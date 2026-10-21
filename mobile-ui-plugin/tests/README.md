# Layout contribution tests

Requires Node.js >= 22.19.0. Run from `mobile-ui-plugin`:

```sh
node --test tests/*.test.mjs
```

No test package or DOM dependencies are required. The client suites read
`../dsh-android-mobile/lib/client.js` relative to each test's `import.meta.url`,
extract real production functions/declarations and execute them in isolated Node
VMs with minimal fake DOM, timer and official-service interfaces. They do not
load a live plugin or modify any host state. Missing production source/functions
are failures; there is no helper-source fallback.

Coverage:

- `layout.test.mjs`: alias ownership and cleanup, retry epochs, reversible mobile
  DOM scope, readiness/manual policy, exact legacy width boundary, drawer mask
  stacking-parent attachment without moving host nodes.
- `control.test.mjs`: additive official slots, form readiness, accepted/rejected
  writes, transport recovery, disposal, accessible collapsed toggle.
- `auto-layout.test.mjs`: strict 4:3 / 3:4 hysteresis, mobile first start, persisted
  history, invalid sizes, rapid-resize echoes, bounded write retries, disposal and
  shell-resize (not visualViewport) wiring.
- `keyboard.test.mjs`: early password type interception, mode/disposal guards,
  compare-and-restore ownership, missing configuration service fallback and
  exclusion of unrelated local APK bridges (no input value access).
- `schema.test.mjs`: package dependency metadata, importing the actual settings
  owner, real schema validation and official settings effect ownership.
- `integration.test.mjs`: real engine composition ordering/regression mechanism.

## Real schema dependency

The schema test resolves `@deepseek-ai/schemastery` from the production package
`../dsh-android-layout-preferences/index.js` using Node's normal dependency graph.
If that direct dependency has not been installed, the two tests that import it
explicitly **skip**, while package metadata is still tested. Install production
package dependencies through the project's normal package manager, then rerun.
The suite never supplies a mock schema or resolves one from a hardcoded host.
Missing owner source, broken transitive imports or an invalid schema **fail**;
these are not dependency skips.

## Opt-in engine integration

By default both engine composition tests explicitly **skip**. To enable them,
set `DSH_ENGINE_ROOT` to an installed DSH engine root from which Node can resolve
`@deepseek-ai/dsh-app-boot` and `yaml`, for example:

```sh
DSH_ENGINE_ROOT="$HOME/dsh-engine" node --test tests/integration.test.mjs
```

The directory is provided by the caller; the suite never auto-discovers the app,
a workspace or the running engine. A nonexistent directory fails; unavailable
direct integration dependencies explicitly skip; broken modules or incompatible
engine APIs fail. It reads the actual contributed `cordis.patch.yml` and performs
in-memory composition only, without starting or modifying the engine.

A green Node run with skips does **not** establish schema or engine integration
success. Report passed/failed/skipped counts separately. These fake-DOM unit tests
also do not replace a real WebView/device check for CSS layering, official slot
rendering, reactive rerenders, pointer/window events or resize behavior.
