// libboosandbox.so — LD_PRELOAD write-interceptor for Boo's permission modes.
//
// WHY THIS EXISTS
// ---------------
// Android offers no usable kernel sandbox for an app-uid process: SELinux is
// enforcing, `unshare -m`/`-U` fail with EPERM (no CAP_SYS_ADMIN, no user
// namespaces on the 4.14 kernels these devices ship), and bubblewrap/proot are
// therefore unavailable. Shizuku's uid=2000 is blocked from app-private storage
// by SELinux (runas_app). So confinement has to happen *in-process*, at the
// libc boundary every runtime funnels through.
//
// This library interposes the write-capable libc entry points (open/openat/
// creat/fopen/unlink/mkdir/rename/truncate/…) and denies any that would mutate
// a path outside the allowlist. Because it sits at the syscall wrapper, it
// catches writes made from *inside* interpreters too — python's open(), node's
// fs.writeFileSync(), a compiled binary's fwrite() — which no amount of static
// analysis of the shell command string could do.
//
// ⚠️ INTERPOSE BOTH THE PLAIN AND THE `*at` FORM. bionic routes the `*at`
// variant wherever a dirfd is available, so covering only the plain name leaves
// a silent hole. `mkdirat` was missing for exactly this reason and let toybox
// `mkdir` create directories outside the allowlist, with no denial logged
// (measured 2026-09-26 — see the comment on `mkdirat` below).
//
// MODES (env BOO_SANDBOX_MODE)
//   readonly  — deny every write anywhere; reads unrestricted.
//   workspace — allow writes under BOO_SANDBOX_ALLOW only; reads unrestricted.
//   (unset)   — not enforcing; the library is inert. Used for full access.
//
// Verified on-device: python3 writing /sdcard raised PermissionError while the
// same write inside the workspace succeeded, and reads outside stayed allowed.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define MODE_NONE 0
#define MODE_READONLY 1
#define MODE_WORKSPACE 2

static int g_mode = -1; /* -1 = not yet initialised */
/* Writable roots. `BOO_SANDBOX_ALLOW` carries one or more `:`-separated
 * paths. Boo's original spelling was a single path, which still works; the
 * DSH sandbox seam's `workspace-write` grants the workspace root **plus** the
 * platform temp area, so a list is the normal case there — see
 * `dsh-sandbox/roots.js` (`writableRoots`), whose meaning this mirrors. */
#define MAX_ALLOW_ROOTS 8
static char g_allow[MAX_ALLOW_ROOTS][PATH_MAX];
static int g_allow_count = 0;

/* The same directory has two names on Android: `/data/user/0/<pkg>` is a
 * symlink to `/data/data/<pkg>`. `BOO_SANDBOX_ALLOW` comes from Kotlin in the
 * `/data/user/0` spelling, but `getcwd()` returns the kernel's real path — so a
 * relative path absolutized against the cwd becomes `/data/data/…` and the two
 * no longer compare equal under `under()`, which is deliberately lexical.
 *
 * 🔴 Consequence (measured 2026-09-26): **writes inside the workspace were
 * denied when addressed relatively** — `touch x`, `cp a b`, `mv a b` all failed
 * with "outside the allowed area" while the cwd *was* the workspace, and while
 * `mkdir x` (whose caller passes a literal absolute path) succeeded. Pure
 * functional breakage, not a hole: nothing outside the workspace was ever let
 * through.
 *
 * Fix: remember both spellings and accept either. Resolved once here rather
 * than per call — `realpath()` on every intercepted write would be both slow
 * (once per `open`) and re-entrant into this same library. */
static char g_allow_real[MAX_ALLOW_ROOTS][PATH_MAX];

/* Defined below; `init_once()` canonicalises `g_allow_real` with it. */
static void normalize(char *p);

/* Device nodes and pseudo-files nearly every program writes as a matter of
 * course. Denying these breaks ordinary execution (/dev/null is the canonical
 * case — a naive allowlist rejects `cmd > /dev/null`), so they are always
 * writable regardless of mode. They carry no data out of the sandbox. */
static const char *const g_dev_allow[] = {
    "/dev/null", "/dev/zero", "/dev/full", "/dev/tty",
    "/dev/stdout", "/dev/stderr", "/dev/stdin",
    "/dev/ptmx", "/dev/pts/", "/dev/urandom", "/dev/random",
    "/proc/self/", "/proc/thread-self/",
    NULL,
};

static void init_once(void) {
  if (g_mode != -1) return;
  const char *m = getenv("BOO_SANDBOX_MODE");
  if (m && strcmp(m, "readonly") == 0) {
    g_mode = MODE_READONLY;
  } else if (m && strcmp(m, "workspace") == 0) {
    g_mode = MODE_WORKSPACE;
  } else {
    g_mode = MODE_NONE;
  }
  const char *a = getenv("BOO_SANDBOX_ALLOW");
  if (a && *a) {
    /* `:` 分隔的多根。空段（`a::b`）跳过而不是当成根 —— 空根在 `under()`
     * 里会被判为「不匹配任何路径」，看似安全，但它在 `is_dev_allow` 之前
     * 求值，留着只会浪费一次比较并让日志出现空白根名。 */
    char buf[PATH_MAX * MAX_ALLOW_ROOTS];
    strncpy(buf, a, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    char *save = NULL;
    for (char *tok = strtok_r(buf, ":", &save); tok && g_allow_count < MAX_ALLOW_ROOTS;
         tok = strtok_r(NULL, ":", &save)) {
      if (!*tok) continue;
      size_t tl = strlen(tok);
      if (tl >= PATH_MAX) continue; /* 超长根名不可信，丢弃（其余根仍生效） */
      memcpy(g_allow[g_allow_count], tok, tl + 1);
      /* Canonical spelling of the same root (`/data/user/0/…` -> `/data/data/…`).
       * `realpath()` only walks lstat/readlink, neither of which is intercepted
       * below, so this cannot re-enter our own hooks. On failure leave it empty
       * and fall back to the literal spelling alone. */
      char real[PATH_MAX];
      if (realpath(tok, real) && real[0] == '/') {
        size_t rl = strlen(real);
        if (rl < PATH_MAX) {
          memcpy(g_allow_real[g_allow_count], real, rl + 1);
          normalize(g_allow_real[g_allow_count]); /* collapse any `..`/`//` realpath left in */
        }
      }
      g_allow_count++;
    }
  }
}

static int enforcing(void) {
  init_once();
  return g_mode == MODE_READONLY || g_mode == MODE_WORKSPACE;
}

/* Resolve a path to absolute form so a relative path can't dodge the prefix
 * check by being interpreted against a different cwd.
 *
 * ⚠️ Fails **closed**: on overflow it leaves `out` empty, and `allowed()`
 * treats an empty absolute path as "unresolvable -> deny". A silently
 * truncated path is a real bypass — cutting a >PATH_MAX path at the boundary
 * can remove a trailing "/../.." and make an escaping path look contained. */
static void absolutize(const char *path, char *out, size_t n) {
  if (!path || !*path || n < 2) {
    out[0] = '\0';
    return;
  }
  if (path[0] == '/') {
    size_t pl = strlen(path);
    if (pl >= n) { /* would truncate -> refuse rather than mis-judge */
      out[0] = '\0';
      return;
    }
    memcpy(out, path, pl + 1);
    return;
  }
  char cwd[PATH_MAX];
  if (!getcwd(cwd, sizeof(cwd))) {
    out[0] = '\0';
    return;
  }
  int need = snprintf(out, n, "%s/%s", cwd, path);
  if (need < 0 || (size_t)need >= n) out[0] = '\0';
}

/* Collapse "." / ".." components and duplicate slashes, in place.
 *
 * 🔴 This is load-bearing. `absolutize` only makes a path absolute; without
 * this step the prefix checks below are trivially defeated by
 * `/proc/self/../../sdcard/x`, which *starts with* the whitelisted
 * "/proc/self/" but resolves far outside the sandbox. Verified on a real
 * device (2026-09-26): the write escaped and succeeded. The same trick works
 * against the workspace root: `%s/../../sdcard/x` passes `in_workspace`.
 *
 * Deliberately **lexical** — it never touches the filesystem, so it cannot
 * block, cannot be raced, and cannot recurse back into our own hooks.
 * ⚠️ Consequently symlinks are NOT resolved here: a symlink created inside the
 * workspace can still point outside it. Resolving that safely needs a
 * readlink/lstat walk (realpath() inside a libc interposer risks re-entering
 * the hooked calls and hanging every shell command), and Go's Confine has the
 * same lexical-only property. Documented limitation, not an oversight. */
static void normalize(char *p) {
  if (!p || p[0] != '/') return; /* relative/unusable -> leave for fail-closed */
  char *out = p + 1;             /* `out` never overtakes the read cursor */
  const char *r = p + 1;
  while (*r) {
    while (*r == '/') r++; /* collapse runs of slashes */
    if (!*r) break;
    const char *start = r;
    while (*r && *r != '/') r++;
    size_t len = (size_t)(r - start);
    if (len == 1 && start[0] == '.') continue; /* "." */
    if (len == 2 && start[0] == '.' && start[1] == '.') {
      /* ".." pops one component, but never above the root. */
      if (out > p + 1) {
        out--; /* step back off the separator we just wrote */
        while (out > p + 1 && out[-1] != '/') out--;
      }
      continue;
    }
    memmove(out, start, len);
    out += len;
    *out++ = '/';
  }
  if (out > p + 1) out--; /* drop the trailing separator (keep a bare "/") */
  *out = '\0';
}

/* True when `prefix` matches `abs` on whole path components.
 *
 * A raw `strncmp` would treat "/proc/self/" as a prefix of
 * "/proc/selfie/evil" and also accept sibling roots such as "/work-other"
 * for a workspace of "/work". */
static int under(const char *abs, const char *prefix) {
  size_t pl = strlen(prefix);
  if (pl == 0) return 0;
  if (strncmp(abs, prefix, pl) != 0) return 0;
  if (prefix[pl - 1] == '/') return 1; /* prefix already ends at a boundary */
  return abs[pl] == '\0' || abs[pl] == '/';
}

static int is_dev_allow(const char *abs) {
  for (int i = 0; g_dev_allow[i]; i++) {
    if (under(abs, g_dev_allow[i])) return 1;
  }
  return 0;
}

static int in_workspace(const char *abs) {
  if (g_allow_count == 0) return 0;
  for (int i = 0; i < g_allow_count; i++) {
    if (under(abs, g_allow[i])) return 1;
    /* Same root, the other Android spelling (see `g_allow_real`). Without this,
     * a relative path resolved through `getcwd()` — which reports
     * `/data/data/…` — is rejected even though it lands inside the workspace. */
    if (g_allow_real[i][0] != '\0' && under(abs, g_allow_real[i])) return 1;
  }
  return 0;
}

/* Returns 1 when the write is permitted. */
static int allowed(const char *path) {
  if (!enforcing()) return 1;
  if (!path || !*path) return 1;
  char abs[PATH_MAX];
  absolutize(path, abs, sizeof(abs));
  if (abs[0] == '\0') return 0; /* unresolvable -> fail closed */
  normalize(abs);
  if (abs[0] != '/') return 0; /* normalize rejected it -> fail closed */
  if (is_dev_allow(abs)) return 1;
  if (g_mode == MODE_READONLY) return 0;
  return in_workspace(abs);
}

/* Resolve an `*at()` path against a dirfd, so a caller can't hide an escaping
 * path behind an open directory handle.
 *
 * ⚠️ Without this, `openat(dirfd_of_workspace, "../../sdcard/x", O_CREAT)`
 * bypassed the sandbox entirely: the old code only checked paths that were
 * absolute or used AT_FDCWD. dirfd == AT_FDCWD is just the cwd.
 *
 * Returns 0 on success, -1 when the dirfd cannot be resolved (caller must fail
 * closed). */
static int resolve_at(int dirfd, const char *path, char *out, size_t n) {
  if (path && path[0] == '/') {
    absolutize(path, out, n);
    return out[0] ? 0 : -1;
  }
  if (dirfd == AT_FDCWD) {
    absolutize(path, out, n);
    return out[0] ? 0 : -1;
  }
  char link[64];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
  ssize_t r = readlink(link, out, n - 1);
  if (r <= 0) { /* unknown dirfd -> refuse */
    out[0] = '\0';
    return -1;
  }
  out[r] = '\0';
  size_t rl = strlen(out);
  int need = snprintf(out + rl, n - rl, "/%s", path ? path : "");
  if (need < 0 || (size_t)need >= n - rl) {
    out[0] = '\0';
    return -1;
  }
  return 0;
}

/* `*at()` flavour of allowed(): resolves the dirfd first. */
static int allowed_at(int dirfd, const char *path) {
  if (!enforcing()) return 1;
  if (!path || !*path) return 1;
  char abs[PATH_MAX];
  if (resolve_at(dirfd, path, abs, sizeof(abs)) != 0) return 0;
  normalize(abs);
  if (abs[0] != '/') return 0;
  if (is_dev_allow(abs)) return 1;
  if (g_mode == MODE_READONLY) return 0;
  return in_workspace(abs);
}

static int deny(const char *path) {
  init_once();
  /* ⚠️ 这行文案是接口的一部分：`dsh-sandbox-local` 的
   * `DENIAL_SIGNATURES.runnerCommand` 正是 `["read-only file system",
   * "permission denied"]`，靠它把子进程的 stderr 归类成「沙箱拒绝」而不是
   * 「命令本身失败」。改字前先确认那个常量。
   * 根名可能有多个，用 `:` 拼出来，别只打第一个。 */
  char roots[PATH_MAX * MAX_ALLOW_ROOTS];
  roots[0] = '\0';
  for (int i = 0; i < g_allow_count; i++) {
    if (roots[0]) strncat(roots, ":", sizeof(roots) - strlen(roots) - 1);
    strncat(roots, g_allow[i], sizeof(roots) - strlen(roots) - 1);
  }
  fprintf(stderr,
          "[boo] permission denied: %s is outside the allowed area "
          "(mode=%s, allowed=%s)\n",
          path ? path : "(null)",
          g_mode == MODE_READONLY ? "read-only" : "workspace-write",
          roots[0] ? roots : "(none)");
  errno = EACCES;
  return -1;
}

static int writeish(int flags) {
  int acc = flags & O_ACCMODE;
  return acc == O_WRONLY || acc == O_RDWR || (flags & O_CREAT) ||
         (flags & O_TRUNC) || (flags & O_APPEND);
}

/* Single-path wrappers: deny when the target is outside the allowed area. */
#define WRAP_PATH(name)                             \
  int name(const char *a) {                         \
    static int (*real)(const char *) = NULL;        \
    if (!real) real = dlsym(RTLD_NEXT, #name);      \
    if (!allowed(a)) return deny(a);                \
    return real(a);                                 \
  }

int open(const char *path, int flags, ...) {
  static int (*real)(const char *, int, ...) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "open");
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  if (writeish(flags) && !allowed(path)) return deny(path);
  return real(path, flags, mode);
}

int open64(const char *path, int flags, ...) {
  static int (*real)(const char *, int, ...) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "open64");
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  if (writeish(flags) && !allowed(path)) return deny(path);
  return real(path, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
  static int (*real)(int, const char *, int, ...) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "openat");
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list ap;
    va_start(ap, flags);
    mode = (mode_t)va_arg(ap, int);
    va_end(ap);
  }
  /* ⚠️ Resolve the dirfd instead of trusting it: a relative path under an
   * arbitrary dirfd used to skip the check entirely, so
   * `openat(dir, "../../sdcard/x", O_CREAT)` escaped the sandbox. */
  if (writeish(flags) && !allowed_at(dirfd, path)) return deny(path);
  return real(dirfd, path, flags, mode);
}

int creat(const char *path, mode_t mode) {
  static int (*real)(const char *, mode_t) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "creat");
  if (!allowed(path)) return deny(path);
  return real(path, mode);
}

FILE *fopen(const char *path, const char *mode) {
  static FILE *(*real)(const char *, const char *) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "fopen");
  if (mode && (strchr(mode, 'w') || strchr(mode, 'a') || strchr(mode, '+')) &&
      !allowed(path)) {
    deny(path);
    return NULL;
  }
  return real(path, mode);
}

FILE *fopen64(const char *path, const char *mode) {
  static FILE *(*real)(const char *, const char *) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "fopen64");
  if (mode && (strchr(mode, 'w') || strchr(mode, 'a') || strchr(mode, '+')) &&
      !allowed(path)) {
    deny(path);
    return NULL;
  }
  return real(path, mode);
}

WRAP_PATH(unlink)
WRAP_PATH(rmdir)

/* truncate takes (path, length) — not a single-arg path wrapper. */
int truncate(const char *path, off_t length) {
  static int (*real)(const char *, off_t) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "truncate");
  if (!allowed(path)) return deny(path);
  return real(path, length);
}

int truncate64(const char *path, off64_t length) {
  static int (*real)(const char *, off64_t) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "truncate64");
  if (!real) { /* no 64-bit variant: reuse the 32-bit one, same guard */
    if (!allowed(path)) return deny(path);
    extern int truncate(const char *, off_t);
    return truncate(path, (off_t)length);
  }
  if (!allowed(path)) return deny(path);
  return real(path, length);
}

int mkdir(const char *path, mode_t mode) {
  static int (*real)(const char *, mode_t) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "mkdir");
  if (!allowed(path)) return deny(path);
  return real(path, mode);
}

/* 🔴 `mkdirat` was MISSING, and it is the one toybox actually calls.
 *
 * Measured on-device (2026-09-26): with the mode set to `workspace-write` and
 * the cwd inside the workspace, `mkdir /data/user/0/<pkg>/files/ESCAPED` created
 * the directory **outside** the allowlist and returned 0, with no `[boo]` line
 * — while `touch` and `cp` to the same directory were correctly denied (they go
 * through `openat`). Android's toybox `mkdir` and `install -d` are `mkdirat`
 * clients, so the plain `mkdir` interposer below never saw them.
 *
 * This was a real escape, not a cosmetic gap, and it predates the cwd-aliasing
 * fix: the old library let it through too. Any future write-capable entry point
 * must be checked for an `*at` variant — bionic routes the `*at` form wherever a
 * dirfd is available, so interposing only the plain name silently under-covers.
 */
int mkdirat(int dirfd, const char *path, mode_t mode) {
  static int (*real)(int, const char *, mode_t) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "mkdirat");
  if (!allowed_at(dirfd, path)) return deny(path);
  return real(dirfd, path, mode);
}

int unlinkat(int dirfd, const char *path, int flags) {
  static int (*real)(int, const char *, int) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "unlinkat");
  if (!allowed_at(dirfd, path)) return deny(path);
  return real(dirfd, path, flags);
}

int rename(const char *from, const char *to) {
  static int (*real)(const char *, const char *) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "rename");
  if (!allowed(to)) return deny(to);
  if (!allowed(from)) return deny(from);
  return real(from, to);
}

/* Same `*at` coverage gap as `mkdirat` (see above): these mirror the plain
 * wrappers below, and bionic clients that hold a dirfd reach only the `*at`
 * form. `renameat2` is the RENAME_NOREPLACE/EXCHANGE flavour used by newer
 * toybox; it must not be left as a hole around `renameat`. */
int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
  static int (*real)(int, const char *, int, const char *) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "renameat");
  if (!allowed_at(newdirfd, newpath)) return deny(newpath);
  if (!allowed_at(olddirfd, oldpath)) return deny(oldpath);
  return real(olddirfd, oldpath, newdirfd, newpath);
}

int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
              unsigned int flags) {
  static int (*real)(int, const char *, int, const char *, unsigned int) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "renameat2");
  if (!real) { /* not exported: fall back to renameat, still guarded */
    if (!allowed_at(newdirfd, newpath)) return deny(newpath);
    if (!allowed_at(olddirfd, oldpath)) return deny(oldpath);
    errno = ENOSYS;
    return -1;
  }
  if (!allowed_at(newdirfd, newpath)) return deny(newpath);
  if (!allowed_at(olddirfd, oldpath)) return deny(oldpath);
  return real(olddirfd, oldpath, newdirfd, newpath, flags);
}

int symlink(const char *target, const char *linkpath) {
  static int (*real)(const char *, const char *) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "symlink");
  if (!allowed(linkpath)) return deny(linkpath);
  return real(target, linkpath);
}

int symlinkat(const char *target, int newdirfd, const char *linkpath) {
  static int (*real)(const char *, int, const char *) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "symlinkat");
  if (!allowed_at(newdirfd, linkpath)) return deny(linkpath);
  return real(target, newdirfd, linkpath);
}

int link(const char *from, const char *to) {
  static int (*real)(const char *, const char *) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "link");
  if (!allowed(to)) return deny(to);
  return real(from, to);
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
           int flags) {
  static int (*real)(int, const char *, int, const char *, int) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "linkat");
  if (!allowed_at(newdirfd, newpath)) return deny(newpath);
  return real(olddirfd, oldpath, newdirfd, newpath, flags);
}

int chmod(const char *path, mode_t mode) {
  static int (*real)(const char *, mode_t) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "chmod");
  if (!allowed(path)) return deny(path);
  return real(path, mode);
}

int fchmodat(int dirfd, const char *path, mode_t mode, int flags) {
  static int (*real)(int, const char *, mode_t, int) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "fchmodat");
  if (!allowed_at(dirfd, path)) return deny(path);
  return real(dirfd, path, mode, flags);
}

/* ── `access()` / `faccessat()` ────────────────────────────────────────────
 *
 * ⚠️ These are a *reconnaissance* channel, not a write. They were previously
 * unguarded, which let a sandboxed command map the real filesystem
 * (`access("/sdcard/DCIM", W_OK)`) even though it could not write there.
 * Reading is deliberately NOT restricted — those two functions cannot open
 * anything; blocking them entirely would break ordinary programs (`test -w`,
 * shell redirection checks) for no confinement benefit.
 *
 * Only the capability probe (`W_OK`) is denied, so the sandbox no longer
 * advertises writability it will refuse at `open()` time. */
int access(const char *path, int mode) {
  static int (*real)(const char *, int) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "access");
  if ((mode & W_OK) && !allowed(path)) return deny(path);
  return real(path, mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
  static int (*real)(int, const char *, int, int) = NULL;
  if (!real) real = dlsym(RTLD_NEXT, "faccessat");
  if ((mode & W_OK) && !allowed_at(dirfd, path)) return deny(path);
  return real(dirfd, path, mode, flags);
}
