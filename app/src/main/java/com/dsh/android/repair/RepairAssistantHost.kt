package com.dsh.android.repair

import android.app.Activity
import android.content.Intent

/** Optional assistant-only extension. The normal DSH host has no rescue client dependency. */
interface RepairAssistantHost : AutoCloseable {
    fun open()
    fun onStart()
    fun onPause()
    fun onUserInteraction()
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean

    companion object {
        fun create(activity: Activity): RepairAssistantHost? {
            if (activity.packageName != "com.dsh.fixhelp") return null
            return Class.forName("com.dsh.android.repair.assistant.RepairHostController")
                .getConstructor(Activity::class.java).newInstance(activity) as RepairAssistantHost
        }
    }
}
