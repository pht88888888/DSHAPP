// liblinkcompat.so — Android 上 link() / linkat() 的语义转译层（LD_PRELOAD）。
//
// 为什么需要它
// ------------
// SELinux 的 untrusted_app(_27) 域对 app_data_file **没有 link 权限**：
//
//   avc: denied { link } for comm="libuv-worker"
//     name="session.v4.jsonl.zstd.<hex>.tmp"
//     scontext=u:r:untrusted_app_27:s0  tcontext=u:object_r:app_data_file:s0
//     tclass=file permissive=0
//
// 于是 node 进程里任何 link() / linkat() 都返回 EACCES。而 DSH 上游
// （dsh-session-persistence-jsonl / dsh-attachment-local / dsh-fs-local）用硬链接
// 做**独占原子发布**，并靠 EEXIST 判定「已被别人发布」：
//
//   try { await link(staged, currentPath); }
//   catch (e) { if (e.code === 'EEXIST') return false; throw e; }   // ← EACCES 走这里
//
// EACCES 不是 EEXIST，于是直接冒泡成「本轮运行失败」。
//
// SELinux 策略改不了（AOSP 为防沙箱数据外泄主动移除，且是收紧方向）。但
// **不需要让 link 成功，只需要让调用方不依赖它** —— 在 libc 边界把系统调用转译成
// 语义等价的组合即可。这是平台适配层，上游代码一行不用动（动了会牺牲 Linux 上的正确性）。
//
// 转译语义
// --------
//   link(from, to)  ≡  把 from 的内容复制到 to，且 to 必须原本不存在
//
// 必须同时保住三个性质：
//
//   ① 独占 —— to 已存在时必须返回 EEXIST（调用方靠它做 CAS / 乐观并发控制）。
//      ⇒ 用 renameat2(RENAME_NOREPLACE) 发布：内核保证「目标存在则失败且不覆盖」。
//
//   ② 保源 —— 真 link 不消耗 from。**不能图省事换成 rename()**：rename 会移走 from，
//      而 dsh-attachment-local 的 publishImmutableAlias 其 source 是
//      content-addressed 持久对象，会被反复 link 到不同 ref（同文件 index.js
//      第 690 与 708 行指向同一个 object）。第一次 rename 之后第二次就 ENOENT。
//      这是本实现与「O_EXCL 占位 + rename」朴素写法的关键差别 —— 那种写法会破坏数据。
//
//   ③ 原子 —— 读者要么看到旧内容、要么看到新内容，不存在中间态。
//      先复制到**同目录**临时文件（保证同文件系统），再 renameat2 发布。
//
// 与真 link 的差异（已逐处核对，可接受）
// --------------------------------------
//   · inode 不同（复制而非共享）。核对结论：全部调用方只依赖 EEXIST 语义与
//     内容摘要校验（digestFile / sha256），没有任何一处依赖「同 inode」。
//   · 空间占用翻倍（源与目标各存一份）。会话日志与附件量级下可接受。
//   · 时间戳用 utimensat 显式复制，权限位沿用源的 mode，尽量贴近原观感。
//
// 只 hook link / linkat 两个符号，其余一律透传，不改变任何其它写操作的行为。
//
// 诊断开关
// --------
//   LINKCOMPAT_DISABLE=1  关闭转译（直接透传真 link，用于对照实验）
//   LINKCOMPAT_VERBOSE=1  每次转译打一行到 stderr
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1 << 0)
#endif
#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif

#define COPY_BUF 65536

/* 环境开关：只在首次调用时读一次。 */
static int env_flag(const char *name) {
  const char *v = getenv(name);
  return v && v[0] == '1' && v[1] == '\0';
}

static int disabled(void) {
  static int cached = -1;
  if (cached < 0) cached = env_flag("LINKCOMPAT_DISABLE");
  return cached;
}

static int verbose(void) {
  static int cached = -1;
  if (cached < 0) cached = env_flag("LINKCOMPAT_VERBOSE");
  return cached;
}

static void note(const char *from, const char *to) {
  if (!verbose()) return;
  fprintf(stderr, "[linkcompat] 转译 link: %s -> %s\n", from, to);
}

/* 把 (dirfd, path) 解析成绝对路径。
 *
 * ⚠️ 必须解析 dirfd 而不是直接信任路径：`linkat(dirfd, "a", AT_FDCWD, "b")`
 * 里 "a" 是相对 dirfd 的，拿 getcwd() 拼会指向错误的文件。
 * 返回 0 成功，-1 失败（errno 已设）。 */
static int resolve(int dirfd, const char *path, char *out, size_t n) {
  if (!path) {
    errno = EFAULT;
    return -1;
  }
  if (path[0] == '/') {
    size_t l = strlen(path);
    if (l >= n) {
      errno = ENAMETOOLONG;
      return -1;
    }
    memcpy(out, path, l + 1);
    return 0;
  }
  if (dirfd == AT_FDCWD) {
    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof(cwd))) return -1;
    int need = snprintf(out, n, "%s/%s", cwd, path);
    if (need < 0 || (size_t)need >= n) {
      errno = ENAMETOOLONG;
      return -1;
    }
    return 0;
  }
  /* 非 AT_FDCWD：借 /proc/self/fd 把句柄还原成路径（与 kit 的 resolve_at 同法）。 */
  char link[64];
  int k = snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
  if (k < 0 || (size_t)k >= sizeof(link)) {
    errno = ENAMETOOLONG;
    return -1;
  }
  ssize_t r = syscall(__NR_readlinkat, AT_FDCWD, link, out, n - 1);
  if (r <= 0) {
    if (r == 0) errno = ENOENT;
    return -1;
  }
  out[r] = '\0';
  size_t rl = (size_t)r;
  int need = snprintf(out + rl, n - rl, "/%s", path);
  if (need < 0 || (size_t)need >= n - rl) {
    errno = ENAMETOOLONG;
    return -1;
  }
  return 0;
}

/* 核心转译：复制 from -> 同目录临时文件 -> renameat2(RENAME_NOREPLACE) 发布。
 *
 * 全程用 libc 的 open/read/write（本库只被 LD_PRELOAD 到 node 进程，不加载任何
 * 会 hook 这些符号的库，所以不会自我递归）；只有 renameat2 必须走 syscall ——
 * bionic 没有导出它（那是 glibc 的扩展）。 */
static int compat_link(int olddirfd, const char *from, int newdirfd,
                       const char *to) {
  char src[PATH_MAX], dst[PATH_MAX];
  if (resolve(olddirfd, from, src, sizeof(src)) != 0) return -1;
  if (resolve(newdirfd, to, dst, sizeof(dst)) != 0) return -1;

  struct stat st;
  if (stat(src, &st) != 0) return -1;
  /* 真 link 对目录返回 EPERM（Linux 不允许硬链接目录）。照搬，别放行。 */
  if (!S_ISREG(st.st_mode)) {
    errno = EPERM;
    return -1;
  }

  int in = open(src, O_RDONLY | O_CLOEXEC);
  if (in < 0) return -1;

  /* 临时文件放在**目标同目录**：renameat2 只有同文件系统才是原子的。 */
  char tmp[PATH_MAX];
  int tn = snprintf(tmp, sizeof(tmp), "%s.%d.linkcompat.tmp", dst, (int)getpid());
  if (tn < 0 || (size_t)tn >= sizeof(tmp)) {
    close(in);
    errno = ENAMETOOLONG;
    return -1;
  }

  int out = open(tmp, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC,
                 st.st_mode & 07777);
  if (out < 0) {
    int e = errno;
    close(in);
    errno = e;
    return -1;
  }

  int saved = 0;
  char buf[COPY_BUF];
  for (;;) {
    ssize_t r = read(in, buf, sizeof(buf));
    if (r == 0) break;
    if (r < 0) {
      if (errno == EINTR) continue;
      saved = errno;
      break;
    }
    ssize_t off = 0;
    while (off < r) {
      ssize_t w = write(out, buf + off, (size_t)(r - off));
      if (w < 0) {
        if (errno == EINTR) continue;
        saved = errno;
        break;
      }
      off += w;
    }
    if (saved) break;
  }
  close(in);

  if (!saved) {
    /* 复制时间戳，贴近「同一 inode」的观感。失败不影响正确性。
     * 用 futimens 而不是 utimensat：这里持有的是 fd，而 utimensat 要路径
     * （想对 fd 生效得配 AT_EMPTY_PATH，多一层不必要的不确定性）。 */
    struct timespec ts[2];
    ts[0] = st.st_atim;
    ts[1] = st.st_mtim;
    (void)futimens(out, ts);
    /* 先落盘再发布：rename 本身原子，但要保证读者看到的是完整内容。 */
    if (fsync(out) != 0) saved = errno;
  }
  if (close(out) != 0 && !saved) saved = errno;

  if (saved) {
    unlink(tmp);
    errno = saved;
    return -1;
  }

  if (syscall(__NR_renameat2, AT_FDCWD, tmp, AT_FDCWD, dst,
              RENAME_NOREPLACE) != 0) {
    int e = errno;
    if (e == ENOSYS || e == EINVAL || e == ENOTSUP) {
      /* 两条路都到不了 renameat2：
       *
       *   ENOSYS  老内核（< 3.15）根本没有这个系统调用。
       *   EINVAL  **FUSE/sdcardfs 不支持 RENAME_NOREPLACE**（真机实测 /sdcard 上
       *           返回 EINVAL）。用户把工作区设在 /sdcard 时走的就是这里 ——
       *           不认这个 errno 的话 write 工具会直接报
       *           `EINVAL: invalid argument, link ...`。
       *   ENOTSUP 部分文件系统对 flags 的整体拒绝。
       *
       * 退化为「O_EXCL 占位 + rename」：独占性仍由 O_EXCL 保证，但两步之间被杀会
       * 留下空占位文件，导致后续永久 EEXIST。
       *
       * ⚠️ 这个窗口在 /sdcard 上是**真实存在**的（app_data_file 上走不到这里，
       * 内核 4.14 支持 renameat2）。取舍依据：占位与 rename 之间只隔一次系统调用，
       * 且被杀的后果是「该文件名此后写不进去」，而不是数据损坏 —— 相比
       * 「/sdcard 工作区里 write 工具完全不可用」，这个代价明显更小。 */
      int guard = open(dst, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
      if (guard < 0) {
        unlink(tmp);
        return -1; /* EEXIST 原样透出 */
      }
      close(guard);
      if (rename(tmp, dst) != 0) {
        int e2 = errno;
        unlink(tmp);
        unlink(dst);
        errno = e2;
        return -1;
      }
      note(src, dst);
      return 0;
    }
    unlink(tmp);
    errno = e;
    return -1;
  }

  note(src, dst);
  return 0;
}

int link(const char *from, const char *to) {
  static int (*real)(const char *, const char *) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "link");
  if (disabled() || !real) {
    errno = ENOSYS;
    return -1;
  }
  /* 先试原生：SELinux 若允许（例如 /dev 下的路径，或策略将来放宽）就直接走，
   * 零开销。只有被 SELinux 拒了才转译。 */
  if (real(from, to) == 0) return 0;
  if (errno != EACCES && errno != EPERM) return -1; /* EEXIST/ENOENT 等原样返回 */
  return compat_link(AT_FDCWD, from, AT_FDCWD, to);
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
           int flags) {
  static int (*real)(int, const char *, int, const char *, int) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "linkat");
  if (disabled() || !real) {
    errno = ENOSYS;
    return -1;
  }
  /* AT_EMPTY_PATH 的语义是「链接 olddirfd 本身」，没有路径可复制，不转译。 */
  if ((flags & AT_EMPTY_PATH) != 0) {
    return real(olddirfd, oldpath, newdirfd, newpath, flags);
  }
  if (real(olddirfd, oldpath, newdirfd, newpath, flags) == 0) return 0;
  if (errno != EACCES && errno != EPERM) return -1;
  return compat_link(olddirfd, oldpath, newdirfd, newpath);
}
