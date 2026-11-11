// boo-sandbox-run — 把 DSH 沙箱层的 bwrap 风格 runner 协议翻译成本项目的
// libboosandbox.so 环境协议，然后 exec 真正的命令。
//
// 为什么需要它
// ------------
// DSH 的 `@deepseek-ai/dsh-sandbox-local` 按平台挑沙箱后端：
//
//   PLATFORM_CHAINS = { linux: ["bwrap","landlock"], darwin: ["seatbelt"], win32: ["windows-acl"] }
//
// Android 落不进任何一条：内核 4.14（设备实测 4.14.116）没有 Landlock
// （需 5.13+），没有 bwrap，`unshare -U` 因无 CAP_SYS_ADMIN 失败。于是
// `confine()` 抛 SANDBOX_UNAVAILABLE，而它是 **fail-closed** 的 —— 不是不安全，
// 是 bash 直接不能用：
//
//   sandbox mode "workspace-write" is requested but no sandbox backend is usable
//   on this host; refusing to run the command unconfined.
//
// 但该 provider 留了一个操作员口子：`Config.runnerCommand`。非空时它**不做
// 平台探测**，直接把 runner 当成可用的 bwrap 等价物，并按 bwrap 方言拼参数：
//
//   [ ...runnerCommand, ...bwrapProfileArgs(policy), "--", ...真实argv ]
//
// 于是我们不必改引擎源码，只要写一个听得懂那套参数的 runner。它把
// `--bind <root> <root>`（workspace-write 的可写根）翻译成
// `BOO_SANDBOX_ALLOW`，把「没有 --bind」翻译成 readonly，再把
// libboosandbox.so 塞进子进程的 LD_PRELOAD —— 拦截发生在 libc 边界，所以
// 解释器内部的写（node 的 fs.writeFileSync、python 的 open）同样被挡住。
//
// 收到的参数（见 dsh-sandbox-local/lib/index.js 的 bwrapProfileArgs）
// ---------------------------------------------------------------
//   --ro-bind / /          bind 整个根只读     → 无需处理（本方案不挂载）
//   --dev /dev             devtmpfs            → 无需处理
//   --unshare-pid          PID 命名空间        → ⚠️ 无法实现（见下）
//   --proc /proc           procfs              → 无需处理（宿主 /proc 已在）
//   --die-with-parent      父死子死            → 用 PR_SET_PDEATHSIG 实现
//   --tmpfs /tmp           workspace-write 专属：临时区可写
//   --bind SRC DST         workspace-write 专属：可写根
//
// 与真 bwrap 的差异（诚实记录）
// ---------------------------
//   · `--unshare-pid` 做不到：没有 CAP_SYS_ADMIN，`unshare(CLONE_NEWPID)` 返回
//     EPERM。后果是沙箱内进程看得见宿主 PID 空间，`kill` 能打到别的进程。
//     文件写边界不受影响 —— 那是本 runner 实际承诺的东西。
//   · `--ro-bind / /` 不做：Android 上 SELinux 已经在文件层面兜住了系统分区，
//     再叠一层只读绑定既无必要也做不到（同样需要 CAP_SYS_ADMIN）。
//   · 因此 enforcement 是「写边界完整、命名空间隔离缺失」。引擎把
//     runnerCommand 一律标成 "full"，所以这个差异必须在这里说清楚，
//     而不是让调用方以为拿到了完整 bwrap。
//
// 环境协议（与 libboosandbox.so 对接）
// -----------------------------------
//   BOO_SANDBOX_LIB      libboosandbox.so 的绝对路径（由 App 注入；缺省则报错退出）
//   BOO_SANDBOX_MODE     readonly | workspace | 不设（不强制）
//   BOO_SANDBOX_ALLOW    ':' 分隔的可写根
//   BOO_SANDBOX_DEBUG=1  打印解析结果到 stderr
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/prctl.h>
#include <signal.h>
#endif

#define MAX_ROOTS 8

static int g_debug = 0;

static void dbg(const char *fmt, ...) {
  if (!g_debug) return;
  va_list ap;
  va_start(ap, fmt);
  fputs("[boo-sandbox-run] ", stderr);
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
}

int main(int argc, char **argv) {
  g_debug = getenv("BOO_SANDBOX_DEBUG") && getenv("BOO_SANDBOX_DEBUG")[0] == '1';

  const char *lib = getenv("BOO_SANDBOX_LIB");
  if (!lib || !*lib) {
    fprintf(stderr, "boo-sandbox-run: BOO_SANDBOX_LIB is not set; refusing to run unconfined\n");
    return 125;
  }

  /* 解析 bwrap 方言，收集可写根。`--` 之后是真实 argv。 */
  char roots[MAX_ROOTS][PATH_MAX];
  int nroots = 0;
  int saw_tmpfs = 0;
  int die_with_parent = 0;
  int i = 1;
  int exec_at = -1;

  for (; i < argc; i++) {
    const char *a = argv[i];
    if (strcmp(a, "--") == 0) {
      exec_at = i + 1;
      break;
    }
    if (strcmp(a, "--ro-bind") == 0 || strcmp(a, "--bind") == 0 ||
        strcmp(a, "--dev-bind") == 0) {
      if (i + 2 >= argc) {
        fprintf(stderr, "boo-sandbox-run: %s needs 2 operands\n", a);
        return 125;
      }
      /* 只有 --bind 表示「可写」；--ro-bind / --dev-bind 不授予写权限。 */
      if (strcmp(a, "--bind") == 0) {
        const char *src = argv[i + 1];
        size_t l = strlen(src);
        if (nroots < MAX_ROOTS && l < PATH_MAX) {
          memcpy(roots[nroots], src, l + 1);
          nroots++;
          dbg("可写根: %s", src);
        }
      }
      i += 2;
      continue;
    }
    if (strcmp(a, "--dev") == 0 || strcmp(a, "--proc") == 0 ||
        strcmp(a, "--tmpfs") == 0) {
      if (i + 1 >= argc) {
        fprintf(stderr, "boo-sandbox-run: %s needs 1 operand\n", a);
        return 125;
      }
      if (strcmp(a, "--tmpfs") == 0) saw_tmpfs = 1;
      i += 1;
      continue;
    }
    if (strcmp(a, "--die-with-parent") == 0) {
      die_with_parent = 1;
      continue;
    }
    if (strcmp(a, "--unshare-pid") == 0) {
      /* 无法实现，但不该让命令失败 —— 见文件头「与真 bwrap 的差异」。 */
      dbg("忽略 --unshare-pid（Android 无 CAP_SYS_ADMIN）");
      continue;
    }
    /* 未知 flag：fail closed。宁可拒绝也不要在不理解的 profile 下放行。 */
    fprintf(stderr, "boo-sandbox-run: unknown profile argument: %s\n", a);
    return 125;
  }

  if (exec_at < 0 || exec_at >= argc) {
    fprintf(stderr, "boo-sandbox-run: missing `--` and command argv\n");
    return 125;
  }

  /* workspace-write 语义 = 工作区 + 平台临时区（对齐 dsh-sandbox 的
   * writableRoots，避免「写工具能写而 bash 不能」的不对称）。
   * bwrap 方言把临时区表达为 `--tmpfs /tmp`，而本 App 的 TMPDIR 是
   * cacheDir，所以两者都要收进来。 */
  char allow[PATH_MAX * MAX_ROOTS];
  allow[0] = '\0';
  for (int k = 0; k < nroots; k++) {
    if (allow[0]) strncat(allow, ":", sizeof(allow) - strlen(allow) - 1);
    strncat(allow, roots[k], sizeof(allow) - strlen(allow) - 1);
  }
  const char *tmpdir = getenv("TMPDIR");
  if (saw_tmpfs && tmpdir && *tmpdir) {
    if (allow[0]) strncat(allow, ":", sizeof(allow) - strlen(allow) - 1);
    strncat(allow, tmpdir, sizeof(allow) - strlen(allow) - 1);
    dbg("临时区可写: %s", tmpdir);
  }
  if (saw_tmpfs) {
    if (allow[0]) strncat(allow, ":", sizeof(allow) - strlen(allow) - 1);
    strncat(allow, "/tmp", sizeof(allow) - strlen(allow) - 1);
  }

  const char *mode = nroots > 0 ? "workspace" : "readonly";
  dbg("mode=%s allow=%s", mode, allow[0] ? allow : "(none)");

  setenv("BOO_SANDBOX_MODE", mode, 1);
  if (allow[0]) setenv("BOO_SANDBOX_ALLOW", allow, 1);
  /* 追加而非覆盖：App 已经为 node 本身预载了 link 兼容层，两个库都要生效
   * （LD_PRELOAD 支持空格分隔的多个库）。 */
  const char *existing = getenv("LD_PRELOAD");
  char preload[PATH_MAX * 2];
  if (existing && *existing) {
    snprintf(preload, sizeof(preload), "%s %s", lib, existing);
  } else {
    snprintf(preload, sizeof(preload), "%s", lib);
  }
  setenv("LD_PRELOAD", preload, 1);

  if (die_with_parent) {
#ifdef __linux__
    /* 父进程（node）退出时自杀，等价于 bwrap 的 --die-with-parent。
     * ⚠️ 与 prctl 有固有竞态：若父进程在 prctl 之前就死了，信号不会补发。
     * 这里父进程是长期存活的 node，窗口极小；且即便漏掉，后果只是子进程
     * 变孤儿，不影响沙箱边界。 */
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) {
      dbg("prctl(PR_SET_PDEATHSIG) 失败: %s（忽略）", strerror(errno));
    }
    if (getppid() == 1) return 125; /* 父进程已死 */
#endif
  }

  execvp(argv[exec_at], &argv[exec_at]);
  fprintf(stderr, "boo-sandbox-run: cannot execute %s: %s\n", argv[exec_at],
          strerror(errno));
  return errno == ENOENT ? 127 : 126;
}
