package com.dsh.android.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream

/**
 * 一次截图事务拥有临时可见性、提示隐藏、比帧 Bitmap、超时和结果回调。
 * UI 宿主只提供真实布局 View，标签身份由调用方的有效性检查限定。
 * PixelCopy 已交给系统的 Bitmap 必须等系统回调后回收，不能在取消时提前 recycle。
 */
internal class BrowserCaptureService(
    private val surface: View,
    private val suppressIndicator: () -> (() -> Unit),
) {
    private val handler = Handler(Looper.getMainLooper())
    private val tasks = LinkedHashSet<CaptureTask>()
    private var closed = false
    private var visibilityLeases = 0
    private var originalVisibility = View.VISIBLE

    fun capture(
        webView: WebView,
        isValid: () -> Boolean,
        stable: Boolean,
        onBitmap: (Bitmap?) -> Unit,
    ): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || !isValid()) {
            onBitmap(null)
            return {}
        }
        val task = CaptureTask(webView, isValid, stable, onBitmap)
        tasks.add(task)
        task.start()
        return { task.finish(null) }
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        tasks.toList().forEach { it.finish(null) }
    }

    /** 接收 Bitmap 所有权，无论压缩或写入是否成功都释放。 */
    fun save(bitmap: Bitmap?, directory: File, onPath: (String?) -> Unit) {
        if (bitmap == null) {
            onPath(null)
            return
        }
        var output: File? = null
        val path = try {
            check(!closed) { "浏览器已销毁" }
            check(directory.exists() || directory.mkdirs()) { "无法创建截图目录" }
            ShotStore.prune(directory)
            val file = File.createTempFile("shot-${System.currentTimeMillis()}-", ".png", directory)
            output = file
            FileOutputStream(file).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "截图压缩失败" }
            }
            Log.i("AiBrowser", "截图已保存: ${file.absolutePath}")
            file.absolutePath
        } catch (error: Throwable) {
            Log.w("AiBrowser", "截图落盘失败: ${error.message}")
            output?.delete()
            null
        } finally {
            bitmap.recycle()
        }
        onPath(path)
    }

    private fun acquireVisibility(): () -> Unit {
        if (visibilityLeases++ == 0) {
            originalVisibility = surface.visibility
            if (originalVisibility != View.VISIBLE) surface.visibility = View.VISIBLE
        }
        var released = false
        return {
            if (!released) {
                released = true
                if (--visibilityLeases == 0 && surface.visibility == View.VISIBLE) {
                    surface.visibility = originalVisibility
                }
            }
        }
    }

    private inner class CaptureTask(
        private val webView: WebView,
        private val isValid: () -> Boolean,
        private val stable: Boolean,
        private var callback: ((Bitmap?) -> Unit)?,
    ) {
        private val token = Any()
        private var restoreVisibility: (() -> Unit)? = null
        private var restoreIndicator: (() -> Unit)? = null
        private var previous: Bitmap? = null
        private var attempts = 0
        private val deadline = SystemClock.elapsedRealtime() + 3_000L
        private val timeout = Runnable { finish(null) }
        private val nextFrame = Runnable { takeFrame() }
        private val waitForSurface = Runnable { webView.postOnAnimation(nextFrame) }

        private fun valid(): Boolean = callback != null && !closed && isValid()

        fun start() {
            restoreVisibility = acquireVisibility()
            restoreIndicator = suppressIndicator()
            handler.postDelayed(timeout, 5_500L)
            // 可见性和浮层变更先经过合成，再读取窗口像素。
            webView.postOnAnimation(waitForSurface)
        }

        private fun takeFrame() {
            if (!valid() || webView.width <= 0 || webView.height <= 0) {
                finish(null)
                return
            }
            val location = IntArray(2)
            webView.getLocationInWindow(location)
            val x = location[0]
            val y = location[1]
            val activity = surface.context as? Activity
            if (webView.windowToken == null || activity == null || x < 0 || y < 0) {
                receiveFrame(draw())
                return
            }
            val bitmap = try {
                Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            } catch (error: Throwable) {
                Log.w("AiBrowser", "截图分配失败: ${error.message}")
                finish(null)
                return
            }
            try {
                PixelCopy.request(
                    activity.window,
                    Rect(x, y, x + webView.width, y + webView.height),
                    bitmap,
                    { result ->
                        if (!valid()) {
                            bitmap.recycle()
                            finish(null)
                        } else if (result == PixelCopy.SUCCESS) {
                            receiveFrame(bitmap)
                        } else {
                            bitmap.recycle()
                            Log.w("AiBrowser", "PixelCopy 失败 code=$result，退回 draw()")
                            receiveFrame(draw())
                        }
                    },
                    handler,
                )
            } catch (error: Throwable) {
                bitmap.recycle()
                Log.w("AiBrowser", "PixelCopy 调用异常: ${error.message}")
                receiveFrame(draw())
            }
        }

        private fun draw(): Bitmap? {
            if (!valid()) return null
            var bitmap: Bitmap? = null
            return try {
                Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also {
                    bitmap = it
                    webView.draw(Canvas(it))
                }
            } catch (error: Throwable) {
                bitmap?.recycle()
                Log.w("AiBrowser", "截图失败: ${error.message}")
                null
            }
        }

        private fun receiveFrame(bitmap: Bitmap?) {
            if (!valid()) {
                bitmap?.recycle()
                finish(null)
                return
            }
            if (bitmap == null || !stable) {
                finish(bitmap)
                return
            }
            val old = previous
            val same = old != null && sameFrame(old, bitmap)
            old?.recycle()
            previous = bitmap
            attempts++
            if (same || attempts >= 12 || SystemClock.elapsedRealtime() >= deadline) {
                if (!same) Log.w("AiBrowser", "画面未静止就超时交图（tries=$attempts），可能仍在动画中")
                previous = null
                finish(bitmap)
            } else {
                handler.postAtTime(nextFrame, token, SystemClock.uptimeMillis() + 150L)
            }
        }

        fun finish(bitmap: Bitmap?) {
            val consumer = callback
            if (consumer == null) {
                bitmap?.recycle()
                return
            }
            callback = null
            tasks.remove(this)
            handler.removeCallbacks(timeout)
            handler.removeCallbacksAndMessages(token)
            webView.removeCallbacks(waitForSurface)
            webView.removeCallbacks(nextFrame)
            previous?.recycle()
            previous = null
            restoreVisibility?.invoke()
            restoreVisibility = null
            restoreIndicator?.invoke()
            restoreIndicator = null
            try {
                consumer(bitmap)
            } catch (error: Throwable) {
                bitmap?.takeUnless { it.isRecycled }?.recycle()
                Log.w("AiBrowser", "截图回调失败: ${error.message}")
            }
        }
    }

    private fun sameFrame(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val stepX = (a.width / 24).coerceAtLeast(1)
        val stepY = (a.height / 48).coerceAtLeast(1)
        var different = 0
        var total = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) different++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && different * 100 / total <= 2
    }
}
