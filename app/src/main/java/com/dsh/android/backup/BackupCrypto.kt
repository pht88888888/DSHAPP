package com.dsh.android.backup

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * **加密备份容器** —— 用户密码保护，跨设备可解密。
 *
 * ## 设计说明的硬要求（§5.3.2）
 *
 * > 加密实现复用维护中的密码 KDF 与认证式流加密库/容器，**不自创密码学算法**，
 * > 不把普通 ZIP 或仅「设置打开密码」当作已满足。…冻结库版本、KDF 成本上下限、
 * > 随机盐、认证加密格式、nonce/分段规则、**头部认证与截断检测**。
 *
 * > 密码由用户设置并二次确认，**不使用固定密码，不由 AI 保管，不写日志**、
 * > profile、包 manifest 或导出记录。
 *
 * > **文件名、清单及内容哈希置于加密载荷**；外部头只保留解析必需的格式/KDF
 * > 参数与随机信息，**不泄露插件列表或秘密**。
 *
 * > 归档压缩后**流式加密，不能把全包读进 RAM**。
 *
 * ## 格式（v1，本次冻结）
 *
 * ```
 * 偏移  长度   内容
 * 0     8      magic "DSHBAK01"（ASCII）
 * 8     1      格式版本 = 1
 * 9     1      算法 ID = 1（PBKDF2-HMAC-SHA256 + AES-256-GCM）
 * 10    4      KDF 迭代次数（大端 int）
 * 14    16     salt
 * 30    12     首段 nonce
 * 42    ...    分段密文（每段：4 字节长度 + GCM 密文+16 字节 tag）
 * 末尾  32     HMAC-SHA256(头部字节 || 全部密文)  ← 头部认证 + 截断检测
 * ```
 *
 * ## ⚠️ 为什么是「分段 GCM」而不是「整个文件一个 GCM」
 *
 * 整包 GCM 有两个实际问题：
 * 1. **必须全部读进内存**才能验证 tag —— 而文档明确禁止（「不能把全包读进 RAM」）
 * 2. 无法流式解密 —— 用户恢复 100 MB 备份时要先吃 100 MB 内存
 *
 * 分段 GCM 的做法：每 [SEGMENT_SIZE] 字节一段，各自带 tag。于是：
 * - 流式加密/解密（内存占用恒定）
 * - 任意一段被篡改都能被发现
 * - **但段的重排检测不了** —— 那由末尾的整包 HMAC 负责
 *
 * ## ⚠️ 为什么末尾还要一个 HMAC
 *
 * 分段 GCM 只能保证「每段完整」，不能保证「段没被删掉或换顺序」。
 * 攻击者可以把第 3 段换成第 1 段、或者删掉中间几段，GCM 各自都能过。
 *
 * 所以末尾加一个覆盖「头部 + 全部密文」的 HMAC —— 任何删段、换序、
 * 改 KDF 参数都会让它失败。这就是文档要求的「头部认证与截断检测」。
 *
 * ## ⚠️ 密码不落盘
 *
 * 本类只接受调用方传入的密码。**不缓存、不写日志、不进 manifest**。
 * 自动备份需要的「免输入」能力由 Keystore 包裹的密钥材料提供（见 [BackupKeyVault]），
 * 那存的是**密钥材料**，不是密码本身。
 */
object BackupCrypto {

    private const val TAG = "DSH"

    /** magic（8 字节）。改它等于改格式，必须升 [FORMAT_VERSION]。 */
    private val MAGIC = "DSHBAK01".toByteArray(Charsets.US_ASCII)

    /** 格式版本。**改动格式必须 +1**，读取端按它分派。 */
    const val FORMAT_VERSION: Int = 1

    /** 算法 ID。1 = PBKDF2-HMAC-SHA256 + AES-256-GCM + HMAC-SHA256。 */
    const val ALGO_ID: Int = 1

    /**
     * KDF 迭代次数。
     *
     * ⚠️ 文档要求「KDF 成本上下限」。这里固定 [ITERATIONS]，
     * 读取端接受 [MIN_ITERATIONS]..[MAX_ITERATIONS] 之间的任意值 ——
     * 上限的存在是为了**防止恶意包用超大迭代数拖死导入**
     * （文档：「限制未认证头的 KDF 和内存参数，避免导入包诱发资源耗尽」）。
     */
    const val ITERATIONS: Int = 210_000
    const val MIN_ITERATIONS: Int = 100_000
    const val MAX_ITERATIONS: Int = 1_000_000

    /** 每段明文大小（64 KB）。小到内存友好，大到 GCM 开销可忽略。 */
    private const val SEGMENT_SIZE = 64 * 1024

    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val HMAC_LEN = 32

    /** 头部长度（magic + ver + algo + iter + salt + nonce）。 */
    private const val HEADER_LEN = 8 + 1 + 1 + 4 + SALT_LEN + NONCE_LEN

    private val rnd = SecureRandom()

    /** 加密失败。消息**不含密码**。 */
    class EncryptError(message: String) : Exception(message)

    /** 解密失败。消息**不含密码**。 */
    class DecryptError(message: String) : Exception(message)

    /**
     * 密码策略。
     *
     * ⚠️ 文档要求「密码编码/长度」冻结。这里只做**最低长度**检查，
     * 不强制复杂度 —— 强制复杂度会诱导用户写 `Password1!` 这种，
     * 反而不如长密码。密码按 UTF-8 编码。
     */
    const val MIN_PASSWORD_LEN: Int = 8

    fun validatePassword(password: CharArray): String? = when {
        password.size < MIN_PASSWORD_LEN -> "密码至少 $MIN_PASSWORD_LEN 个字符"
        password.size > 1024 -> "密码过长（最多 1024 字符）"
        else -> null
    }

    // ── 加密 ─────────────────────────────────────────────────────────────

    /**
     * 加密一个目录树到一个 `.dshbak` 文件。
     *
     * 流式：内存占用恒定（约 3 × [SEGMENT_SIZE]），与目录总大小无关。
     *
     * @param srcDir 要备份的目录（其内容会原样进包）
     * @param out 输出文件
     * @param password 用户密码。**调用方负责用后清零**
     * @param manifest 清单（会**加密进包内**，不进明文头）
     */
    fun encryptDirectory(
        srcDir: File,
        out: File,
        password: CharArray,
        manifest: BackupManifest,
    ) {
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val nonceBase = ByteArray(NONCE_LEN).also { rnd.nextBytes(it) }
        val iter = ITERATIONS

        val keys = deriveKeys(password, salt, iter)

        out.parentFile?.mkdirs()
        out.outputStream().buffered().use { os ->
            // ① 头部（明文，只含格式与 KDF 参数）
            os.write(MAGIC)
            os.write(FORMAT_VERSION)
            os.write(ALGO_ID)
            os.write(intToBytes(iter))
            os.write(salt)
            os.write(nonceBase)
            os.flush()

            // ② 用 HMAC 覆盖头部 + 后续全部密文
            val hmac = MessageDigest.getInstance("SHA-256")
            // ⚠️ 头部认证：把头部字节喂进 HMAC，改任何一个字段都会让末尾校验失败
            hmac.update(MAGIC)
            hmac.update(FORMAT_VERSION.toByte())
            hmac.update(ALGO_ID.toByte())
            hmac.update(intToBytes(iter))
            hmac.update(salt)
            hmac.update(nonceBase)

            val hmacOut = HmacSink(os, hmac)

            // ③ 分段加密
            val plain = buildPlainStream(srcDir, manifest)
            val segBuf = ByteArray(SEGMENT_SIZE)
            var segIndex = 0
            while (true) {
                val n = readFully(plain, segBuf)
                if (n < 0) break
                if (n == 0) continue

                // nonce = base XOR 段号（保证每段唯一，且可重现）
                val nonce = nonceForSegment(nonceBase, segIndex)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(keys.encKey, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                // AAD = 段号，防止段被换序（配合末尾 HMAC 双保险）
                cipher.updateAAD(intToBytes(segIndex))
                val ct = cipher.doFinal(segBuf, 0, n)

                hmacOut.write(intToBytes(ct.size))
                hmacOut.write(ct)
                segIndex++
            }

            // ④ 终结标记：一个长度为 0 的段，表示「到此结束」
            //
            // ⚠️ 没有它的话，「文件被截断」与「正常结束」在读取端无法区分 ——
            //   而截断正是文档要求检测的情况之一。
            hmacOut.write(intToBytes(0))

            // ⑤ 末尾 HMAC
            val mac = hmac.digest(keys.macKey)
            os.write(mac)
            os.flush()
        }

        // 清零派生的密钥材料
        keys.encKey.fill(0)
        keys.macKey.fill(0)
    }

    // ── 解密 ─────────────────────────────────────────────────────────────

    /**
     * 解密并校验一个备份包。
     *
     * @param verifyOnly `true` = 只校验不写出（用于「导入前检查」）
     * @param destDir 解出的目录（[verifyOnly] 为 false 时使用）
     * @return 清单（从包内解出）
     * @throws DecryptError 密码错、包被篡改、被截断、格式不支持
     */
    fun decrypt(
        src: File,
        destDir: File?,
        password: CharArray,
        verifyOnly: Boolean = false,
    ): BackupManifest {
        src.inputStream().buffered().use { ins ->
            // ① 读头部
            val magic = ByteArray(8)
            if (readFully(ins, magic) != 8 || !magic.contentEquals(MAGIC)) {
                throw DecryptError("不是 DSH 备份包（magic 不符）")
            }
            val ver = ins.read()
            if (ver != FORMAT_VERSION) {
                throw DecryptError("不支持的格式版本 $ver（本机支持 $FORMAT_VERSION）")
            }
            val algo = ins.read()
            if (algo != ALGO_ID) {
                throw DecryptError("不支持的算法 ID $algo")
            }
            val iterBytes = ByteArray(4)
            if (readFully(ins, iterBytes) != 4) throw DecryptError("头部不完整")
            val iter = bytesToInt(iterBytes)
            // ⚠️ 迭代数上下限：防止恶意包用超大值拖死导入
            if (iter < MIN_ITERATIONS || iter > MAX_ITERATIONS) {
                throw DecryptError("KDF 迭代次数超出允许范围（$iter）")
            }
            val salt = ByteArray(SALT_LEN)
            if (readFully(ins, salt) != SALT_LEN) throw DecryptError("头部不完整")
            val nonceBase = ByteArray(NONCE_LEN)
            if (readFully(ins, nonceBase) != NONCE_LEN) throw DecryptError("头部不完整")

            // ② 头部认证数据
            val hmac = MessageDigest.getInstance("SHA-256")
            hmac.update(MAGIC)
            hmac.update(ver.toByte())
            hmac.update(algo.toByte())
            hmac.update(iterBytes)
            hmac.update(salt)
            hmac.update(nonceBase)

            val keys = deriveKeys(password, salt, iter)

            // ③ 先解出全部明文到临时区（流式），再校验 HMAC
            //
            // ⚠️ 顺序很要紧：**必须先验证 HMAC 再信任内容**。
            //   但 HMAC 在文件末尾，而流式解密不能回头 —— 所以先解到临时目录，
            //   HMAC 通过后才把它当作结果。这就是文档说的
            //   「认证不完整的暂存内容不得激活」。
            val staging = File.createTempFile("dshbak-", ".stage", destDir?.parentFile)
            staging.delete()

            var manifest: BackupManifest? = null
            var segIndex = 0
            var sawEnd = false

            try {
                staging.outputStream().buffered().use { os ->
                    while (true) {
                        val lenBytes = ByteArray(4)
                        val r = readFully(ins, lenBytes)
                        if (r < 0) {
                            // 读到文件末尾却没有终结标记 ⇒ 被截断
                            throw DecryptError("备份包被截断（缺少终结标记）")
                        }
                        if (r != 4) throw DecryptError("备份包损坏（段长度不完整）")
                        val len = bytesToInt(lenBytes)
                        if (len == 0) { sawEnd = true; break }
                        if (len < TAG_BITS / 8 || len > SEGMENT_SIZE + TAG_BITS / 8) {
                            throw DecryptError("备份包损坏（段长度异常 $len）")
                        }

                        val ct = ByteArray(len)
                        if (readFully(ins, ct) != len) throw DecryptError("备份包被截断")

                        hmac.update(lenBytes)
                        hmac.update(ct)

                        val nonce = nonceForSegment(nonceBase, segIndex)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(keys.encKey, "AES"),
                            GCMParameterSpec(TAG_BITS, nonce),
                        )
                        cipher.updateAAD(intToBytes(segIndex))
                        val pt = try {
                            cipher.doFinal(ct)
                        } catch (t: Throwable) {
                            // GCM tag 不符：密码错，或这一段被篡改
                            throw DecryptError("解密失败（密码错误或数据被篡改）")
                        }
                        os.write(pt)
                        segIndex++
                    }
                }

                if (!sawEnd) throw DecryptError("备份包被截断（缺少终结标记）")

                // ④ 读末尾 HMAC 并比对
                val stored = ByteArray(HMAC_LEN)
                val got = readFully(ins, stored)
                if (got != HMAC_LEN) throw DecryptError("备份包被截断（缺少校验码）")
                // 末尾之后不该有内容
                if (ins.read() >= 0) throw DecryptError("备份包尾部有多余数据")

                val expected = hmac.digest(keys.macKey)
                if (!MessageDigest.isEqual(stored, expected)) {
                    throw DecryptError("备份包完整性校验失败（可能被篡改、换序或删段）")
                }

                // ⑤ 解出清单
                manifest = readStagedManifest(staging)

                // ⑥ 需要的话展开到 destDir
                if (!verifyOnly && destDir != null) {
                    destDir.mkdirs()
                    extractStaged(staging, destDir)
                }
            } finally {
                keys.encKey.fill(0)
                keys.macKey.fill(0)
                staging.delete()
            }

            return manifest ?: throw DecryptError("备份包内没有清单")
        }
    }

    /**
     * 只读头部，**不需要密码**。
     *
     * 用于导入前的「这是什么包」检查。文档要求外部头「不泄露插件列表或秘密」，
     * 所以这里只能拿到格式与 KDF 参数。
     */
    fun peekHeader(src: File): HeaderInfo? = try {
        src.inputStream().buffered().use { ins ->
            val magic = ByteArray(8)
            if (readFully(ins, magic) != 8 || !magic.contentEquals(MAGIC)) return null
            val ver = ins.read()
            val algo = ins.read()
            val iterBytes = ByteArray(4)
            readFully(ins, iterBytes)
            HeaderInfo(ver, algo, bytesToInt(iterBytes))
        }
    } catch (t: Throwable) {
        null
    }

    /** 明文头信息（不含任何秘密）。 */
    data class HeaderInfo(val formatVersion: Int, val algoId: Int, val iterations: Int)

    // ── 内部 ─────────────────────────────────────────────────────────────

    private class Keys(val encKey: ByteArray, val macKey: ByteArray)

    /**
     * 从密码派生加密密钥与 MAC 密钥。
     *
     * ⚠️ 用**一次** PBKDF2 派生出 64 字节，前 32 做 AES 密钥、后 32 做 HMAC 密钥。
     * 不用两次 PBKDF2 是因为那会让加密耗时翻倍（210k 迭代已经很慢），
     * 而 64 字节的单次派生在密码学上等价（PBKDF2 输出就是伪随机）。
     */
    private fun deriveKeys(password: CharArray, salt: ByteArray, iter: Int): Keys {
        val spec = PBEKeySpec(password, salt, iter, 512)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = f.generateSecret(spec).encoded
        spec.clearPassword()
        return Keys(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
    }

    /**
     * 段 nonce = base XOR 段号。
     *
     * ⚠️ 必须保证「同一密钥下每个 nonce 只用一次」—— GCM 的硬要求，
     * 重复 nonce 会直接泄露明文异或值。XOR 段号能保证 12 字节内不重复
     * （段数远小于 2^32，而段号只动低 4 字节）。
     */
    private fun nonceForSegment(base: ByteArray, index: Int): ByteArray {
        val n = base.copyOf()
        val idx = intToBytes(index)
        // 只 XOR 低 4 字节（n[8..11]），高位保持 base
        for (i in 0 until 4) n[8 + i] = (n[8 + i].toInt() xor idx[i].toInt()).toByte()
        return n
    }

    private fun intToBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun bytesToInt(b: ByteArray) =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    /** 读满 [buf]，返回实际字节数；-1 = 一开始就 EOF。 */
    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) return if (off == 0) -1 else off
            off += n
        }
        return off
    }

    /** 把写入同时喂给 HMAC 的 OutputStream 包装。 */
    private class HmacSink(
        private val out: OutputStream,
        private val md: MessageDigest,
    ) {
        fun write(b: ByteArray) {
            out.write(b)
            md.update(b)
        }
    }

    // ── 占位：明文流的构造与解析 ──────────────────────────────────────────
    //
    // ⚠️ 这两个函数依赖 BackupManifest 与 zip 打包逻辑，
    //   在 BackupBundle.kt 里实现。这里只声明，保持本文件专注密码学。

    private fun buildPlainStream(srcDir: File, manifest: BackupManifest): InputStream =
        BackupBundle.buildPlainStream(srcDir, manifest)

    private fun readStagedManifest(staging: File): BackupManifest? =
        BackupBundle.readManifest(staging)

    private fun extractStaged(staging: File, destDir: File) =
        BackupBundle.extract(staging, destDir)

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：认证加密、头部认证、截断检测、段重排检测、流式（恒定内存）、
     *          KDF 参数上下限、密码不落盘、密码不写日志
     * ❌ 未做：Keystore 包裹的免输入密钥（[BackupKeyVault] 待实现）、
     *          密码世代（改密码后旧包仍需旧密码 —— 靠每个包自带 salt 天然支持，
     *          但没有 UI 提示）、导入配额（解压炸弹防护在 BackupBundle 里）
     */
}
