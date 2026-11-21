package android.os

class Bundle {
    private val values = mutableMapOf<String, Any?>()
    fun putString(key: String, value: String?) { values[key] = value }
    fun getString(key: String): String? = values[key] as? String
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
    fun getBoolean(key: String, fallback: Boolean = false) = values[key] as? Boolean ?: fallback
    fun putLong(key: String, value: Long) { values[key] = value }
    fun getLong(key: String, fallback: Long = 0) = values[key] as? Long ?: fallback
}
object Process { fun myPid() = -999; fun myUid() = 987654321 }
object SystemClock { @Volatile var now = 1000L; fun elapsedRealtime() = now }
