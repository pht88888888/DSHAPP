package com.dsh.android.repair

import android.content.Context
import com.dsh.android.repair.files.RepairFileBroker
import java.io.File

/** Only platform-owned roots are canonicalized. Requests never supply absolute root paths. */
internal object RepairFileAccess {
    fun broker(context: Context): RepairFileBroker = RepairFileBroker(context.filesDir, roots(context))

    internal fun roots(context: Context): Map<String, File> = linkedMapOf<String, File>().apply {
        fun add(name: String, directory: File?) {
            val root = runCatching { directory?.canonicalFile?.takeIf { it.isDirectory } }.getOrNull() ?: return
            if (values.none { it == root }) put(name, root)
        }
        add("app", context.dataDir)
        // Device-protected storage is separate on API26+. Omit a genuinely unavailable root,
        // never manufacture a caller-selected path or unlock credential storage implicitly.
        add("device_app", runCatching { context.createDeviceProtectedStorageContext().dataDir }.getOrNull())
        fun volumes(prefix: String, directories: Array<out File?>) {
            directories.forEachIndexed { index, file -> add(if (index == 0) prefix else "${prefix}_${index + 1}", file) }
        }
        volumes("external_files", runCatching { context.getExternalFilesDirs(null) }.getOrDefault(emptyArray()))
        volumes("external_cache", runCatching { context.externalCacheDirs }.getOrDefault(emptyArray()))
        volumes("external_media", runCatching { context.externalMediaDirs }.getOrDefault(emptyArray()))
        check(containsKey("app")) { "Application data root is unavailable" }
    }
}
