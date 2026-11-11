# Official market contract fixtures

These are **unmodified upstream files**, not hand-written schemas or Gateway mocks.
`market/manifest.json` records the original engine-relative path, package versions,
SHA-256, byte length and content-addressed fixture filename for both shipped and
installed engine inputs. Original package metadata and upstream MIT license texts
are included; identical files share storage. Repository:
https://github.com/deepseek-ai/deepseek-harness

The fixture executes the actual PluginManager generated descriptors, Gateway
`invoke`, RemoteInfo schema assembly and RemoteError implementation. Only the RPC
transport/owner and unused Cordis base class are test harness adapters. Existing
market UI tests keep executing the real client. The local exact zod dev dependency
and package-lock supply schema validation without a global DSH installation.

## L0 — no engine archive or global installation

```sh
npm ci --ignore-scripts --no-audit --no-fund --prefix market-plugin/dsh-android-market
npm test --prefix market-plugin/dsh-android-market
```

Every pinned fixture hash is checked before use. This layer needs only tracked
sources and npm's locked zod dependency; it does not extract an engine archive,
resolve a global DSH package, or silently skip official validation.

## L1 — explicit real inputs, drift is a failure

```sh
DSH_ENGINE_PACKAGE=/absolute/installed/@deepseek-ai/dsh/package.json \
DSH_ENGINE_ARCHIVE=/absolute/engine.bin \
npm run test:engine --prefix market-plugin/dsh-android-market
```

Both variables are mandatory absolute paths. The installed input must have its
normal node_modules dependency tree. Python 3 is required to read the tar archive.
Both inputs must contain the contract files, upstream package metadata and licenses
listed in the manifest. Missing input, missing archive entry, source/version/license
drift or failed behavior is a hard failure. No fallback to fixtures is allowed.
The suite tests actual shipped and installed Gateway contracts and reruns market
behavior against the shipped implementation, using the locked zod test runtime.
This layer is required when changing engine/compatibility/install contracts and
before release; it is deliberately separate from the asset-free default CI job.

## Explicit engine update / fixture refresh

```sh
DSH_ENGINE_PACKAGE=/absolute/candidate/package.json \
DSH_ENGINE_ARCHIVE=/absolute/candidate-engine.bin \
node contracts/engine/market-fixtures.mjs --write
```

Refresh only after reviewing the new official version and contract changes. The
manifest diff exposes each source/package/license hash; tests never refresh files.
Review the shipped zod version and deliberately update the exact test dependency
and lock if needed, then run L0 and L1. The initial source baseline is DSH
0.2.2-rc.1; shipped zod is 4.4.3, while the independently installed input metadata
records 4.6.7. Test runtime is explicitly pinned to shipped zod 4.4.3.
