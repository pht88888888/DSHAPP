# Target-side app-wide repair transactions

The synchronous `RepairFileBroker(filesDir, roots)` accepts trusted Context directory roots,
not model-supplied absolute paths. `RepairFileBroker(filesDir)` and the old host-test adapter
constructor remain compatible. Callers authorize every request, stop live engine writers,
and obtain explicit one-use native confirmation before apply/rollback. No shell, JS execution,
upload, or assertion of successful application repair occurs here.

## Contract

- `list_files {path?, offset?:0, limit?:100}`: empty path inventories roots; otherwise lists a
  real directory. Numeric limit 1–200, numeric nonnegative offset, `entries`, `next_offset`
  (integer/null), `has_more`. Entries report name/path/type/size_bytes/mode/protected.
- `read_file {path, offset?, length?, encoding?:"utf8"}`: whole `sha256`, `size_bytes`, plus
  `offset`, actual `length`, `content`, `encoding`, `eof`. Path-only legacy reads remain full
  only up to 128 KiB. Explicit offset/length reads bounded chunks of arbitrarily sized files.
  UTF-8 is strict, so arbitrary byte boundaries/binary data should use canonical padded base64.
  Escaped-control chunks may return fewer bytes to stay below the JSON envelope; legacy full
  reads may return encoding=base64 when UTF-8 JSON escaping would exceed it. Honor returned encoding.
- `stage_edit {path, expected_sha256, content, encoding?, mode?}`: bounded full replacement.
- `stage_patch {path, expected_sha256, offset, delete_bytes, content, encoding?, mode?}`:
  SHA-bound byte splice; original/remaining bytes are streamed locally, not transferred.
- `stage_file {path, operation, ...}`: `create` optional content (empty default), encoding/mode;
  `delete` requires expected_sha256; `mkdir` optional mode; `rmdir` only empty directories.
  Delete/rmdir have no content/mode; mkdir/rmdir/create have no expected_sha256.
- `list_changes {}`, `apply_changes {change_id}`, `rollback {change_id}` retain existing names.

All results have `ok`; failures contain a bounded error/message. Summaries contain operation,
path/ID/state, both digests/sizes/modes and `transaction_kind: single_file`. File absence is
recorded as `"absent"`, not the empty-file digest. Publication is one path per transaction.
Optional modes are integer permission bits 0–511. Explicit regular-file modes require owner
read; directory modes require owner read/search, otherwise `unsupported_mode`. External modes
are platform-managed: explicit mode is rejected, chmod is skipped, synthetic POSIX modes are
not used as durable verification state. Existing private modes are preserved.

Virtual roots are `app`, `device_app`, and `external_files`, `external_cache`, `external_media`
(with optional `_2`, `_3`, etc. for volumes), supplied/deduplicated by the native caller. Legacy
`engine/...` aliases Context.filesDir/engine/... (normally app/files/engine/...). All other
accessible regular files are supported regardless of package, extension, binary content or
size. There is no total-file-size ceiling. Absolute paths, NUL/backslash, empty components,
`.`/`..` and root mutation are rejected; Unicode and spaces are valid. Symlinks, hardlinks,
special files and symlinked ancestors are not followed, and listings mark unsupported types.

The only generic-access exclusions are rescue's own transaction subtree plus, under app and
device_app, exact `shared_prefs/repair-diagnostics-consent.xml` and `.xml.bak`,
`files/.engine-repair.lock`, and `files/repair-candidates/**`. The live lock cannot even be
read because POSIX closing another descriptor could release process-wide locks. Other user
configuration, plugins, profiles and engine packages are not excluded.

## Bounded memory and storage

128 KiB is the decoded message/chunk bound, not a file-size whitelist. Encoded requests are
at most 768 KiB. Hashes, backup, copy, splice and publication stream through 32 KiB buffers.
Native-only `previewChange` returns at most 16 KiB per side (UTF-8/base64 + truncation metadata),
plus separately bounded patch-local excerpts and splice offset/delete/insert metadata. The
native caller must not turn truncated/binary previews into an unbounded file read.

Disk preflight includes original+candidate bytes, actual available space, and 16 MiB reserve;
publication checks target-filesystem temporary space too. There is no arbitrary byte quota.
There are at most 16 retained candidate directories per grant and 32 namespaces. Published
backups are never automatically deleted. Limits can eventually require an independently
authorized lifecycle/recovery UI (not implemented here). Failed/no-op staging cleans only
its own newly-created unjournaled artifacts; crash orphans remain failclosed and count.

## IO, journal and recovery

Trusted canonical Context anchors are opened directly; no public ancestor O_RDONLY dependency.
Each descendant uses pinned procfd parents, NOFOLLOW/NONBLOCK and fstat; no nonpublic SDK
O_DIRECTORY constant. Existing-file publication prepares/fsyncs an exclusive sibling temp,
rechecks SHA, atomically renames and fsyncs parent. Creation tries no-clobber hardlink publication.
On any trusted anchor refusing links (EACCES/EPERM/EOPNOTSUPP/ENOSYS only), O_EXCL reserves
an absent name and dev/inode/empty-size/single-link are checked before renaming the complete
candidate over that reservation. EEXIST and unexpected I/O errors never enable fallback;
a concurrent creator makes O_EXCL fail without replacing existing data. The temporary link
cleanup is outside the link-error handler, so a cleanup failure cannot start a second publication. If a filesystem refuses directory fsync, publication reports `unsupported_durability`
and callers must inspect transaction recovery state; durability is never silently weakened.
Private-policy and external filesystem behavior still require actual device validation. A crash in that reservation window is a recoverable *conflict*, not a
claim creation completed; no pre-existing file is deliberately overwritten. Same-UID hostile
writers can race cooperative checks; native engine quiescence remains mandatory.

Free-space queries use public `Os.fstatvfs` on the pinned directory descriptor, without reopening
a procfd pathname for statvfs. Adapter entry points translate raw `ErrnoException` into a fixed
sanitized `RepairIoException` carrying only a constant operation label and integer errno. Nested
safe exceptions keep the originating label; OS messages, paths and function names are not copied.
Link fallback handles the selected raw errnos before that boundary translation.

The user's Android10 0.4.0 report establishes reads/listings succeeded while all staging failed
without visible candidates; it does **not** establish the actual errno or device root cause.
Private `link` denial is a reproduced host-injected regression case, not a definitive diagnosis.

Journal transitions precede publication; recovery checks digests, type, size, private modes and
directory identity without rewriting target content. Unknown current state yields conflict.
Create/delete/mkdir/rmdir rollback restores absence/content/directory correctly while refusing
unknown user changes. A mkdir crash before identity journal durability also fails closed. A failure after an O_EXCL
fallback reservation but before its identity is journaled may leave the empty path behind; that
state fails closed as conflict rather than clobbering or claiming a successful create.
Schema1 journals are normalized in memory for same-grant legacy rollback, with original
artifacts/digests still validated. Native approval remains independent of this migration.

Namespaces are SHA256(grantId)/UUID under filesDir/repair-candidates. A new/expired/revoked grant
cannot access or rewrite an old grant's journal. Same live grant must span staging through
approval/apply/verification/rollback; there is no cross-grant recovery bypass. Applied state
is `applied_pending_verification`, never a declaration that the target launches correctly.

## Evidence

The broker host harness executes the production state machine and compiles the actual adapter
against the public API35 JAR. The OS fixture suite executes the actual Android adapter+broker,
with real host files/descriptors and narrow Android API simulation. It exercises denied public
ancestor access, NOFOLLOW, FD cleanup/faults, real 227100-byte dsh-app-boot entry first-line
removal, hashes/chunks, binary/external no-hardlink creation, config outside engine, Unicode,
create/delete/directory rollback and bounded native preview. Supply REPAIR_BOOT_FIXTURE for
the actual rc.5 entry. These are host proofs, NOT Android/SELinux/device or complete end-to-end
native confirmation/AI/target-launch acceptance. No Gradle/device/upload is run by this scope.
