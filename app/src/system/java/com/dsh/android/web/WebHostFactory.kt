package com.dsh.android.web

import android.content.Context
import android.webkit.CookieManager
import com.dsh.android.log.DshLog as Log
import com.dsh.android.core.DshApplication
import com.dsh.android.ui.MainActivity

/**
 * 建当前 flavor 的 Web 内核宿主。
 *
 * ⚠️ 这个函数**每个 flavor 源集各有一份**（`src/system/.../WebHostFactory.kt` 与
 *   `src/gecko/.../WebHostFactory.kt`），函数签名必须一致 —— 它是两个内核版本
 *   对 `MainActivity` 的唯一接缝。同名类在两个源集里不会冲突：AGP 一次只编译
 *   一个 flavor 的源集。
 */
fun createWebHost(context: Context, callbacks: WebHostCallbacks): WebHost {
    purgeAuthCookies()
    return SystemWebHost(context, callbacks)
}

/**
 * 清掉 WebView 累积的鉴权 cookie。
 *
 * ## 为什么必须清（真机实测，2026-10-17，先在 gecko flavor 上定位到）
 *
 * 引擎打印的 URL 带一次性 token，它对主文档回 **303 + `Set-Cookie`** 完成
 * token→cookie 交换，而那个 cookie 的名字**每次都不一样**
 * （`dsh-auth-<随机后缀>`，`Max-Age=2592000` 即 30 天）。于是**每启动一次就多一个**，
 * 且 30 天内不会被回收。
 *
 * 累积到一定数量后，请求头里的 `Cookie` 超过服务器上限，引擎对首页直接回
 * **431 Request Header Fields Too Large 且响应体为空** —— 症状是**永久白屏**，
 * 且极具迷惑性：地址正确、没有任何 JS 报错、`#root` 与 `document.scripts` 全空、
 * console 一条不输出。gecko flavor 实测累积到 **142 个** cookie 时稳定复现，
 * 清掉 cookies 库后同一份代码立刻恢复渲染。
 *
 * ## 为什么整体清而不是只删过期的
 *
 * 本 App 的内核里**只有**引擎的鉴权 cookie（外链一律交系统浏览器），而每次启动
 * 都会重新交换出一个新的，所以整个 jar 都是可丢的。只删过期项要逐个枚举，
 * 收益为零。
 *
 * ⚠️ `removeAllCookies` 是**异步**的，所以在宿主创建时（早于导航）发起；即便
 *    本次导航仍带上一轮的那一两个 cookie 也无害 —— 关键是**不让它长期累积**。
 */
private fun purgeAuthCookies() {
    runCatching { CookieManager.getInstance().removeAllCookies(null) }
        .onFailure { Log.w(DshApplication.TAG, "清理 WebView cookie 失败（忽略）", it) }
}
