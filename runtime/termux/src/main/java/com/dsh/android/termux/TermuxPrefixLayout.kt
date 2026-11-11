package com.dsh.android.termux

import android.system.Os
import com.dsh.android.log.DshLog as Log
import java.io.File
import java.nio.file.Files

/** prefix 别名、dpkg 目录和 apt 假根布局；不覆盖已有真实文件。 */
internal class TermuxPrefixLayout(
    private val dataDir: String,
    private val rootDir: File,
    private val prefixDir: File,
    private val homeDir: File,
) {
    fun createPrefixAliases() {
        val appDataRoot = File(dataDir).parentFile ?: return
        // ELF spelling: /data/data/com.dsh.android//usr
        ensureSymlink(File(appDataRoot, "usr"), prefixDir)
        ensureSymlink(File(appDataRoot, "home"), homeDir)
        // Text spelling: /data/data/com.dsh.android/files/usr
        ensureSymlink(File(dataDir, "usr"), prefixDir)
        ensureSymlink(File(dataDir, "home"), homeDir)
    }

    /**
     * dpkg opens its compiled-in config directory *before* it parses argv or
     * environment, so `DPKG_CONFIG_DIR`, `DPKG_ADMINDIR`, `DPKG_ROOT` and even
     * `--admindir` are all ignored — it dies with EACCES on the com.termux path
     * regardless. The bootstrap ships `var/lib/dpkg` fully seeded but does not
     * ship `etc/dpkg` at all, so create that skeleton ourselves.
     */
    fun ensureDpkgDirs() {
        File(prefixDir, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(prefixDir, "etc/dpkg/origins").mkdirs()
        val cfg = File(prefixDir, "etc/dpkg/dpkg.cfg")
        if (!cfg.exists()) cfg.writeText("")
    }

    /**
     * Build the fake root that makes dpkg unpack into the real prefix.
     *
     * Termux `.deb` files store their payload under the absolute path they
     * expect to be installed at:
     *   `./data/data/com.termux/files/usr/lib/libz.so.1.3.2`
     * and dpkg's `instdir` defaults to `/`. So dpkg literally tries to
     * `stat("./data/data/com.termux")` under the filesystem root — a directory
     * owned by com.termux, unreadable by this app — and the install dies with
     *   `unable to stat './data/data/com.termux' …: Permission denied`
     * even after every path string in dpkg/apt has been rewritten.
     *
     * Fix: point `--instdir` at a directory tree that *contains* that path as a
     * symlink back to our own data dir. dpkg then resolves
     * `<root>/data/data/com.termux/files/usr/…` straight into the real prefix,
     * and no package needs repacking. `var/lib/dpkg` is linked for the same
     * reason: dpkg derives its admin dir from `instdir`, and without the link it
     * would silently create an *empty* database there (every package would then
     * look uninstalled and get reinstalled wholesale).
     *
     * A symlink rather than a bind mount because this runs as an ordinary app —
     * no root, no mount namespace.
     */
    fun ensureAptInstdirRoot() {
        val root = File(rootDir, "aptroot")
        File(root, "data/data").mkdirs()
        File(root, "var/lib").mkdirs()
        val appDataRoot = File(dataDir).parentFile ?: return
        ensureSymlink(File(root, "data/data/com.termux"), appDataRoot)
        ensureSymlink(File(root, "var/lib/dpkg"), File(prefixDir, "var/lib/dpkg"))
    }

    private fun ensureSymlink(linkPath: File, target: File) {
        try {
            // Never clobber a real file or directory that happens to sit there.
            if (Files.isSymbolicLink(linkPath.toPath())) linkPath.delete()
            else if (linkPath.exists()) return
            Os.symlink(target.absolutePath, linkPath.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "symlink ${linkPath.name}: ${e.message}")
        }
    }

    private companion object { const val TAG = "TermuxBootstrap" }
}
