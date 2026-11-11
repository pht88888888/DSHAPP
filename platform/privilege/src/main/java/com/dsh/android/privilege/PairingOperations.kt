package com.dsh.android.privilege

/** Blocking pairing operation; the process host supplies installation/runtime coordination. */
fun interface PairingOperations {
    fun pair(code: String, port: Int): WirelessDebugging.Result
}
