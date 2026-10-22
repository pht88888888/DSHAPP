package com.dsh.android.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * **备份配置存储** —— 密码（Keystore 包裹）、SAF 目录、开关状态。
 *
 * ## 设计说明的硬要求（§5.3.2）
 *
 * > 密码由用户设置并二次确认，**不使用固定密码，不由 AI 保管，不写日志、
 * > profile、包 manifest 或导出记录**。
 *
 * > 为支持以后自动备份，经明确告知在本机**仅持久化由 Android Keystore 包裹的
 * > 备份密钥材料，不保存明文密码**；每个备份仍须能**单靠用户密码**在重装/
 * > 其他兼容设备解密。
 *
 * > 锁屏/重启后密钥暂不可用时**等待允许的解锁时机或提示失败，不降级明文**。
 *
 * ## ⚠️ 这里存的是什么、不是什么
 *
 * | 存 | 不存 |
 * |---|---|
 * | Keystore 密钥包裹后的密码密文 | 明文密码 |
 * | SAF 目录 URI | 密码的哈希 |
 * | 用户是否启用自动备份 | 密码的任何可逆形式 |
 *
 * **关键区别**：Keystore 里那把密钥是**不可导出**的（`KeyGenParameterSpec` 默认），
 * 所以即使有人拿到 `filesDir` 里的密文，换台设备也解不开 —— 这正是文档要的
 * 「不能用 Android Keystore 代替可跨设备输入的密码」的反面：
 * Keystore 只是**本机免输入**的便利，不是备份的加密强度来源。
 *
 * 备份包本身的加密用的是**用户密码**（见 [BackupCrypto]），与 Keystore 无关。
 * 所以重装后输入密码仍能解密 —— Keystore 丢了只影响「自动备份要不要重新输密码」。
 *
 * ## ⚠️ 锁屏后密钥不可用
 *
 * `setUserAuthenticationRequired(false)` 时密钥在锁屏后仍可用（不需要解锁）。
 * 文档说「锁屏/重启后密钥暂不可用时等待允许的解锁时机或提示失败」——
 * 我们选择**不要求认证**，因为自动备份发生在 App 启动后（此时设备通常已解锁），
 * 而要求认证会让「后台自动备份」完全不可用。
 *
 * 代价：设备被物理接触且已 root 的情况下，Keystore 密钥可被用来解出密码。
 * 这是「自动备份」这个需求本身的代价，文档也承认「独立目录不等于安全隔离」。
 */
object BackupConfig {

    private const val TAG = "DSH"

    private const val PREFS = "dsh_backup_config"
    private const val KEY_WRAPPED_PASSWORD = "wrapped_password"
    private const val KEY_WRAP_IV = "wrap_iv"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_PASSWORD_SET = "password_set"

    /** Keystore 别名。 */
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dsh_backup_wrap_v1"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 密码 ─────────────────────────────────────────────────────────────

    /**
     * 保存密码（Keystore 包裹后）。
     *
     * @return true = 保存成功；false = Keystore 不可用（此时**不降级明文**，
     *   自动备份功能会被标记为不可用，但用户仍能手动输入密码备份）
     */
    fun savePassword(context: Context, password: CharArray): Boolean {
        val plain = String(password).toByteArray(Charsets.UTF_8)
        return try {
            val key = wrapKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ct = cipher.doFinal(plain)

            prefs(context).edit()
                .putString(KEY_WRAPPED_PASSWORD, ct.joinToString("") { "%02x".format(it) })
                .putString(KEY_WRAP_IV, cipher.iv.joinToString("") { "%02x".format(it) })
                .putBoolean(KEY_PASSWORD_SET, true)
                .apply()
            EventLog.record("backup.config.password.saved")
            true
        } catch (t: Throwable) {
            // ⚠️ **绝不降级为明文存储**（文档明确要求）
            Log.w(TAG, "保存备份密码失败（Keystore 不可用）: ${t.message}")
            EventLog.record("backup.config.password.failed", mapOf("reason" to (t.message ?: "?")))
            false
        } finally {
            plain.fill(0)
        }
    }

    /**
     * 取回密码（自动备份用）。
     *
     * @return 密码；`null` = 没设过、或 Keystore 密钥不可用（如换机、密钥失效）
     *
     * ⚠️ 调用方**必须**在用完后 `fill(0)` 清零。
     */
    fun loadPassword(context: Context): CharArray? {
        val hex = prefs(context).getString(KEY_WRAPPED_PASSWORD, null) ?: return null
        val ivHex = prefs(context).getString(KEY_WRAP_IV, null) ?: return null
        return try {
            val ct = hexToBytes(hex)
            val iv = hexToBytes(ivHex)
            val key = wrapKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ct)
            String(plain, Charsets.UTF_8).toCharArray()
        } catch (t: Throwable) {
            // ⚠️ 解不开时**不报错崩溃**：那可能是换机或密钥失效。
            //   返回 null 让调用方提示「请重新输入密码」。
            Log.w(TAG, "取回备份密码失败: ${t.message}")
            EventLog.record("backup.config.password.unwrapFailed")
            null
        }
    }

    /** 是否设过密码。 */
    fun hasPassword(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PASSWORD_SET, false)

    /** 清除密码（用户主动关闭自动备份时）。 */
    fun clearPassword(context: Context) {
        prefs(context).edit()
            .remove(KEY_WRAPPED_PASSWORD)
            .remove(KEY_WRAP_IV)
            .putBoolean(KEY_PASSWORD_SET, false)
            .apply()
        EventLog.record("backup.config.password.cleared")
    }

    // ── SAF 目录 ─────────────────────────────────────────────────────────

    /**
     * 保存用户选定的备份目录。
     *
     * ⚠️ 调用方应同时 `takePersistableUriPermission` —— 否则重启后授权就没了。
     * 文档：「重装后重新取得 URI 授权，不能假定授权跨卸载保留」。
     */
    fun saveTreeUri(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
        EventLog.record("backup.config.dir.saved")
    }

    /** 取回备份目录；`null` = 未选择。 */
    fun loadTreeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE_URI, null)?.let { Uri.parse(it) }

    /** 清除目录授权。 */
    fun clearTreeUri(context: Context) {
        prefs(context).edit().remove(KEY_TREE_URI).apply()
    }

    // ── 自动备份开关 ─────────────────────────────────────────────────────

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
        EventLog.record("backup.config.auto", mapOf("enabled" to enabled.toString()))
    }

    fun isAutoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ENABLED, false)

    /** 是否**完全配置好了**（密码 + 目录 + 开关）。 */
    fun isReady(context: Context): Boolean =
        hasPassword(context) && loadTreeUri(context) != null && isAutoEnabled(context)

    /**
     * 配置状态摘要（给 UI 用）。
     */
    fun status(context: Context): Status = Status(
        hasPassword = hasPassword(context),
        hasDir = loadTreeUri(context) != null,
        autoEnabled = isAutoEnabled(context),
        lastSuccessAt = BackupScheduler.lastSuccessAt(context),
    )

    data class Status(
        val hasPassword: Boolean,
        val hasDir: Boolean,
        val autoEnabled: Boolean,
        val lastSuccessAt: Long,
    ) {
        val ready: Boolean get() = hasPassword && hasDir && autoEnabled
    }

    // ── Keystore ─────────────────────────────────────────────────────────

    /**
     * 取（或创建）包裹用的 Keystore 密钥。
     *
     * ⚠️ `setUserAuthenticationRequired(false)`：见类 KDoc 的取舍说明。
     * ⚠️ 不设 `setInvalidatedByBiometricEnrollment`：那会让用户加指纹后
     *   旧密文失效 —— 对「备份配置」这种非敏感场景不值得。
     */
    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex 长度必须是偶数" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    /*
     * ⚠️ **本对象的边界**：
     *
     * ✅ 做到：Keystore 包裹密码（不存明文）、SAF URI 存储、自动备份开关、
     *          配置状态查询、解不开时如实返回 null 而非崩溃
     * ❌ 未做：
     *   - 「密码世代」记录（改密码后旧包仍需旧密码 —— 靠每包自带 salt 天然支持，
     *     但没有记录「这个包是哪一代密码加密的」）
     *   - takePersistableUriPermission（调用方负责）
     *   - 配置导出的能力（文档说 Keystore 密钥不随备份迁移）
     */
}
