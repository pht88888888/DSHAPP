package com.dsh.android.bubble

import android.content.Context
import android.content.res.Configuration

/** Activity 学习、悬浮窗读取的键盘高度。保持既有文件名与朝向键，避免相互复制协议。 */
object BubbleImePreferences {
    const val PREFS = "dsh_ime"

    fun keyFor(context: Context): String =
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "ime_height_land"
        } else {
            "ime_height_port"
        }

    fun learnedHeight(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(keyFor(context), 0)

    fun saveHeight(context: Context, heightPx: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(keyFor(context), heightPx).apply()
    }
}
