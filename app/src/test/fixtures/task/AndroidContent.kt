package android.content

import java.io.File

/** Host fixture: never accesses Android services, package manager, or a device. */
open class Context(val filesDir: File, val cacheDir: File = File(filesDir, "cache"), val packageName: String = "com.dsh.android") {
    val applicationContext: Context get() = this
}
class Intent
