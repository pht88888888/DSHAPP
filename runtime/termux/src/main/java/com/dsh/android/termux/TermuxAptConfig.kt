package com.dsh.android.termux

import com.dsh.android.log.DshLog as Log
import java.io.File

/** apt/dpkg 配置、包安装 hook 和 keyring；写入顺序由引导器编排。 */
internal class TermuxAptConfig(
    private val rootDir: File,
    private val prefixDir: File,
    private val elfRootReplacement: String,
) {
    /** Write APT config for a faster, non-interactive Termux experience. */
    fun writeAptConfig() {
        val confDir = File(prefixDir, "etc/apt/apt.conf.d")
        confDir.mkdirs()
        // Disable recommends/suggests — they pull in X11 etc. Termux doesn't need.
        File(confDir, "01-norecommends").writeText("""
APT::Get::Install-Recommends "false";
APT::Get::Install-Suggests "false";
""".trimIndent())
        // Non-interactive, assume yes.
        File(confDir, "02-options").writeText("""
APT::Get::Assume-Yes "true";
APT::Get::AllowUnauthenticated "true";
Dpkg::Options {"--force-confdef";"--force-confold"};
""".trimIndent())
        // Redirect apt's archive cache. libapt-pkg.so has
        // `/data/data/com.termux/cache/apt` compiled in, and that path does NOT
        // match the `/data/data/com.termux/files` rule the ELF patcher rewrites
        // (its root is `…/com.termux/cache`, a sibling of `…/com.termux/files`).
        // Left alone, every download dies with
        //   E: Archives directory /data/data/com.termux/cache/apt/archives/partial
        //      is missing. - Acquire (13: Permission denied)
        // `Dir::Cache` is a plain apt.conf default, so overriding it here is
        // enough — no need to touch the shared library.
        File(confDir, "03-cache").writeText("""
Dir::Cache "$prefixDir/var/cache/apt";
Dir::Cache::archives "$prefixDir/var/cache/apt/archives";
""".trimIndent())
        // Make dpkg unpack into our prefix instead of the filesystem root.
        // See ensureAptInstdirRoot for why bare `/` cannot work. The braced
        // list form is required: apt.conf.d splits `DPkg::Options::=` on
        // whitespace, so the un-braced spelling silently truncates the path.
        val instdir = File(rootDir, "aptroot").absolutePath
        File(confDir, "04-instdir").writeText("""
DPkg::options { "--instdir=$instdir"; };
""".trimIndent())
        // Same setting for direct `dpkg` invocations, which never read
        // apt.conf.d. dpkg.cfg uses the bare `option value` form — this file is
        // NOT apt.conf syntax, and quoting the value or adding a semicolon makes
        // dpkg abort with "unbalanced quotes in 'instdir'".
        //
        // All three lines are required; each one was surfaced only after fixing
        // the previous, measured on device with `dpkg --configure -a`:
        //
        //  1. `instdir` alone            → "admindir must be inside instdir for
        //                                   dpkg to work properly"
        //  2. + `admindir`               → "failed to chroot to '<aptroot>':
        //                                   Operation not permitted" — an app uid
        //                                   has no CAP_SYS_CHROOT, so dpkg can
        //                                   never chroot no matter what
        //  3. + `force-script-chrootless` → maintainer scripts finally run
        //
        // Only packages that *have* a maintainer script trip 1 and 2, which is
        // why `wget` installed cleanly while `glib`/`openssh` always failed.
        //
        // Line 4 is a `post-invoke` sweep (see [installShebangFixHook]). It has
        // to live in **dpkg.cfg**, not apt.conf.d: dpkg runs maintainer scripts
        // in a separate `--configure` pass *after* `--unpack`, and apt's
        // `DPkg::Post-Invoke` only fires once the whole transaction is done —
        // far too late. Measured with `python-pip`, whose postinst shells out to
        // `pip` itself: the apt-level hook did rewrite the shebang, but only
        // after the postinst had already died on it.
        File(prefixDir, "etc/dpkg/dpkg.cfg").writeText(
            "instdir $instdir\n" +
                "admindir $instdir/var/lib/dpkg\n" +
                "force-script-chrootless\n" +
                "post-invoke $shebangHookPath\n"
        )
        installDebFixHook()
    }

    /**
     * Install the apt hook that repairs `.deb` control scripts before dpkg runs
     * them.
     *
     * [rewriteTermuxPaths] fixes the paths of everything **already on disk**.
     * That is not enough: every `pkg install` unpacks a *fresh* `.deb` whose
     * maintainer scripts still carry the official Termux prefix, so dpkg writes
     * those official paths back over our repaired copies. Measured on device —
     * re-installing `glib` after a manual fix restored the broken shebang and
     * the package dropped straight back to `iF`.
     *
     * Maintainer scripts are not cosmetic. `openssh.postinst` is what generates
     * the sshd host keys and `glib.postinst` is what compiles the GSettings
     * schema cache; when the script dies on its shebang the package still ends
     * up "installed" but silently half-working, which is exactly the
     * "能用但不好用" failure mode.
     *
     * `DPkg::Pre-Install-Pkgs` is invoked once per dpkg transaction with every
     * `.deb` path on stdin (measured: one PRE / one POST for a 10-package
     * install), so the hook is the single interception point where a package can
     * still be repaired before it lands. It rewrites the prefix inside the
     * `control.tar.*` member and repacks with `ar` — the payload member is
     * copied through untouched, so package contents are never rewritten.
     *
     * The hook runs under `/system/bin/sh` (mksh, ships with Android) rather
     * than our prefix's shell: a hook that can only run once the prefix works
     * would be useless on exactly the installs that need it.
     */
    private fun installDebFixHook() {
        // The sweep rewrites the official prefix inside ELF binaries, which is
        // only safe because the two strings are the same length (pure
        // overwrite — no offsets shift). The sweep interpolates elfRootReplacement
        // into itself, so assert the invariant here: refuse to write a hook that
        // would silently corrupt every binary it touched.
        check(OLD_TERMUX_ROOT.length == elfRootReplacement.length) {
            "Termux path rewrite must be equal length: " +
                "${OLD_TERMUX_ROOT.length} != ${elfRootReplacement.length}"
        }
        try {
            val hookDir = File(prefixDir, "etc/apt/hooks")
            val confDir = File(prefixDir, "etc/apt/apt.conf.d")
            hookDir.mkdirs()
            confDir.mkdirs()

            val hook = File(hookDir, "boo-fix-deb.sh")
            hook.writeText(debFixHookScript())
            hook.setExecutable(true, false)

            // Repairs the payload *after* unpack; referenced by dpkg.cfg, not
            // apt.conf.d — see writeAptConfig for why the timing matters.
            val sweep = File(hookDir, "boo-fix-paths.sh")
            sweep.writeText(shebangFixHookScript())
            sweep.setExecutable(true, false)

            File(confDir, "97-boo-fixdeb").writeText(
                "DPkg::Pre-Install-Pkgs { \"${hook.absolutePath}\"; };\n"
            )
        } catch (e: Exception) {
            Log.w(TAG, "install deb fix hook: ${e.message}")
        }
    }

    /** Path of the post-unpack sweep hook, referenced from `dpkg.cfg`. */
    private val shebangHookPath: String
        get() = File(prefixDir, "etc/apt/hooks/boo-fix-paths.sh").absolutePath

    /**
     * Body of `etc/apt/hooks/boo-fix-paths.sh`, wired in as dpkg's `post-invoke`.
     *
     * [debFixHookScript] repairs the `.deb` **control** member — the scripts
     * dpkg executes. That leaves the files dpkg *installs* alone, and a fresh
     * payload carries the official prefix in two more places:
     *
     *  1. **Shebangs of the installed entry points.** `python-pip` ships
     *     `$PREFIX/bin/pip` starting with `#!/data/data/com.termux/files/usr/bin/
     *     python3.14`. The package reaches `ii`, and the moment anything runs
     *     `pip` it dies with `bad interpreter: No such file or directory`.
     *  2. **Plain-text data files.** Termux's own packages are patched upstream
     *     to hardcode `/data/data/com.termux/files/usr/etc` as the site config
     *     root — `pip/_vendor/platformdirs/unix.py` and `pip/_internal/utils/
     *     appdirs.py` both do. `pip` then writes `/data/data/com.termux/files/
     *     usr/etc/pip.conf` and fails with `PermissionError: [Errno 13]`.
     *
     * Both are rewritten here, right after unpack and before `--configure`, so a
     * package's own postinst sees a self-consistent prefix. Measured: with this
     * hook in place `python-pip` configures cleanly and `pip download six` works.
     *
     * `grep -I` skips binaries, which is what keeps the sweep safe — an ELF
     * cannot be length-changed, and [patchElfInPlace] already handled the ones
     * that matter. The whole prefix costs well under a second (measured: 0 s for
     * ~1,900 candidate files), so it runs unattended on every transaction rather
     * than trying to guess which paths a package touched.
     *
     * The log lives in `$PREFIX/tmp` and is append-only, so a failed install can
     * be diagnosed after the fact; the hook always exits 0 and must never turn a
     * working install into a failing one.
     */
    private fun shebangFixHookScript(): String = """
        #!/system/bin/sh
        # Boo: repoint official-Termux paths left behind by a freshly unpacked
        # payload. Runs as dpkg `post-invoke`, i.e. after --unpack and before
        # --configure, so maintainer scripts see a consistent prefix.
        PREFIX=$prefixDir
        T=com.termux
        OLD=/data/data/${'$'}T/files/usr
        PATH=${'$'}PREFIX/bin:/system/bin
        export PATH
        LOG=${'$'}PREFIX/tmp/boo-fix-paths.log
        ch=0

        # 1. Shebangs of installed entry points. `bin` holds ~450 files and
        #    `libexec` ~107; a single `grep -l` over the globs keeps this at a
        #    few milliseconds instead of a shell loop over every file.
        for d in bin libexec; do
          for f in $(grep -l -a "^#!${'$'}OLD" "${'$'}PREFIX/${'$'}d"/* 2>/dev/null); do
            sed -i "1s|${'$'}OLD|${'$'}PREFIX|" "${'$'}f" 2>/dev/null && echo "shebang ${'$'}f" >> "${'$'}LOG"
            ch=1
          done
        done

        # 2. Maintainer scripts sitting in dpkg's own database. `--unpack`
        #    extracts every package's scripts into `var/lib/dpkg/info/` and
        #    `--configure` executes them from there, so this is the copy that
        #    actually runs, and the sweep above deliberately skips the whole
        #    `var/lib/dpkg` tree. The `.deb` repair in [debFixHookScript] should
        #    have already cleaned them at the source; doing it again here is the
        #    safety net for the case where that hook could not run at all, which
        #    is exactly how the `ar`-less baseline failed before. Measured on
        #    device: `dpkg --unpack vim` leaves 11 official-prefix hits in
        #    `vim.postinst` and 9 in `vim.prerm`, and fixing them here is what
        #    lets `--configure` succeed.
        for f in "${'$'}PREFIX"/var/lib/dpkg/info/*.postinst \
                 "${'$'}PREFIX"/var/lib/dpkg/info/*.preinst \
                 "${'$'}PREFIX"/var/lib/dpkg/info/*.prerm \
                 "${'$'}PREFIX"/var/lib/dpkg/info/*.postrm; do
          [ -f "${'$'}f" ] || continue
          grep -q "${'$'}OLD" "${'$'}f" 2>/dev/null || continue
          sed -i "s|${'$'}OLD|${'$'}PREFIX|g" "${'$'}f"
          # Same DPKG_ROOT trap as in the .deb repair — see that hook for the
          # full account. Idempotent, so applying it twice is harmless.
          grep -q '^unset DPKG_ROOT${'$'}' "${'$'}f" || sed -i '1a unset DPKG_ROOT' "${'$'}f"
          echo "info ${'$'}f" >> "${'$'}LOG"
          ch=1
        done

        # 3. Plain-text files anywhere in the prefix. `-I` skips binaries; a
        #    length-changing sed on an ELF would corrupt it. dpkg's own metadata
        #    is skipped: rewriting `info/*.list` would desynchronise the file
        #    list from what dpkg believes it installed, and the apt logs are
        #    historical records, not live configuration. The hooks are skipped so
        #    this script cannot rewrite its own needle.
        for f in $(grep -rl -I "${'$'}OLD" "${'$'}PREFIX" 2>/dev/null); do
          case "${'$'}f" in
            "${'$'}PREFIX"/var/lib/dpkg/*|"${'$'}PREFIX"/var/log/*|"${'$'}PREFIX"/tmp/*) continue ;;
            "${'$'}PREFIX"/etc/apt/hooks/*) continue ;;
          esac
          sed -i "s|${'$'}OLD|${'$'}PREFIX|g" "${'$'}f" 2>/dev/null && echo "text ${'$'}f" >> "${'$'}LOG"
          ch=1
        done

        # 4. ELF binaries, equal-length rewrite only. A fresh .deb payload carries
        #    the official prefix inside its executables too — `git` bakes it into
        #    12 strings (its templates dir, `etc/gitconfig`, the sh/bash/login it
        #    hands to hooks, and RUNPATH). Unpatched, every `git init` prints
        #    "warning: templates not found in" the com.termux path and the
        #    compiled-in RUNPATH sends the linker into com.termux's private dir.
        #
        #    The substitution MUST be byte-for-byte equal length (27 -> 27): that
        #    is what makes a plain `sed` safe on an ELF — nothing shifts, no
        #    `.dynstr` index moves, no neighbouring string gets clipped. This is
        #    the same rule [patchElfInPlace] applies at bootstrap; the difference
        #    is that it runs only once, at unpack time, so packages installed
        #    *later* never got it. Verified on device: 3613096 bytes before and
        #    after, 0 residual hits, `git --version` still answers 2.56.0.
        #
        #    ⚠️ ELF_OLD is assembled from T for the same reason the hooks above
        #    are: [rewriteTermuxPaths] walks this very file and rewrites every
        #    literal occurrence of the official root, so spelling it out in full
        #    would make the rewriter substitute the longer real prefix, the
        #    lengths would stop matching, and every binary this loop touched
        #    would be silently corrupted.
        #    ⚠️ ELF_NEW is interpolated from elfRootReplacement so there is a
        #    single source of truth for the 27-byte spelling;
        #    [installDebFixHook] refuses to write this script at all unless that
        #    constant matches OLD_TERMUX_ROOT in length.
        ELF_OLD=/data/data/${'$'}T/files
        ELF_NEW=$elfRootReplacement
        for d in bin libexec lib; do
          [ -d "${'$'}PREFIX/${'$'}d" ] || continue
          for f in ${'$'}(grep -rl -a "${'$'}ELF_OLD" "${'$'}PREFIX/${'$'}d" 2>/dev/null); do
            sed -i "s|${'$'}ELF_OLD|${'$'}ELF_NEW|g" "${'$'}f" 2>/dev/null && echo "elf ${'$'}f" >> "${'$'}LOG"
            ch=1
          done
        done

        # Python bytecode caches the string constants it was compiled from, so a
        # stale .pyc keeps the old path alive after its .py has been fixed.
        # Gated on `ch` — an untouched transaction must not throw away every
        # cache in the prefix, and a recompile on next import is the whole cost.
        if [ "${'$'}ch" = "1" ]; then
          find "${'$'}PREFIX/lib/python"* -name '*.pyc' -delete 2>/dev/null
        fi

        exit 0
    """.trimIndent()

    /**
     * Body of `etc/apt/hooks/boo-fix-deb.sh`.
     *
     * ⚠️ The official prefix is spelled as `$T` + `/files/usr` on purpose.
     * [rewriteTermuxPaths] rewrites every plain-text occurrence of
     * [OLD_TERMUX_ROOT], and it walks this very file — writing the prefix out in
     * full would make the rewriter blank its own needle, and the hook would then
     * silently match nothing. Splitting the literal keeps it out of reach.
     *
     * The archive is unpacked and repacked with `dpkg-deb`, **not** `ar`.
     * `ar` is not part of the baseline: the prefix has no `binutils`, and
     * `$PREFIX/bin/ar` only resolves to `llvm-ar` — which the `llvm` package
     * provides, and which a cold `pkg install python` pulls in as a dependency
     * *of that very transaction*. A hook that needs `ar` therefore fails on the
     * first package that carries a maintainer script, and fails **silently**
     * (the old code ended the loop iteration on a failed extract), leaving the
     * `.deb` untouched and the install dead at `dpkg returned an error code
     * (1)`. Measured 2026-09-27: the model then spent 12 rounds hand-patching
     * dpkg's own maintainer-script copies under `var/lib/dpkg/info/` on device
     * to get python installed at all.
     *
     * ⚠️ Never write a bare path glob in a KDoc block here. Kotlin block
     * comments **nest**, so the slash-star that a glob such as `info` + `/*`
     * contains opens a second comment level, and every `*/` after it closes the
     * wrong one — the compiler then reports `Unclosed comment` at end of file
     * and every symbol in the class reads as unresolved. Cost me one build.
     *
     * `dpkg-deb` ships inside the `dpkg` package itself, which is already
     * present before the very first `apt install` — so it is the one extractor
     * guaranteed to exist. It also removes the hand-rolled `ar` member ordering:
     * `debian-binary` / `control.tar.*` / `data.tar.*` is emitted by dpkg-deb in
     * the required order, where the old `ar rc …` had to spell it out.
     *
     * ⚠️ `dpkg-deb -R` extracts maintainer scripts as mode 644 while `-b`
     * refuses anything below 0555 (`maintainer script 'preinst' has bad
     * permissions 644`), so the scripts are `chmod 755`ed before repacking.
     * Measured: without that chmod the rebuild aborts and `termux-tools` — the
     * one baseline package with a `preinst` — fails with `unable to execute new
     * termux-tools package pre-installation script … No such file or
     * directory`.
     */
    private fun debFixHookScript(): String = """
        #!/system/bin/sh
        # Boo: rewrite the Termux prefix baked into .deb control scripts.
        # apt passes the .deb paths on stdin via DPkg::Pre-Install-Pkgs.
        PREFIX=$prefixDir
        T=com.termux
        OLD=/data/data/${'$'}T/files/usr
        PATH=${'$'}PREFIX/bin:/system/bin
        export PATH
        # ⚠️ Prefer apt's own TMPDIR. Under the `workspace-write` preset the
        # shell layer relocates TMPDIR into the workspace (and under `read-only`
        # it clears it entirely), so hardcoding `${'$'}PREFIX/tmp` would scribble
        # outside the directory the sandbox actually allows. Fall back to the
        # prefix only when TMPDIR is unset or dangling — that is the
        # full-access case, where nothing is confined anyway.
        TMPROOT=${'$'}{TMPDIR:-}
        [ -n "${'$'}TMPROOT" ] && [ -d "${'$'}TMPROOT" ] || TMPROOT=${'$'}PREFIX/tmp
        LOG=${'$'}TMPROOT/boo-fix-deb.log
        TMPD=${'$'}TMPROOT/bfd.${'$'}${'$'}
        mkdir -p "${'$'}TMPD" || exit 0

        while read -r deb; do
          [ -f "${'$'}deb" ] || continue
          case "${'$'}deb" in *.deb) ;; *) continue ;; esac

          # Cheap gate: pull just the control member first. Most of a
          # transaction needs no repair at all, and a package that is left
          # byte-identical is strictly better than one needlessly rebuilt.
          # ⚠️ `-e` writes the control files straight into the directory given,
          # whereas `-R` nests them under `DEBIAN/` — hence the two spellings.
          e=${'$'}TMPD/e
          rm -rf "${'$'}e"; mkdir -p "${'$'}e" || continue
          dpkg-deb -e "${'$'}deb" "${'$'}e" 2>/dev/null || continue

          need=0
          for f in "${'$'}e"/postinst "${'$'}e"/preinst "${'$'}e"/prerm \
                   "${'$'}e"/postrm "${'$'}e"/triggers "${'$'}e"/control; do
            [ -f "${'$'}f" ] || continue
            grep -q "${'$'}OLD" "${'$'}f" 2>/dev/null && need=1
          done
          [ "${'$'}need" = "0" ] && continue

          d=${'$'}TMPD/w
          rm -rf "${'$'}d"; mkdir -p "${'$'}d" || continue
          dpkg-deb -R "${'$'}deb" "${'$'}d" >>"${'$'}LOG" 2>&1 || continue

          ch=0
          # Real maintainer scripts: rewrite the prefix AND clear DPKG_ROOT.
          for f in "${'$'}d/DEBIAN"/postinst "${'$'}d/DEBIAN"/preinst \
                   "${'$'}d/DEBIAN"/prerm "${'$'}d/DEBIAN"/postrm; do
            [ -f "${'$'}f" ] || continue
            # 🔴 `-R` drops maintainer scripts to 0644, and `-b` refuses to
            # rebuild anything below 0555. Measured: the rebuild aborted with
            # "maintainer script 'preinst' has bad permissions 644" and, because
            # the repacked file was never produced, `dpkg --unpack` then died on
            # "/…/tmp.ci/preinst: No such file or directory".
            chmod 755 "${'$'}f"
            grep -q "${'$'}OLD" "${'$'}f" 2>/dev/null || continue
            sed -i "s|${'$'}OLD|${'$'}PREFIX|g" "${'$'}f"
            # 🔴 dpkg exports `DPKG_ROOT=<aptroot>` to every maintainer script
            # (probed on device). Tools that honour it — `update-alternatives`,
            # `dpkg-maintscript-helper` — prepend it to each path they are
            # handed. The paths above are now real, so that prepending lands on
            # `<aptroot>/data/user/0/.../bin/nano`, which does not exist, and
            # the script dies during --configure. Measured: `nano` aborted with
            # "alternative path ... doesn't exist" and stuck at `iF`.
            # Clearing it makes the script agree with the paths it now holds.
            # Inserted after line 1 so a shebang stays first; idempotent.
            grep -q '^unset DPKG_ROOT${'$'}' "${'$'}f" || sed -i '1a unset DPKG_ROOT' "${'$'}f"
            ch=1
          done

          # `triggers`/`control` are metadata parsed by dpkg, not shell — the
          # prefix is rewritten but nothing may be inserted into them.
          for f in "${'$'}d/DEBIAN"/triggers "${'$'}d/DEBIAN"/control; do
            [ -f "${'$'}f" ] || continue
            grep -q "${'$'}OLD" "${'$'}f" 2>/dev/null || continue
            sed -i "s|${'$'}OLD|${'$'}PREFIX|g" "${'$'}f"
            ch=1
          done

          if [ "${'$'}ch" = "1" ]; then
            # Rebuild the whole package: `-b` takes the parent of `DEBIAN/`, and
            # emits a correctly ordered ar archive for us.
            # 🔴 The output must NOT live inside "${'$'}d" — that directory *is* the
            # payload being packed, so `-b "${'$'}d" "${'$'}d/fix.deb"` sweeps the
            # half-written archive into the package and every rebuilt .deb ships
            # a stray `/fix.deb`. Measured: the first rebuilt package
            # (libcompiler-rt) claimed it, and python, glib and python-pip then
            # all died with "trying to overwrite '/fix.deb'". Write to the
            # sibling scratch dir instead.
            # ⚠️ `-Zgzip -z1` is deliberate. The default `-Zxz` re-compresses the
            # payload and costs ~14 s on python's 25 MB of files; python pulls
            # ~20 packages, so the default would add minutes to a single `pkg
            # install`. Measured on device: `-Zxz` 14 s / 4.82 MB, `-Zxz -z0`
            # 1 s / 6.05 MB, `-Zgzip -z1` 0 s / 7.90 MB. The repacked file only
            # ever lives in apt's archive cache long enough for dpkg to read it,
            # so the size costs nothing and the seconds are the whole story.
            # Verified faithful: 1044 of 1044 payload entries preserved with
            # mode-identical listings, and `dpkg --dry-run -i` accepts it.
            dpkg-deb -b -Zgzip -z1 "${'$'}d" "${'$'}TMPD/fix.deb" >>"${'$'}LOG" 2>&1 || continue
            cp "${'$'}TMPD/fix.deb" "${'$'}deb" && echo "fixed $(basename ${'$'}deb)" >> "${'$'}LOG"
          fi
        done

        rm -rf "${'$'}TMPD"
        exit 0
    """.trimIndent()

    /** Copy GPG keyrings from share/termux-keyring/ into etc/apt/trusted.gpg.d/.
     *  Symlinks are avoided: on some Android versions Os.symlink fails for
     *  cross-directory links under the app data dir. */
    fun installGpgKeys(prefixDir: File) {
        val keyDir = File(prefixDir, "share/termux-keyring")
        if (!keyDir.isDirectory) return
        val trustedDir = File(prefixDir, "etc/apt/trusted.gpg.d")
        trustedDir.mkdirs()
        keyDir.listFiles { f -> f.name.endsWith(".gpg") }?.forEach { key ->
            val dest = File(trustedDir, key.name)
            if (!dest.exists()) {
                try {
                    key.inputStream().use { src -> dest.outputStream().use { src.copyTo(it) } }
                    Log.i(TAG, "  gpg key: ${key.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "copy gpg key ${key.name}: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "TermuxBootstrap"
        const val OLD_TERMUX_ROOT = "/data/data/com.termux/files"
    }
}
