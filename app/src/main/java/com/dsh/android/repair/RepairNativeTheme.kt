package com.dsh.android.repair

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import kotlin.math.pow

/** Visual snapshot only. No theme value may select a target, scope, grant, task or approval. */
internal object RepairNativeTheme {
    const val EXTRA = "repair_theme"
    private const val PREFS = "repair-native-theme"
    private const val LAST = "last-valid"
    private const val MAX_BYTES = 2048
    data class Palette(val dark: Boolean, val background: Int, val surface: Int, val text: Int, val primary: Int)

    /** Run on the Activity worker: persistent fallback must not do disk IO on the UI thread. */
    fun load(context: Context, raw: String?): Palette {
        val prefs = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        parse(raw)?.let { palette ->
            // Only a signed caller accepted by the native Activity can reach this persistence.
            runCatching { prefs?.edit()?.putString(LAST, raw)?.apply() }
            return palette
        }
        parse(runCatching { prefs?.getString(LAST, null) }.getOrNull())?.let { return it }
        return fallback(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }

    fun parse(raw: String?): Palette? = runCatching {
        require(raw != null && raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(raw)
        require(json.keys().asSequence().toSet() == setOf("dark", "colors"))
        require(json.get("dark") is Boolean)
        val colors = json.getJSONObject("colors")
        require(colors.keys().asSequence().toSet() == setOf("background", "surface", "text", "primary"))
        fun color(key: String): Int {
            val value = colors.get(key)
            require(value is String && value.matches(Regex("#[0-9a-fA-F]{6}")))
            return (0xff000000L or value.substring(1).toLong(16)).toInt()
        }
        val palette = Palette(json.getBoolean("dark"), color("background"), color("surface"), color("text"), color("primary"))
        require(contrast(palette.text, palette.background) >= 4.5 && contrast(palette.text, palette.surface) >= 4.5)
        require(contrast(palette.primary, palette.surface) >= 3.0)
        require((luminance(palette.background) < 0.5) == palette.dark)
        palette
    }.getOrNull()

    fun fallback(dark: Boolean) = if (dark) Palette(true, 0xff111318.toInt(), 0xff1d2026.toInt(), 0xffe3e2e9.toInt(), 0xffb8c4ff.toInt())
        else Palette(false, 0xfff9f9ff.toInt(), 0xfff0f0f7.toInt(), 0xff191b22.toInt(), 0xff4357a5.toInt())

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v = ((color ushr shift) and 255) / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
    private fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
    private fun Activity.dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    fun builder(activity: Activity, palette: Palette, title: String, message: String): MaterialAlertDialogBuilder {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.window.setBackgroundDrawable(ColorDrawable(palette.background))
        activity.window.statusBarColor = palette.background
        activity.window.navigationBarColor = palette.background
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val heading = TextView(activity).apply {
            text = title; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.text); setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val body = TextView(activity).apply {
            text = message; textSize = 15f; setTextColor(palette.text)
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(16))
            setTextIsSelectable(true); autoLinkMask = 0
        }
        return MaterialAlertDialogBuilder(activity).setBackground(ColorDrawable(palette.surface))
            .setCustomTitle(heading).setView(ScrollView(activity).apply { addView(body) })
    }

    fun protect(dialog: AlertDialog, palette: Palette) {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                filterTouchesWhenObscured = true
                setTextColor(palette.primary)
                minHeight = (48 * resources.displayMetrics.density).toInt()
            }
        }
    }
    fun busy(dialog: AlertDialog, busy: Boolean) {
        dialog.setCancelable(!busy)
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) dialog.getButton(which)?.isEnabled = !busy
    }
}
