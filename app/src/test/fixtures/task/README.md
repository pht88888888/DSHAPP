# Production repair task coordinator host checks

The driver compiles the current, unmodified production `RepairTaskCoordinator.kt`, `RepairChangeApproval.kt`, and `EngineRepairLock.kt` with Kotlin 2.0.21 / JDK 17. Each scenario runs in a fresh JVM. Reflection seeds otherwise unreachable intermediate states and simulates loss of volatile coordinator fields; public production methods implement all admission, report, permit, transition, ticket binding, and persistence decisions under test.

Narrow fixtures replace only Android Context/Bundle/time/Os/AtomicFile, authorization storage, task notification service, process scanning, runtime launch/report transport, diagnostics, and file broker ports. The broker returns fixed bounded metadata; latches simulate an admitted slow stage. Real `/proc` entries are deliberately unrelated to the fixture UID. EngineRepairLock still uses real host file locks. The real JSON implementation is an explicit `ENGINE_TEST_JSON_JAR` input; no Android platform JAR or Gradle build is required.

These tests **do not establish** actual Android Binder caller/PID identity, device process stopping, Android AtomicFile/fsync crash guarantees, Service/Activity lifecycle, theme rendering, or broker/adapter filesystem safety. Those require their respective production tests, independent source review, and eventual device acceptance. In particular an allow-all process-port fixture is not evidence that actual stopping succeeded.

Run:

```sh
JAVA_HOME=/path/to/jdk17 ENGINE_TEST_JSON_JAR=/absolute/path/to/json.jar node --test app/src/test/RepairTaskCoordinator.test.mjs
```

Missing tool/input is a failure, never a skip. Current driver contains 15 named production-behavior scenarios and reports zero skips on success. Fixture sources are exclusive to this test and must not enter production source sets.
