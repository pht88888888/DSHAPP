package com.dsh.android.web

import android.content.Context

/**
 * 建当前 flavor 的 Web 内核宿主。
 *
 * ⚠️ 这个函数**每个 flavor 源集各有一份**（`src/system/.../WebHostFactory.kt` 与
 *   `src/gecko/.../WebHostFactory.kt`），函数签名必须一致 —— 它是两个内核版本
 *   对 `MainActivity` 的唯一接缝。同名类在两个源集里不会冲突：AGP 一次只编译
 *   一个 flavor 的源集。
 */
fun createWebHost(context: Context, callbacks: WebHostCallbacks): WebHost {
    // ⚠️ 必须在 `GeckoWebHost` 构造**之前**：它的 init 里就 `GeckoRuntime.create()`，
    //   而 Runtime 一起来就打开并锁住 cookies 库，此后再删必失败。
    //   放在这里而不是 `createRuntime()` 里，是因为那个分支只在 `getDefault()` 为
    //   null 时才走到 —— 实测它并不可靠（清理没生效，cookie 每次启动 +2 累积）。
    purgeAuthCookies(context)
    return GeckoWebHost(context, callbacks)
}
