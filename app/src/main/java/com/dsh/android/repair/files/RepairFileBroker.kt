package com.dsh.android.repair.files

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Single-path, durable candidates. Caller authorizes every call, stops concurrent engine
 * writers and obtains native confirmation before apply/rollback. This class never executes files. */
class RepairFileBroker internal constructor(filesDir: File, suppliedRoots: Map<String, File>, private val io: RepairFileIo) {
    private val filesDir = filesDir.canonicalFile
    private val roots = linkedMapOf<String, File>().apply {
        suppliedRoots.forEach { (name, file) ->
            require(name.matches(Regex("app|device_app|external_(files|cache|media)(_[1-9][0-9]*)?"))) { "Invalid trusted root name" }
            val canonical = file.canonicalFile
            if (values.none { it == canonical }) put(name, canonical)
        }
    }
    internal constructor(filesDir: File, io: RepairFileIo) : this(filesDir, mapOf("app" to filesDir.canonicalFile.parentFile), io)
    constructor(filesDir: File) : this(filesDir, mapOf("app" to filesDir.canonicalFile.parentFile))
    constructor(filesDir: File, roots: Map<String, File>) : this(filesDir, roots,
        AndroidRepairFileIo(filesDir.canonicalFile, roots.mapValues { it.value.canonicalFile }))

    // All public entries hold LOCK; only fixed source-owned labels are exposed in diagnostics.
    private var failurePhase = "request"
    private inline fun <T> phase(name: String, action: () -> T): T { failurePhase = name; return action() }

    fun dispatch(method: String, args: JSONObject, grantId: String): JSONObject = guarded {
        if (args.toString().toByteArray(Charsets.UTF_8).size > MAX_ARGS_BYTES) reject("request_too_large", "Encoded request exceeds limit")
        validateGrant(grantId)
        when (method) {
            "list_files" -> { keys(args, emptySet(), setOf("path", "offset", "limit")); list(args) }
            "read_file" -> { keys(args, setOf("path"), setOf("offset", "length", "encoding")); read(args) }
            "stage_edit", "stage_patch", "stage_file" -> stage(phase("grant_store") { grantDirectory(grantId) }, method, args)
            "list_changes" -> {
                keys(args, emptySet())
                val rows = JSONArray()
                changeDirectories(grantDirectory(grantId)).forEach {
                    if (io.statNoFollow(File(it, JOURNAL)) != null) rows.put(summary(recover(load(it))))
                }
                JSONObject().put("changes", rows)
            }
            "apply_changes", "rollback" -> {
                keys(args, setOf("change_id"))
                val transaction = load(change(grantDirectory(grantId), string(args, "change_id")))
                if (method == "apply_changes") apply(transaction) else rollback(transaction)
            }
            else -> reject("unsupported_method", "Unknown file operation")
        }
    }

    /** Native safe-exit only: reconcile interrupted journals without granting access to old backups. */
    fun reconcileForShutdown(): JSONObject = guarded {
        val root = File(filesDir, "repair-candidates")
        var unresolved = 0
        var checked = 0
        if (io.statNoFollow(root) != null) {
            requireDirectory(root)
            val grants = io.children(root)
            if (grants.size > MAX_GRANTS) reject("candidate_limit", "Too many retained grants")
            for (name in grants) {
                if (!name.matches(Regex("[a-f0-9]{64}"))) reject("candidate_corrupt", "Unknown retained grant")
                val directory = File(root, name)
                requireDirectory(directory)
                for (candidate in changeDirectories(directory)) {
                    if (io.statNoFollow(File(candidate, JOURNAL)) != null) {
                        val transaction = recover(load(candidate))
                        checked++
                        if (transaction.journal.optString("status") in setOf("applying", "rolling_back", "conflict")) unresolved++
                    }
                }
            }
        }
        JSONObject().put("checked", checked).put("unresolved", unresolved)
    }

    /** Bounded text/base64 excerpts; large files never enter the native UI in full. */
    fun previewChange(changeId: String, grantId: String): JSONObject = guarded {
        val t = recover(load(change(grantDirectory(grantId), changeId)))
        verifyArtifacts(t)
        val result = summary(t)
        preview(t, ORIGINAL, "original", result)
        preview(t, CANDIDATE, "candidate", result)
        result.put("preview_limit_bytes", PREVIEW_BYTES)
        if (t.journal.has("patch_offset")) {
            val offset = t.journal.getLong("patch_offset")
            result.put("patch_offset", offset).put("delete_bytes", t.journal.getLong("delete_bytes"))
                .put("insert_bytes", t.journal.getLong("insert_bytes"))
            result.put("patch", JSONObject().put("offset", offset).put("delete_bytes", t.journal.getLong("delete_bytes")).put("insert_bytes", t.journal.getLong("insert_bytes")))
            val start = maxOf(0L, offset - PREVIEW_BYTES / 2)
            for (name in listOf(ORIGINAL, CANDIDATE)) {
                val prefix = if (name == ORIGINAL) "original_patch" else "candidate_patch"
                putPreview(result, prefix, chunk(File(t.directory, name), start, PREVIEW_BYTES),
                    t.journal.getLong(if (name == ORIGINAL) "original_size_bytes" else "candidate_size_bytes"), start)
                result.put(if (name == ORIGINAL) "patch_original_content" else "patch_candidate_content", result.get("${prefix}_content"))
            }
        }
        result
    }

    private fun list(args: JSONObject): JSONObject {
        val path = if (args.has("path")) string(args, "path") else ""
        val offset = number(args, "offset", 0, Int.MAX_VALUE.toLong()).toInt()
        val limit = number(args, "limit", 100, 200).toInt()
        if (limit < 1) reject("invalid_argument", "limit must be 1..200")
        val names: List<Pair<String, File>>
        if (path.isEmpty()) names = roots.map { it.key to it.value }
        else {
            val dir = resolve(path, allowRoot = true)
            requireDirectory(dir)
            names = io.children(dir).sorted().map { it to File(dir, it) }
        }
        val rows = JSONArray()
        var responseBytes = 8192
        for ((name, file) in names.drop(offset).take(limit)) {
            val childPath = if (path.isEmpty()) name else "$path/$name"
            val protected = protected(file)
            val stat = if (protected) null else io.statNoFollow(file)
            val row = JSONObject().put("name", name).put("path", childPath)
                .put("type", when { protected -> "protected"; stat?.directory == true -> "directory"; stat?.regular == true -> "file"; else -> "unsupported" })
                .put("size_bytes", stat?.size ?: 0L).put("mode", stat?.mode ?: 0).put("protected", protected)
                .put("permissions_policy", if (io.platformManagedMode(file)) "platform_managed" else "posix")
            val rowBytes = row.toString().toByteArray(Charsets.UTF_8).size + 1
            if (responseBytes + rowBytes > MAX_ARGS_BYTES) break
            rows.put(row); responseBytes += rowBytes
        }
        val next = minOf(names.size.toLong(), offset.toLong() + rows.length())
        return JSONObject().put("path", path).put("entries", rows).put("offset", offset)
            .put("next_offset", if (next < names.size) next else JSONObject.NULL).put("has_more", next < names.size)
            .also { if (path.isEmpty()) it.put("roots", JSONArray(roots.keys.toList())).put("boundaries",
                "Only target-app accessible regular single-link files and real directories; symlinks/hardlinks are unsupported. Rescue authorization, live lock and transaction storage are protected. Native approval required for publication.") }
    }
    private fun read(args: JSONObject): JSONObject {
        val path = string(args, "path")
        val file = resolve(path)
        requireRegular(file)
        val digest = io.digest(file)
        val explicit = args.has("offset") || args.has("length")
        if (!explicit && digest.size > MAX_FILE_BYTES) reject("file_too_large", "Use explicit byte offset and length for large files")
        val offset = number(args, "offset", 0)
        if (offset > digest.size) reject("invalid_argument", "Offset exceeds file size")
        val length = number(args, "length", MAX_FILE_BYTES.toLong(), MAX_FILE_BYTES.toLong()).toInt()
        val encoding = encoding(args)
        // Worst-case JSON UTF-8 escaping costs six bytes per raw control byte.
        val boundedLength = if (explicit && encoding == "utf8") minOf(length, (MAX_ARGS_BYTES - 8192) / 6) else length
        val bytes = chunk(file, offset, boundedLength)
        // A chunk must not be associated with a stale whole-file hash after a concurrent edit.
        val after = io.digest(file)
        if (after != digest) reject("hash_conflict", "File changed while reading")
        val result = JSONObject().put("path", path).put("sha256", digest.sha).put("size_bytes", digest.size)
            .put("offset", offset).put("length", bytes.size).put("encoding", encoding)
            .put("content", encode(bytes, encoding)).put("eof", offset + bytes.size >= digest.size)
        if (result.toString().toByteArray(Charsets.UTF_8).size > MAX_ARGS_BYTES - 1024) {
            // Preserve legacy full bytes instead of exceeding Binder JSON bounds for controls.
            result.put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(bytes))
        }
        return result
    }

    private fun stage(grant: File, method: String, args: JSONObject): JSONObject {
        failurePhase = "validate_candidate"
        val common = setOf("encoding", "mode")
        val operation = when (method) {
            "stage_edit" -> { keys(args, setOf("path", "expected_sha256", "content"), common); "edit" }
            "stage_patch" -> { keys(args, setOf("path", "expected_sha256", "offset", "delete_bytes", "content"), common); "patch" }
            else -> {
                keys(args, setOf("path", "operation"), common + setOf("expected_sha256", "content"))
                string(args, "operation").also { if (it !in setOf("create", "delete", "mkdir", "rmdir")) reject("invalid_argument", "Unknown file operation") }
            }
        }
        val path = string(args, "path")
        val file = resolve(path, missingLeaf = operation == "create" || operation == "mkdir")
        val stat = io.statNoFollow(file)
        // Existing root directories are immutable anchors, even through an overlapping alias.
        if (roots.values.any { it == file } || file == filesDir) reject("unsafe_path", "Trusted anchor cannot be changed")
        val hasOriginal = operation in setOf("edit", "patch", "delete")
        val hasCandidate = operation in setOf("edit", "patch", "create")
        if (hasOriginal) requireRegular(file)
        if (operation == "rmdir") {
            requireDirectory(file)
            if (io.children(file).isNotEmpty()) reject("directory_not_empty", "Only empty directories can be removed")
        }
        if (operation == "create" || operation == "mkdir") {
            if (stat != null) reject("already_exists", "Creation requires an absent destination")
        }
        if (!hasOriginal && args.has("expected_sha256")) reject("invalid_argument", "SHA is only valid for existing files")
        if (operation in setOf("delete", "rmdir", "mkdir") && (args.has("content") || args.has("encoding"))) reject("invalid_argument", "Operation has no content")
        if (operation in setOf("delete", "rmdir") && args.has("mode")) reject("invalid_argument", "Operation has no candidate mode")
        val bytes = if (hasCandidate) decode(if (args.has("content")) string(args, "content") else "", encoding(args)) else ByteArray(0)
        val before = if (hasOriginal) io.digest(file) else FileDigest(ABSENT, 0)
        if (hasOriginal) {
            val expected = string(args, "expected_sha256"); validHash(expected)
            if (before.sha != expected) reject("hash_conflict", "Original no longer matches expected SHA-256")
        }
        val offset = if (operation == "patch") number(args, "offset", 0) else 0
        val delete = if (operation == "patch") number(args, "delete_bytes", 0) else 0
        if (offset > before.size || delete > before.size - offset) reject("invalid_argument", "Patch range exceeds original bytes")
        val newSize = if (operation == "patch") Math.addExact(before.size - delete, bytes.size.toLong()) else if (hasCandidate) bytes.size.toLong() else 0
        val mode = if (args.has("mode")) number(args, "mode", 0, 511).toInt() else stat?.mode ?: if (operation == "mkdir") 448 else PRIVATE_MODE
        if (args.has("mode") && io.platformManagedMode(file)) reject("unsupported_mode", "External storage permissions are platform-managed; omit mode")
        if (args.has("mode") && ((operation == "mkdir" && mode and 320 != 320) || (operation != "mkdir" && mode and 256 == 0))) {
            reject("unsupported_mode", "Recoverable candidates require owner read; directories also require owner search")
        }
        if (changeDirectories(grant).size >= MAX_CHANGES) reject("candidate_limit", "Too many retained candidates for grant")
        phase("storage_preflight") { checkCapacity(grant, Math.addExact(before.size, newSize)) }
        val id = UUID.randomUUID().toString()
        val directory = File(grant, id)
        phase("candidate_directory") { io.mkdir(directory) }
        var journalPublished = false
        var primaryFailure: Exception? = null
        try {
        phase("original_backup") {
            if (hasOriginal) {
                io.write(File(directory, ORIGINAL), PRIVATE_MODE, ABSENT) { output -> io.input(file) { it.copyTo(output, BUFFER_BYTES) } }
                if (io.digest(File(directory, ORIGINAL)) != before || io.digest(file) != before) reject("hash_conflict", "Original changed during backup")
            }
        }
        phase("candidate_write") {
            if (hasCandidate) io.write(File(directory, CANDIDATE), PRIVATE_MODE, ABSENT) { output ->
                if (operation == "patch") io.input(File(directory, ORIGINAL)) { input ->
                    copyExactly(input, output, offset); skipExactly(input, delete); output.write(bytes); input.copyTo(output, BUFFER_BYTES)
                } else output.write(bytes)
            }
        }
        val after = phase("candidate_hash") { if (hasCandidate) io.digest(File(directory, CANDIDATE)) else FileDigest(ABSENT, 0) }
        if (hasOriginal && hasCandidate && before == after && stat?.mode == mode) reject("no_change", "Candidate equals original")
        val j = JSONObject().put("schema", 2).put("change_id", id).put("path", path).put("operation", operation)
            .put("original_sha256", before.sha).put("candidate_sha256", after.sha)
            .put("original_size_bytes", before.size).put("candidate_size_bytes", after.size)
            .put("original_mode", stat?.mode ?: 0).put("candidate_mode", mode)
            .put("original_identity", stat?.identity ?: "").put("candidate_identity", "")
            .put("status", "staged")
        if (operation == "patch") j.put("patch_offset", offset).put("delete_bytes", delete).put("insert_bytes", bytes.size)
        val transaction = Transaction(directory, j)
        phase("journal_publish") { save(transaction) }
        journalPublished = true
        return phase("candidate_summary") { summary(transaction) }
        } catch (error: Exception) {
            primaryFailure = error
            throw error
        } finally {
            val failedAt = failurePhase
            try {
                if (!journalPublished && io.statNoFollow(File(directory, JOURNAL)) == null) {
                    failurePhase = "candidate_cleanup"
                    // Only our freshly-created, unjournaled directory is eligible for cleanup.
                    // Never prune another grant or a published candidate automatically.
                    for (name in listOf(ORIGINAL, CANDIDATE)) {
                        val artifact = File(directory, name)
                        if (io.statNoFollow(artifact)?.regular == true) io.remove(artifact, io.digest(artifact).sha)
                    }
                    if (io.children(directory).isEmpty()) io.rmdir(directory, io.statNoFollow(directory)!!.identity)
                }
            } catch (cleanup: Exception) {
                if (primaryFailure == null) throw cleanup
                // Keep the original step/errno; a cleanup error must never replace the cause.
                primaryFailure.addSuppressed(cleanup)
            } finally {
                if (primaryFailure != null) failurePhase = failedAt
            }
        }
    }

    private fun apply(input: Transaction): JSONObject {
        val t = recover(input)
        verifyArtifacts(t)
        val status = t.journal.getString("status")
        if (status == "applied_pending_verification") {
            requireState(t, candidate = true)
            return summary(t).put("requires_verification", true)
        }
        if (status != "staged") reject("invalid_state", "Only staged candidates can be applied")
        requireState(t, candidate = false)
        transition(t, "applying")
        publish(t, forward = true)
        transition(t, "applied_pending_verification")
        return summary(t).put("requires_verification", true)
    }
    private fun rollback(input: Transaction): JSONObject {
        val t = recover(input)
        verifyArtifacts(t)
        if (t.journal.getString("status") == "rolled_back") { requireState(t, false); return summary(t) }
        if (t.journal.getString("status") != "applied_pending_verification") reject("invalid_state", "Only applied candidates can be rolled back")
        requireState(t, true)
        transition(t, "rolling_back")
        publish(t, forward = false)
        transition(t, "rolled_back")
        return summary(t)
    }
    private fun publish(t: Transaction, forward: Boolean) {
        val j = t.journal
        val file = resolve(j.getString("path"), missingLeaf = true)
        val op = j.getString("operation")
        val sourceField = if (forward) "original_sha256" else "candidate_sha256"
        val destinationField = if (forward) "candidate_sha256" else "original_sha256"
        if (op in setOf("mkdir", "rmdir")) {
            val creating = (op == "mkdir") == forward
            if (creating) {
                io.mkdir(file, j.getInt(if (forward) "candidate_mode" else "original_mode"))
                j.put(if (forward) "candidate_identity" else "original_identity", io.statNoFollow(file)!!.identity)
                save(t)
            } else io.rmdir(file, j.getString(if (forward) "original_identity" else "candidate_identity"))
        } else if (j.getString(destinationField) == ABSENT) {
            io.remove(file, j.getString(sourceField))
        } else {
            val artifact = File(t.directory, if (forward) CANDIDATE else ORIGINAL)
            checkFree(file.parentFile!!, io.statNoFollow(artifact)!!.size)
            io.write(file, j.getInt(if (forward) "candidate_mode" else "original_mode"), j.getString(sourceField)) { out ->
                io.input(artifact) { it.copyTo(out, BUFFER_BYTES) }
            }
        }
    }
    private fun requireState(t: Transaction, candidate: Boolean) {
        if (!matches(t, candidate)) reject("hash_conflict", "Target changed; nothing was overwritten")
    }
    private fun matches(t: Transaction, candidate: Boolean): Boolean {
        val j = t.journal
        val prefix = if (candidate) "candidate" else "original"
        val file = resolve(j.getString("path"), missingLeaf = true)
        val stat = io.statNoFollow(file)
        val op = j.getString("operation")
        if (op in setOf("mkdir", "rmdir")) {
            val exists = (op == "mkdir") == candidate
            if (!exists) return stat == null
            if (stat?.directory != true || io.children(file).isNotEmpty()) return false
            val identity = j.getString("${prefix}_identity")
            return identity.isNotEmpty() && identity == stat.identity && (io.platformManagedMode(file) || stat.mode == j.getInt("${prefix}_mode"))
        }
        val expected = j.getString("${prefix}_sha256")
        if (expected == ABSENT) return stat == null
        return stat?.regular == true && (io.platformManagedMode(file) || stat.mode == j.getInt("${prefix}_mode")) && io.digest(file).let {
            it.sha == expected && it.size == j.getLong("${prefix}_size_bytes") }
    }
    /** Interrupted operations are reconciled by digest, type, mode and directory identity, never by overwriting. */
    private fun recover(t: Transaction): Transaction {
        val state = t.journal.getString("status")
        if (state !in setOf("applying", "rolling_back")) return t
        verifyArtifacts(t)
        val original = matches(t, false)
        val candidate = matches(t, true)
        val next = when {
            state == "applying" && original -> "staged"
            state == "applying" && candidate -> "applied_pending_verification"
            state == "rolling_back" && candidate -> "applied_pending_verification"
            state == "rolling_back" && original -> "rolled_back"
            else -> "conflict"
        }
        transition(t, next)
        return t
    }
    private fun verifyArtifacts(t: Transaction) {
        for ((name, prefix) in listOf(ORIGINAL to "original", CANDIDATE to "candidate")) {
            val expected = t.journal.getString("${prefix}_sha256")
            if (expected == ABSENT) continue
            val digest = io.digest(File(t.directory, name))
            if (digest.sha != expected || digest.size != t.journal.getLong("${prefix}_size_bytes")) reject("candidate_corrupt", "Retained bytes failed integrity check")
        }
    }
    private fun load(directory: File): Transaction {
        val j = JSONObject(text(io.read(File(directory, JOURNAL), MAX_JOURNAL_BYTES)))
        // Read old journals without rewriting them: existing approved rollback artifacts remain valid.
        if (j.optInt("schema") == 1) {
            j.put("schema", 2).put("operation", "edit")
                .put("original_size_bytes", io.statNoFollow(File(directory, ORIGINAL))?.size ?: -1)
                .put("candidate_size_bytes", io.statNoFollow(File(directory, CANDIDATE))?.size ?: -1)
                .put("candidate_mode", j.optInt("original_mode", -1))
                .put("original_identity", "").put("candidate_identity", "")
        }
        if (j.optInt("schema") != 2 || j.optString("change_id") != directory.name) reject("candidate_corrupt", "Invalid journal schema or ID")
        resolve(string(j, "path"), missingLeaf = true)
        if (j.optString("operation") !in setOf("edit", "patch", "create", "delete", "mkdir", "rmdir") || j.optString("status") !in STATES) reject("candidate_corrupt", "Invalid transaction state")
        for (prefix in listOf("original", "candidate")) {
            val hash = string(j, "${prefix}_sha256")
            if (hash != ABSENT) validHash(hash)
            number(j, "${prefix}_size_bytes", -1)
            number(j, "${prefix}_mode", -1, 511)
            string(j, "${prefix}_identity")
        }
        return Transaction(directory, j)
    }
    private fun transition(t: Transaction, state: String) { t.journal.put("status", state); save(t) }
    private fun save(t: Transaction) = io.replace(File(t.directory, JOURNAL), t.journal.toString().toByteArray(Charsets.UTF_8), PRIVATE_MODE)
    private fun summary(t: Transaction) = JSONObject().apply {
        for (key in listOf("change_id", "path", "operation", "original_sha256", "candidate_sha256", "original_size_bytes", "candidate_size_bytes", "original_mode", "candidate_mode", "status", "patch_offset", "delete_bytes", "insert_bytes")) {
            if (t.journal.has(key)) put(key, t.journal.get(key))
        }
        put("size_bytes", t.journal.getLong("candidate_size_bytes")); put("transaction_kind", "single_file")
        put("permissions_policy", if (io.platformManagedMode(resolve(t.journal.getString("path"), missingLeaf = true))) "platform_managed" else "posix")
    }
    private fun preview(t: Transaction, name: String, prefix: String, out: JSONObject) {
        val size = t.journal.getLong("${prefix}_size_bytes")
        val bytes = if (t.journal.getString("${prefix}_sha256") == ABSENT) ByteArray(0) else chunk(File(t.directory, name), 0, PREVIEW_BYTES)
        putPreview(out, prefix, bytes, size, 0)
    }
    private fun putPreview(out: JSONObject, prefix: String, bytes: ByteArray, size: Long, offset: Long) {
        val utf8 = try { text(bytes) } catch (_: RepairFileException) { null }
        val encoding = if (utf8 != null && '\u0000' !in utf8) "utf8" else "base64"
        out.put("${prefix}_content", if (encoding == "utf8") utf8 else Base64.getEncoder().encodeToString(bytes))
            .put("${prefix}_encoding", encoding).put("${prefix}_truncated", offset > 0 || bytes.size.toLong() < size)
            .put("${prefix}_offset", offset).put("${prefix}_preview_bytes", bytes.size)
    }

    private fun resolve(path: String, missingLeaf: Boolean = false, allowRoot: Boolean = false): File {
        if (path.isEmpty() || path.length > 4096 || path.startsWith('/') || '\u0000' in path || '\\' in path) reject("path_out_of_scope", "Invalid virtual path")
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) reject("path_out_of_scope", "Invalid path component")
        val root: File
        val relative: List<String>
        if (parts.first() == "engine") { root = filesDir; relative = parts }
        else {
            root = roots[parts.first()] ?: reject("path_out_of_scope", "Unknown virtual root; use list_files")
            relative = parts.drop(1)
        }
        if (relative.isEmpty() && !allowRoot) reject("unsafe_path", "Root itself cannot be changed")
        var node = root
        requireDirectory(root)
        for ((index, part) in relative.withIndex()) {
            node = File(node, part)
            if (protected(node)) reject("protected_path", "Rescue authorization, live lock and transaction storage are not exposed")
            val stat = io.statNoFollow(node)
            if (index < relative.lastIndex) {
                if (stat == null) reject("not_found", "Ancestor is absent")
                if (!stat.directory) reject("unsafe_path", "Ancestor is not a real directory")
            } else if (stat == null && !missingLeaf) reject("not_found", "Requested entry is absent")
        }
        return node
    }
    private fun protected(file: File): Boolean {
        val path = file.absolutePath
        if (listOf("repair-candidates", "repair-control").any { name -> path == File(filesDir, name).path || path.startsWith(File(filesDir, name).path + "/") }) return true
        return roots.filterKeys { it == "app" || it == "device_app" }.values.any { root ->
            val relative = path.removePrefix(root.path + "/")
            path.startsWith(root.path + "/") && (relative in setOf("shared_prefs/repair-diagnostics-consent.xml", "shared_prefs/repair-diagnostics-consent.xml.bak", "files/.engine-repair.lock") || relative == "files/repair-candidates" || relative.startsWith("files/repair-candidates/") || relative == "files/repair-control" || relative.startsWith("files/repair-control/"))
        }
    }
    private fun grantDirectory(grant: String): File {
        validateGrant(grant); requireDirectory(filesDir)
        val root = File(filesDir, "repair-candidates"); ensureDirectory(root)
        val directory = File(root, hash(grant.toByteArray(Charsets.UTF_8)))
        if (io.statNoFollow(directory) == null && io.children(root).size >= MAX_GRANTS) reject("candidate_limit", "Too many retained grants")
        ensureDirectory(directory)
        return directory
    }
    private fun validateGrant(grant: String) { if (!grant.matches(Regex("[A-Za-z0-9_-]{16,128}"))) reject("invalid_grant", "Invalid grant identifier") }
    private fun change(grant: File, id: String): File {
        if (!id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) reject("invalid_change", "Invalid candidate identifier")
        return File(grant, id).also(::requireDirectory)
    }
    private fun changeDirectories(grant: File): List<File> {
        val names = io.children(grant)
        if (names.size > MAX_CHANGES) reject("candidate_limit", "Too many retained candidates")
        return names.map { change(grant, it) }
    }
    private fun ensureDirectory(file: File) { if (io.statNoFollow(file) == null) io.mkdir(file); requireDirectory(file) }
    private fun requireDirectory(file: File) { if (io.statNoFollow(file)?.directory != true) reject("unsafe_path", "Expected a real directory") }
    private fun requireRegular(file: File) { if (io.statNoFollow(file)?.regular != true) reject("unsafe_file", "Expected a regular file with one link") }
    private fun checkCapacity(grant: File, added: Long) {
        var retained = 0L
        val store = File(filesDir, "repair-candidates")
        for (g in io.children(store)) {
            val gd = File(store, g); requireDirectory(gd)
            for (c in io.children(gd)) {
                val cd = File(gd, c); requireDirectory(cd)
                for (name in io.children(cd)) {
                    val stat = io.statNoFollow(File(cd, name)) ?: continue
                    if (!stat.regular) reject("candidate_corrupt", "Unexpected transaction store entry")
                    retained = Math.addExact(retained, stat.size)
                }
            }
        }
        // Existing retained bytes already reduce availableBytes; do not impose a total-file ceiling.
        checkFree(grant, added)
    }
    private fun checkFree(directory: File, required: Long) {
        val free = io.availableBytes(directory)
        if (free < FREE_RESERVE_BYTES || required > free - FREE_RESERVE_BYTES) reject("insufficient_storage", "Not enough free space for atomic file transaction and reserve")
    }
    private fun chunk(file: File, offset: Long, length: Int): ByteArray = io.input(file) { input ->
        skipExactly(input, offset)
        val out = java.io.ByteArrayOutputStream(minOf(length, BUFFER_BYTES))
        val buffer = ByteArray(minOf(maxOf(length, 1), BUFFER_BYTES))
        while (out.size() < length) {
            val n = input.read(buffer, 0, minOf(buffer.size, length - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
    private fun copyExactly(input: InputStream, output: OutputStream?, length: Long) {
        var remaining = length
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) reject("hash_conflict", "File shortened during streaming")
            output?.write(buffer, 0, n); remaining -= n
        }
    }
    private fun skipExactly(input: InputStream, length: Long) = copyExactly(input, null, length)
    private fun encoding(args: JSONObject): String = if (!args.has("encoding")) "utf8" else string(args, "encoding").also {
        if (it !in setOf("utf8", "base64")) reject("invalid_argument", "encoding must be utf8 or base64") }
    private fun decode(content: String, encoding: String): ByteArray {
        val bytes = if (encoding == "base64") {
            try {
                Base64.getDecoder().decode(content).also { if (Base64.getEncoder().encodeToString(it) != content) reject("invalid_argument", "Expected canonical padded base64") }
            } catch (_: IllegalArgumentException) { reject("invalid_argument", "Invalid base64") }
        } else try {
            val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(content))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: java.nio.charset.CharacterCodingException) { reject("invalid_utf8", "Invalid Unicode text") }
        if (bytes.size > MAX_FILE_BYTES) reject("file_too_large", "Decoded content exceeds 128 KiB message limit; use byte patches")
        return bytes
    }
    private fun encode(bytes: ByteArray, encoding: String) = if (encoding == "base64") Base64.getEncoder().encodeToString(bytes) else text(bytes)
    private fun text(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { reject("invalid_utf8", "Chunk is not strict UTF-8; use base64 or align byte boundaries") }
    private fun string(j: JSONObject, key: String): String = j.opt(key) as? String ?: reject("invalid_argument", "Missing or non-string argument: $key")
    private fun number(j: JSONObject, key: String, default: Long, max: Long = Long.MAX_VALUE): Long {
        if (!j.has(key)) { if (default < 0) reject("candidate_corrupt", "Missing numeric journal field"); return default }
        val raw = j.opt(key)
        if (raw !is Int && raw !is Long) reject("invalid_argument", "Expected integer: $key")
        val value = (raw as Number).toLong()
        if (value < 0 || value > max) reject("invalid_argument", "Out of range: $key")
        return value
    }
    private fun keys(j: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = j.keys().asSequence().toSet()
        if (!actual.containsAll(required) || (actual - required - optional).isNotEmpty()) reject("invalid_argument", "Unexpected or missing arguments")
    }
    private fun validHash(value: String) { if (!value.matches(Regex("[0-9a-f]{64}"))) reject("invalid_argument", "Expected lowercase SHA-256") }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun guarded(action: () -> JSONObject): JSONObject = synchronized(LOCK) {
        failurePhase = "request"
        try { action().put("ok", true) }
        catch (e: RepairFileException) { JSONObject().put("ok", false).put("error", e.code).put("message", e.message)
            .put("diagnostic", diagnostic()) }
        catch (e: RepairIoException) { ioFailure().put("diagnostic", diagnostic()
            .put("io_operation", e.operation.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "filesystem")
            .put("errno", e.errno.takeIf { it in 1..4095 } ?: 0)) }
        catch (_: Exception) { ioFailure().put("diagnostic", diagnostic()) }
    }
    private fun diagnostic() = JSONObject().put("implementation", "app_files_041").put("phase", failurePhase)
    private fun ioFailure() = JSONObject().put("ok", false).put("error", "io_error").put("message",
        "Target file operation failed after authorization validation; this is not a disconnection result. Inspect diagnostic step and transaction state before retrying; publication may have occurred.")
    private data class Transaction(val directory: File, val journal: JSONObject)
    companion object {
        /** Per-message/chunk bound, NOT a file-size whitelist. */
        const val MAX_FILE_BYTES = 128 * 1024
        private const val MAX_ARGS_BYTES = 768 * 1024
        private const val PREVIEW_BYTES = 16 * 1024
        private const val BUFFER_BYTES = 32 * 1024
        private const val MAX_JOURNAL_BYTES = 64 * 1024
        private const val MAX_CHANGES = 16
        private const val MAX_GRANTS = 32
        private const val FREE_RESERVE_BYTES = 16L * 1024 * 1024
        private const val PRIVATE_MODE = 384
        private const val JOURNAL = "journal.json"
        private const val ORIGINAL = "original.bin"
        private const val CANDIDATE = "candidate.bin"
        private val STATES = setOf("staged", "applying", "applied_pending_verification", "rolling_back", "rolled_back", "conflict")
        private val LOCK = Any()
    }
}
