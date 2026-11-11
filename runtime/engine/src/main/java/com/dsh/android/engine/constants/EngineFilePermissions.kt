package com.dsh.android.engine.constants

import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/** 凭据文件的宿主权限约定。 */
        /**
         * 凭据文件名（`dsh-credentials-local` 的 `CREDENTIALS_FILENAME`，写死，
         * 因为它是引擎的公开约定而不是可配置项）。见 [ensureCredentialFileMode]。
         */
const val CREDENTIALS_FILE = ".credentials.yaml"

        /**
         * 凭据文件唯一被接受的权限位：`0600`（仅属主可读写）。
         *
         * 见 [ensureCredentialFileMode]。写入值写死成 `0600` 而不是「把过宽的位
         * 减掉」：引擎自己写这个文件时用的就是 `writeFileAtomic(..., { mode: 0o600 })`，
         * `0600` 才是它认得的规范形状。
         */
val OWNER_ONLY_PERMISSIONS: Set<PosixFilePermission> =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        /**
         * 引擎 `assertOwnerOnly()` 的判据位：组 + 其他的全部 9 位，即 `0o077`。
         *
         * `dsh-credentials-local/lib/index.js` 里是
         * `const GROUP_OTHER_BITS = 63;` + `if ((mode & GROUP_OTHER_BITS) === 0) return;`。
         * **只拒绝这 9 位非零**，不关心属主执行位（所以 `0700` 是它接受的文件）。
         * 我们照抄这个判据，免得去改一个引擎本来就接受的文件。
         */
val GROUP_OTHER_BITS: Set<PosixFilePermission> =
            EnumSet.of(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE,
            )

