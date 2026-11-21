# Original DSH Android with additive app-wide rescue

The assistant APK uses the original Android host, GUI, settings, plugins, tools and shipped presets in its own package and private home. Its launch extension copies these assets and applies `repair.patch.yml` after the ordinary Android overlays. No original composition row is removed, disabled or replaced. `repair` adds instructions and **13 native-backed tools** to the complete shipped standard preset. Existing standard/PTC/minimal/Cordis presets and their settings remain unchanged.

The additive header status/detail and official `settings.section` offer user-click-only zero-argument `androidRescue/authorize` and `androidRescue/disconnect` Remotes. They atomically write fixed native control requests. Native opens target choice and consent only while the Activity is foreground. The model has no authorization or UI-validation tool. A strict four-color theme snapshot is visual only. Native polls read-only task state every 1.5 seconds without renewing consent. Terminal attempt evidence uses official identified user messages via agent.inject while running or agent.followup when idle, deduplicated by task/attempt/evidence sequence, durable session and inbox; no task evidence mutates systemPrompt. “Requested” is not “authorized”; switching target requires a new rescue conversation.

Environment supplied by the APK: `DSH_REPAIR_ASSETS`, `DSH_REPAIR_ENGINE_PACKAGE` (absolute real rc.5 engine manifest), `DSH_REPAIR_TOOLS_MODULE`, `DSH_REPAIR_MAILBOX`, `DSH_REPAIR_CONTROL_FILE`. These are host values, never model arguments. Historical standalone profile/guard fixtures are not loaded by this overlay and do not describe product restrictions.

## Native mailbox contract

Native owns private `request`/`response` directories and `binding.json` containing `{binding:<UUID>}`. A conversation captures the connection at Agent creation (or retries a missing authorization); it never adopts another binding, including during internal activity. After a target/grant epoch change, create a new conversation. Requests are exactly `{id,method,args,binding}` with host-minted binding and ID. Native independently revalidates signature, grant, expiry, roots, paths, candidates and confirmation.

Responses are `{id,ok:true,result:{...}}` or `{id,ok:false,error:"stable_code"}`. JS validates IDs and regular no-follow response files, bounds each JSON message to **768 KiB**, and bounds each decoded read/insertion/full-small-replacement payload to **128 KiB**. There is **no 128 KiB total-file limit** for chunk reads or byte patches. At most eight calls are in flight; monotonic mailbox timeout is 180 seconds, with cooperative cancellation. A timed-out write must be inspected with `list_changes`, not blindly repeated. Cleanup touches only owned minted mailbox files.

### Discovery and paths

`list_files({})` or `{path:""}` returns the native roots inventory and boundaries. Only roots actually advertised by native are accessible:

- `app/...` maps to target `Context.dataDir`, including files, configurations, databases, preferences, dependencies, binaries, caches and other own-app data.
- `device_app/...` maps to available device-protected data.
- `external_files/...`, `external_cache/...`, `external_media/...` and numbered volume variants such as `external_files_2/...` are optional native-provided own-app roots.
- Legacy `engine/...` aliases `app/files/engine/...`.

No extension, JavaScript, package or dsh/lib whitelist exists. Paths accept safe Unicode and spaces up to 4096 characters (matching native); absolute paths, NUL, backslashes, empty segments and `.`/`..` segments are rejected. Root names alone are valid only for directory discovery. `list_files({path:"app/files",offset:0,limit:100})` returns metadata entries (`name,path,type,size_bytes,mode`, optionally `protected`) with `offset,next_offset,has_more`; `next_offset` is null at the end. No recursive content collection is automatic. Native protects its live authorization/backup, repair lock and candidate transaction internals; unsupported/symlink/hardlink entries must not be followed to bypass boundaries.

### Read and stage

13 tools: `status`, `evidence`, `list_files`, `read_file`, `stage_edit`, `stage_patch`, `stage_file`, `list_changes`, `apply_changes`, `rollback`, `launch_target`, `validate_target`, `verify_target`.

- `read_file({path,offset?,length?,encoding?})` returns `path,sha256,size_bytes,offset,length,encoding,content,eof`. SHA-256 and size describe the **entire original**, length the decoded returned chunk. Default encoding is `utf8`, length 131072. Path-only legacy reads retain small-file whole-read behavior; for larger files explicitly pass `offset:0` or `length`. Arbitrary byte boundaries/binary require canonical padded `base64`; strict UTF-8 rejects a chunk cutting a multibyte codepoint.
- `stage_edit({path,expected_sha256,content,encoding?,mode?})` stages a complete small replacement. Hash binds the entire original, not a chunk.
- `stage_patch({path,expected_sha256,offset,delete_bytes,content,encoding?,mode?})` stages an exact byte splice into an arbitrarily large file, streaming unchanged content natively. Byte offsets are not character offsets. For a 227100-byte dependency, read only fault-relevant chunks and transmit only the localized patch, not a token-heavy whole-file rewrite.
- `stage_file({path,operation,expected_sha256?,content?,encoding?,mode?})` stages `create`, `delete`, `mkdir` or empty-only `rmdir`. Create requires an existing parent and no expected hash; omitted content creates an empty file. Delete requires the full original hash and accepts no content/encoding/mode. Directory operations accept no content/encoding/hash. Optional numeric Unix mode 0..511 applies only to create/mkdir/edit/patch; no special permission bits. Explicit file modes must retain owner-read (0400), and mkdir modes owner-read plus search (0500), so verification/rollback remain possible; otherwise `unsupported_mode`. Omitted mode retains native preservation/default behavior.

Every stage operation creates a candidate, never mutates the original. Apply and rollback use the current task authorization and return queued operation IDs; the one-time grant covers publication, so no per-file approval dialog interrupts the repair. Show concrete content/data/permission impacts, inspect `list_changes`, then run asynchronous `validate_target({level:"syntax"|"engine"})` and observe `verify_target`. On a passing engine check, tell the user the repair is done and ask them to return to the original app and tap 「重启并验证」, which ends the task and boots it normally for acceptance. `launch_target` returns user_action_required without opening an Activity. Failure may require rollback of that transaction, never indiscriminate reset.

## Privacy and actual repair boundary

App-wide access can expose credentials, sessions and personal data to the selected model. Read only the minimum fault-relevant content, prefer metadata and bounded snippets, never harvest secrets or dump all files. Explain sensitive access and model disclosure before reading sensitive material; obtain explicit consent before staging sensitive changes. Connection consent is not consent to arbitrary sensitive edits; sensitive changes retain native confirmation.

This is access to the target's own OS-permitted files, not an installed-APK rewrite mechanism. Compiled code/read-only resources inside an installed APK, protected system files and other apps' data require their proper signed update/OS mechanisms. Preserve package identity, signing and user data; do not claim private file edits can rewrite running installed APK code.

Original generic shell/files/plugin tools intentionally remain available. The broker validates its own operations, not arbitrary same-UID code/root explicitly granted by the user. Do not claim a 13-tools-only sandbox. Scope/channel failures, authorization expiry, file mutation and refusal remain distinct; unchanged `io_error` retries are not a repair.

## Verification

`repair-agent.test.mjs` uses actual rc.5 bundles, Cordis/scopes/ToolsService, original composition and preset comparisons, inherited tools, all 13 additions, scoped admission, message bounds, failures/cancellation/activity and immutable conversation binding. `repair-file-tools.test.mjs` exercises closed argument contracts, optional/numeric schemas, virtual paths, large-file chunks and binary/localized patch transport. These host tests are not Android device or complete native transaction evidence.

A full official Web host smoke also needs a genuine matching platform addon (Android extraction lacks Linux's `node-addon-require-builtin-linux-x64-gnu@0.1.9`). Use an isolated genuine package, not a fake addon. Host startup/client graph, browser visual checks and Android evidence are separate claims.
