# DSH for Android

**Run a full AI coding agent on your Android phone.**

[中文](README.md) · [Docs](docs/agents/README.md) · [Contributing](CONTRIBUTING.md)

---

## What is this

DSH for Android brings the [DSH](https://github.com/deepseek-ai) (DeepSeek Harness)
desktop engine to your phone as-is: a bundled Termux environment runs Node, Node runs
the DSH engine, and a WebView loads DSH's own web GUI.

**It does not draw its own chat UI.** The interface is the official frontend, with
mobile adaptation handled by a bundled plugin. What you get is not a stripped-down
"mobile edition" — it is **the same frontend as desktop**, small enough to fit in a pocket.

```
APK
 ├─ Termux bootstrap        bash / dpkg / apt + 64 shared libraries
 ├─ Node runtime            Termux official arm64 node 26.4.0
 ├─ DSH engine              node_modules, unpacked into private storage
 ├─ Native addons           self-built require-builtin / flock / node-pty
 ├─ Web engine (gecko)      GeckoView, independent of system WebView
 └─ Bundled plugins         mobile adaptation / floating bubble / UI probe / logs …
```

## Features

- **Full agent capability** — `bash`, file read/write, `grep`/`glob`, and a terminal PTY
  all work. Not just a read-only chat box.
- **Two web engines** — `system` uses the device WebView (smaller); `gecko` bundles
  GeckoView (twice the size, but works on old devices or where system WebView is
  stripped). **Both can be installed on the same device.**
- **Mobile UI adaptation** — official frontend plus a narrow-screen plugin, not a rewrite.
- **Floating bubble** — summon the agent anytime; custom appearance, frame animation,
  video layers.
- **UI probe tool** — lets the model see the screen and tap buttons, enabling end-to-end
  self-testing.
- **Rescue mode** — when a plugin breaks the app so it won't open, a four-tier
  degradation ladder plus in-app plugin removal.
- **Logging & diagnostics** — three-tier storage, crash-site snapshots, one-tap
  diagnostic export.

## Quick start

### Install

Download an APK from [Releases](../../releases) and sideload it.

| Package | applicationId | For |
|---|---|---|
| `DSH<version>.apk` | `com.dsh.android` | Most devices, smaller |
| `DSH<version>-gecko.apk` | `com.dsh.webview` | Old devices / system WebView unavailable |

The two packages have **different applicationIds and can coexist**, each with its own
independent data directory.

> ⚠️ **arm64-v8a only, and targetSdk = 28.** These are hard constraints, not oversights —
> see [Two hard constraints](#two-hard-constraints).

### Build from source

```powershell
$env:JAVA_HOME = "<path to your JDK 17>"   # must be set explicitly

.\gradlew.bat :app:assembleSystemDebug --console=plain
# → app\build\outputs\apk\system\debug\app-system-debug.apk

.\gradlew.bat :app:assembleGeckoDebug --console=plain
# → app\build\outputs\apk\gecko\debug\app-gecko-debug.apk
```

**Toolchain**: JDK 17 · AGP 8.8.2 · Kotlin 2.0.21 · Gradle 8.13 · compileSdk 35 · minSdk 26

### ⚠️ Build assets are not included

Roughly **135 MB** of build assets under `app/src/main/assets/` are **not in this
repository** (excluded by `.gitignore` to keep it lean). **Without them you cannot
produce a working APK.**

| Asset | Size | Source |
|---|---|---|
| `engine.bin` | 55 MB | DSH engine `node_modules`, packed |
| `node-runtime.bin` | 30 MB | Termux official arm64 node |
| `bootstrap-aarch64.zip` | 23 MB | Termux bootstrap |
| `webview-compat.zip` | 9.3 MB | Frontend bundle compiled down to chrome73 |
| `pnpm-android-arm64.zip` | 5.6 MB | pnpm 11.28.3, pure-JS build |
| `adb-wireless-arm64.zip` | 5.3 MB | Termux official adb |
| `sharp-wasm32.zip` | 3.5 MB | sharp wasm32 variant |
| `ripgrep-android-arm64.zip` | 2.0 MB | Official linux-arm64 static rg |
| 24 others | < 1 MB | Self-built native bindings / bundled plugin zips |

**Asset origins, rebuild scope and missing inputs** are recorded in
[asset rebuilding](docs/agents/regen-assets.md) and [native provenance](docs/architecture/native-provenance.md).
`keyinject` has been retired in favor of the gateway path; only legacy library cleanup remains.

## Two hard constraints

These are **platform facts**, not design choices that can be "optimized away."
Read before changing anything.

### ① The package name must be exactly 15 characters

The final APK applicationIds are `com.dsh.android` and `com.dsh.webview`, both 15 characters.
This restriction applies to runtime application identity, not library namespaces or Kotlin packages.

Termux's official binaries have `/data/data/com.termux/files` (27 bytes) baked into
their ELF `.dynstr`, and `TermuxBootstrap.patchElfInPlace()` can only do an
**in-place overwrite of equal length**. The replacement string
`/data/data/<package>/` is 12 + N bytes = 27 ⇒ **N = 15**.

If the length differs, that function **silently returns 0** without throwing; the
symptom is a runtime `CANNOT LINK EXECUTABLE` or `dpkg: Permission denied`.

### ② targetSdk must be ≤ 28

**Do not raise it to 29+.** Starting with Android 10 (API 29), apps with
targetSdk ≥ 29 are not allowed to `execve()` files inside their own private
directory (W^X / the SELinux `untrusted_app` domain forbids `app_data_file`
`execute_no_trans`).

But node / bash / dpkg all live in `$PREFIX` under `filesDir` — raising it causes
blanket `Permission denied`. Termux's own app also stays at 28.

**The cost**: sideload only, cannot ship on Google Play.

## Architecture

```
MainActivity (WebView / GeckoView)
      │  loads http://127.0.0.1:<port>
      ▼
  DSH Web GUI  ←── official frontend + mobile adaptation plugin
      │
      ▼  HTTP
  DSH engine (Node)
      │
      ├─ tool: bash       → via the boo-sandbox-run sandbox
      ├─ tool: read/write → link translated by liblinkcompat.so
      ├─ tool: grep/glob  → static ripgrep
      └─ tool: ui/bubble  → native bridge (file mailbox)
      │
      ▼
  $PREFIX (Termux environment, under filesDir)
```

### Why so many native layers

On Android, `process.platform` is `'android'`, not `'linux'`, while DSH's official
native addons and platform checks assume one of `linux`/`darwin`/`win32`. Anywhere the
official code only recognizes linux but is otherwise libc-agnostic needs an Android
counterpart.

Android also restricts link and sandbox capabilities, requiring adaptation at the libc boundary:

| Layer | Solves | Docs |
|---|---|---|
| `liblinkcompat.so` | SELinux denies `link()` (`EACCES`/`EINVAL`) | [link-compat.md](docs/agents/link-compat.md) |
| `boo-sandbox-run` + `libboosandbox.so` | No bwrap/Landlock on Android, yet `bash` requires a sandbox | [sandbox.md](docs/agents/sandbox.md) |

## Project layout

```
app/                                  application assembly, Activity/Service, backup and diagnostics
core/contracts/ · core/logging/        narrow contracts, shared models and logging
runtime/termux/ · runtime/engine/      Termux/Node, engine installation and lifecycle
platform/privilege/                   Root / Shizuku / ADB
web/contract/ · web/host/              contracts and system/gecko implementations
feature/                              domain features; registration follows settings
native/                               canonical linkcompat/sandbox sources and recipes
plugins/android/bundle/                one top-level Android bundle
plugins/android/components/            14 internal components
plugins/standalone/                    multimodal, memory and preset plugins
catalogs/                             component catalog and provenance/asset locks
tooling/plugins/                      shared plugin build implementation
tools/                                gates, release CLI and compatibility entries
remote-plugin/                        reference-only copies, retained pairing service and tests
docs/architecture/ · docs/runbooks/    conventions, ADRs, status and operating guides
```

[Gradle settings](settings.gradle.kts) define the modules actually registered. Physical moves may preserve Kotlin packages, Manifest class names and bridge contracts for compatibility. The app owns assembly, Windows and Services; logging stays independent of cross-domain diagnostics assembled in the app.

Read the [conventions](docs/architecture/conventions.md), [ADRs](docs/architecture/adr/README.md) and [implementation status](docs/architecture/execution-status.md). Some external runtime assets still lack complete provenance and rebuild recipes. Configuration or partial compilation success does not establish both-flavor, device or release acceptance.

Production remote-plugin development belongs in the [independent repository](https://github.com/pht88888888/dsh-jiuling-remote-plugins). This checkout's old copies are reference-only; retain the unique pairing server and historical tests. See the [provenance audit](docs/architecture/remote-plugin-provenance.md) for differences between the published npm packages and same-version source. The [release runbook](docs/runbooks/release.md) separates local checks from explicitly requested remote actions; builds do not install or upload by default.

## Documentation

**Do not read `docs/agents/` top to bottom** — use the index keyed by
"what are you changing": [docs/agents/README.md](docs/agents/README.md)

| If you're changing | Read first |
|---|---|
| anything native or subprocess-related | [platform-android.md](docs/agents/platform-android.md) |
| `bash` tool reports "no sandbox backend" | [sandbox.md](docs/agents/sandbox.md) |
| `write` tool reports `EACCES/EINVAL: link` | [link-compat.md](docs/agents/link-compat.md) |
| any `.zip` / `.bin` asset | [asset-stamps.md](docs/agents/asset-stamps.md) |
| release builds / signing / publishing | [build-and-ship.md](docs/agents/build-and-ship.md) |
| a plugin broke the app so it won't open | [rescue-mode.md](docs/agents/rescue-mode.md) |

> The developer docs are written in Chinese; the code and this README are the
> English-language entry points.

## Known limitations

- **The sandbox does not isolate the PID namespace** — `--unshare-pid` needs
  CAP_SYS_ADMIN, which ordinary apps cannot get. File write boundaries are complete.
- **arm64-v8a only** — no arm32 or x86 builds.
- **Sideload only** — targetSdk 28, cannot ship on Google Play (see constraint ②).
- **Large APKs** — the system build is ~137 MB, the gecko build ~216 MB (engine and
  web engine ship inside the package).
- **Slow first launch** — the Termux environment and engine must be unpacked;
  roughly 10–60 seconds depending on the device.

Full list: [boundaries.md](docs/agents/boundaries.md).

## Contributing

Issues and PRs are welcome. Before you start, read [CONTRIBUTING.md](CONTRIBUTING.md) —
this project has two **"wrong but compiles, then explodes on a real device"** hard
constraints, plus silent-failure traps such as "changing an asset requires bumping its
version stamp."

## Acknowledgements

This project stands on the shoulders of:

- [DSH / DeepSeek Harness](https://github.com/deepseek-ai) — engine and web frontend
- [Termux](https://github.com/termux) — bootstrap, node, and adb runtimes
- [Mozilla GeckoView](https://wiki.mozilla.org/Mobile/GeckoView) — bundled web engine
- [ripgrep](https://github.com/BurntSushi/ripgrep) · [sharp](https://github.com/lovell/sharp)
  · [pnpm](https://github.com/pnpm/pnpm) · [Shizuku](https://github.com/RikkaApps/Shizuku)

## License

[MIT](LICENSE)
