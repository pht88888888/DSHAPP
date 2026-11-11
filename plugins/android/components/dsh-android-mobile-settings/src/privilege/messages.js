export const MP_PRIV_API = "/api/dsh-android/privileged";
export const MP_PRIV_NS = "dsh-android-mobile.privileged";
export const MP_PRIV_ZH = {
	nav: "特权权限",
	title: "特权权限（uid 2000 / root）",
	intro: "开启后，AI 可以在需要时以更高身份执行命令 —— 截图、模拟点击、卸载应用、改系统设置这些原本做不到的事。默认仍走 App 身份，要不要提权由 AI 自己判断。",
	statusTitle: "当前状态",
	statusOn: "已开启",
	statusOff: "未开启",
	statusUnknown: "检测中",
	recheck: "重新检测",
	checking: "检测中…",
	failed: "检测失败",
	rowRunner: "执行通道",
	rowKey: "ADB 密钥",
	rowIdentity: "实际身份",
	ready: "已就位",
	missing: "缺失",
	imported: "已导入（电脑密钥）",
	generated: "已生成（待配对）",
	paired: "已配对",
	keyOriginUnknown: "来源未知",
	notImported: "未导入",
	justImported: "刚刚导入",
	uidOk: "uid 2000",
	uidApp: "App 身份（未提权）",
	// 「固定」= 走 legacy 5555，它不依赖无线调试开关，也不会随重启换端口。
	chStable: "固定端口（关无线调试也不断）",
	chWireless: "无线调试（关掉开关会断）",
	bytes: "字节",
	guideTitle: "开启步骤",
	step1: "用数据线把手机连到电脑。首次连接时手机会弹「允许 USB 调试吗」，勾选「一律允许」再确定。",
	step2: "在电脑上打开终端，执行下面这条 —— 它让手机的 adbd 在 5555 端口监听。Android 10 每次重启手机后都要重做一次。",
	step3: "再执行下面这条，把电脑上已授权的密钥推到手机的共享目录（macOS / Linux 把 %USERPROFILE%\\.android\\adbkey 换成 ~/.android/adbkey）。",
	step4: "回到本页，点上面的「重新检测」。App 会把密钥搬进自己的私有目录，并立刻删掉共享目录里的中转文件。",
	cmd2: "adb tcpip 5555",
	cmd3: "adb push %USERPROFILE%\\.android\\adbkey /sdcard/dsh_adbkey",
	copy: "复制",
	copied: "已复制",
	copyFailed: "复制失败",
	notesTitle: "几点说明",
	note1: "密钥等同于这台设备的控制权。App 只在私有目录保存它，导入后立刻删除 /sdcard 上的中转文件。",
	note2: "卸载重装会清掉密钥，需要重新做一遍上面的步骤。",
	note3: "以特权身份执行的命令不经过沙箱 —— 该身份读不到 App 私有目录，写沙箱和 link 转译对它都不生效。",
	// ── 无线调试（Android 11+）：免电脑那条路 ──
	wlTitle: "无线调试（Android 11+，不用电脑）",
	wlIntro: "Android 11 起系统自带「无线调试」，可以让本应用自己拿到特权身份，全程不用数据线、不用电脑，重启手机也依然有效。首次需要配对一次。",
	wlStep1: "在本页点下面的「配对无线调试」按钮 —— 通知栏会出现一条常驻提示，告诉你接下来去哪。",
	wlStep2: "在系统「设置 → 开发者选项」里打开「无线调试」开关，再点进「使用配对码配对设备」。",
	wlStep3: "那个界面一打开，通知栏的提示就会自动变成带输入框的（本应用会一直等着，不用回本页重按）。下拉通知栏，在里面直接回复那 6 位配对码。",
	wlStep4: "配对成功后回到本页点「重新检测」，身份应变成 uid 2000。之后就一直有效，不必再配对。",
	wlPair: "配对无线调试",
	wlPairing: "正在等待…",
	// ⚠️ 这句话是本次体验修复的核心：告诉用户「不用回来重按」。
	//    原实现发现 8 秒没端口就报失败，用户刚在系统里找到那个按钮，
	//    通知栏已经说没找到了，只能回本页重按 —— 而重按还是同样结果。
	wlPairingHint: "已开始等待。请去系统「设置 → 开发者选项 → 无线调试」点开「使用配对码配对设备」，让那个界面保持显示 —— 本应用会一直等着（不会超时），端口一出现通知栏就会变成输入框。想中止请点上面的「取消配对」。",
	wlCancel: "取消配对",
	wlPairHint: "配对码由系统显示、只能人工输入 —— 这是 SPAKE2 协议的要求，任何应用（包括 Shizuku）都无法代填。",
	wlPort: "无线端口",
	wlPortNone: "未发现（无线调试可能没开）",
	wlNote: "与上面的电脑引导二选一即可。无线调试那条路更省事，但只在 Android 11 及以上可用；Android 10 只能用电脑引导。",
	wlTcp: "切到固定端口",
	wlTcpHint: "切到固定端口后就不依赖无线调试开关了 —— 关无线调试、关 Wi-Fi 都不断。重启手机后会自动重切，通常不用管。",
	wlNoBridge: "当前环境没有原生的配对入口（DSHAndroid 桥不可用），无法发起配对。",
	wlNoNotify: "配对全程靠通知栏交互（提示 → 输入配对码 → 取消），但本应用的通知被系统关掉了，配对无法进行。请到「系统设置 → 应用 → DSH → 通知」里打开通知后重试。",
	wlPortStale: "已失效（无线调试可能已关）",
	recommended: "推荐",
	// Android 10 上「无线调试」根本不存在，说清楚比让用户去系统设置里白找一遍好。
	wlUnsupported: "本机是 Android 10，系统里没有「无线调试」这个功能（它从 Android 11 才有）。请用下面的「电脑引导」，那是在这台设备上唯一可行的方式。",
	pcTitlePrimary: "开启引导（Android 10 用这条）",
	pcTitle: "改用电脑引导（Android 10 或没有无线调试时）",
	pcIntro: "如果系统里找不到「无线调试」（Android 10 及以下），或者无线调试一直配不上，可以插上数据线用电脑引导一次。",
	setupTitle: "开启引导（当前已就绪，一般不用看）",
	showDetails: "查看详情",
	hideDetails: "收起详情",
	nextAllGood: "已就绪，可以直接用了。",
	nextPairHint: "还差一步：点右边按钮完成无线调试配对。",
	nextSwitchHint: "建议切到固定端口，这样关掉无线调试开关也不会断。",
	nextChoose: "还没开启。按下面「无线调试」那几步走一遍即可，不用电脑。",
	nextChoosePc: "还没开启。按下面的「电脑引导」走一遍 —— 本机是 Android 10，只能用这种方式。",
	// 探测还没返回、但界面已过软截止（8 秒）时的提示。
	// ⚠️ 必须同时给出「不用等」和「能做什么」，不能只说「检测中」——
	//    真机反馈：首次安装时这里一直显示「检测中」且没有任何按钮可点。
	softPending: "还在探测（设备正在重启 adbd 时可能要几十秒）。你可以直接开始配对，不必等它。",
	softPendingPc: "还在探测（设备正在重启 adbd 时可能要几十秒）。你可以直接按下面的「电脑引导」开始，不必等它。",
	// ── 按失败原因给出的「下一步」──────────────────────────
	//
	// ⚠️ 判据是 runner 那条 `诊断: kind=…` 行（见宿主半的 `DIAG_KIND_RE`），
	//    **不是**去匹配中文文案 —— 文案改一个字分支就会静默失效。
	//    这里只覆盖「下一步动作明确不同」的几类；其余 kind（含 unknown、
	//    老版本 runner 没有这个字段）继续走上面的通用引导，行为与改动前一致。
	//
	// 这几类的共同点：用户光看「App 身份（未提权）」完全不知道该点哪里，
	// 而实际上每一类的正解都不一样。
	nextRevoked: "密钥的授权已被设备撤销（多半是点过「撤销 USB 调试授权」）。需要重新配对一次。",
	nextUnauthorized: "密钥还没被设备授权。点右边按钮走一次配对即可。",
	nextNoKey: "还没有 ADB 密钥。点右边按钮走一次配对即可，不用电脑。",
	nextNoKeyPc: "还没有 ADB 密钥。按下面的「电脑引导」走一遍 —— 本机是 Android 10，没有无线调试。",
	nextWirelessStale: "无线调试端口已失效（多半是关过无线调试或重启过）。重开一次无线调试，或直接点右边按钮切到固定端口。",
	nextNoChannel: "固定端口和无线调试都不可用。打开系统「无线调试」，或点右边按钮试一次。",
	nextAdbMissing: "内置的 adb 工具链缺失，无线调试这条路走不通。装一次 App 更新即可修好。",
	nextKeyBlocked: "密钥没被设备信任，为避免反复弹授权框已跳过无线调试。请重新配对。",
	nextTimeout: "探测超时了 —— 多半是 adbd 正在重启。等十几秒再点一次「重新检测」。",
	nextPair: "重新配对",

	// ── root（uid 0）────────────────────────────────────────
	// ⚠️ 这一块**必须存在**：`root` 工具只在检测到 root 时才注册，所以没有
	//   root 的设备上用户看不到它的任何痕迹，分不清「本机没有 root」与
	//   「有 root 但没检测到」。状态与原因都得摆出来。
	rootTitle: "root 权限（uid 0）",
	rootOn: "已开启",
	rootDenied: "未授权",
	rootAbsent: "无 root",
	rootUnknown: "未检测",
	rootRowState: "状态",
	rootRowDetail: "探测结论",
	rootRowAt: "检测时间",
	rootRecheck: "重新检测",
	rootChecking: "检测中…",
	// 「下一步」按三态各写一句 —— 三种情况下用户该做的事完全不同。
	// ⚠️ 还有第四种：结论是 GRANTED，但**本次启动没挂上**（用户是刚点完
	//   「重新检测」才授权成功的）—— 那句单独写，见 rootNextOnPendingReload。
	rootNextOn: "root 工具已注册，AI 可以直接以 uid 0 执行命令（读写 /data、改挂载、iptables 等）。",
	rootNextOnPendingReload: "已检测到 uid 0，但 root 工具要下次启动才会挂上（它是启动时挂载的，当前这个会话里还看不到）。请重启 App 后再用。",
	rootNextDenied: "本机有 su，但本应用没拿到 uid 0。请在 Magisk / SuperSU 之类的授权管理里给「DSH」授权，然后点「重新检测」。",
	rootNextAbsent: "本机没有 root（也没找到 su）。需要 root 的功能用不了，其余功能不受影响 —— 上方的 uid 2000 通道与之无关，照常可用。",
	rootNextUnknown: "还没检测过。点「重新检测」跑一次（首次可能会弹 root 授权框）。",
	// ⚠️ 这里是**纯文本**渲染（React 直接塞进 DOM），不能写 Markdown 标记 ——
	//   写 `**加粗**` 或反引号会原样显示出来（第一版就这么错了）。
	rootNote: "root 工具只在检测到 uid 0 时才注册。检测方式是真的跑一次 su -c id -u、看输出是不是 0，而不是查 su 文件在不在 —— 占位 su、装了 Magisk 但没授权、root 升级后失效这三种情况文件都在却提不了权。结果会缓存 24 小时。",
	rootDetailNever: "（无）",

	// ── Shizuku（uid 2000 的第二条来源，不依赖 adbd）────────────
	//
	// ⚠️ 这一块的存在理由，就是**修掉本页最大的那个坑**：内置的两条路
	//   （固定端口 5555 / 无线调试）都以「设备上有一个可连的 adbd」为前提，
	//   而 Android 10 及以下没有无线调试、`persist.adb.tcp.port` 又不可写，
	//   于是每次重启都得让用户插上电脑再跑一次 `adb tcpip 5555`。
	//   真机反馈原话：「他有线调试，按步骤走了，没成功，一切都跟没动一样」。
	//
	//   Shizuku 走 **binder**，不碰 adbd、不校验 adbkey、重启后依然有效 ——
	//   对那批用户这是唯一走得通的路。所以它排在三张路径卡的最前面。
	shizukuTitle: "Shizuku（不用电脑）",
	// 四种状态各自对应**完全不同的下一步**，所以文案必须分开。
	// ⚠️ 判据顺序是 装了 → 服务在跑 → 已授权，不能合并成一个布尔：
	//   「没装」要引导去下载，「装了没跑」要引导去打开它，
	//   「跑了没授权」才是点授权 —— 三者按钮完全不同。
	shizukuAbsent: "未安装",
	shizukuInstalled: "未启动",
	shizukuUngranted: "待授权",
	shizukuGranted: "已就绪",
	shizukuNextAbsent: "本机还没装 Shizuku。装上它就能免电脑开启特权身份。",
	shizukuNextStopped: "Shizuku 已安装，但服务没有运行。打开 Shizuku 应用并点「启动」，再回来。",
	shizukuNextUngranted: "Shizuku 服务已在运行。点右边按钮授权，在弹出的小窗口里点「允许」即可。",
	shizukuNextGranted: "Shizuku 通道已就绪（uid 2000）。这条通道不依赖 adbd，重启手机后依然有效。",
	// ⚠️ 授权成功后**不用重启 App**：runner 每次执行命令时才现连 Shizuku，
	//   所以这里只是让界面去重新探测一次，而不是要求用户重启。
	shizukuNextGrantedStale: "Shizuku 已授权，但上面的状态还是旧的。点右边按钮重新检测一次即可（不用重启应用）。",
	shizukuDownload: "下载 Shizuku",
	shizukuOpen: "打开 Shizuku",
	shizukuGrant: "授权",
	shizukuGranting: "等待授权…",
	shizukuRecheck: "重新检测",
	shizukuRowService: "服务",
	shizukuRowUid: "服务身份",
	shizukuRowVersion: "服务版本",
	shizukuServiceRunning: "运行中",
	shizukuServiceStopped: "未运行",
	shizukuServiceAbsent: "未安装",
	// Shizuku 的详细步骤（折起）。
	// ⚠️ 逐条拆成独立键，**不要**用数组：`locale` 字典的值是字符串，
	//   数组会被当成「取不到」而回落成键名原样显示（这个回落是静默的）。
	shizukuStep1: "装上 Shizuku 并打开它。",
	shizukuStep2: "在 Shizuku 里按它自己的提示启动服务 —— 通常要求先用电脑执行一次 adb（或用 root）。这一步只需做一次，之后每次开机它自己会起来。",
	shizukuStep3: "回到本页点「授权」，在弹出的系统窗口里点「允许」。",
	shizukuStep4: "回到本页点「重新检测」，上面的身份应变成 uid 2000。",
	shizukuNote: "Shizuku 只在启动服务那一次需要 adb（或者 root），之后它自己常驻，本应用随时可用 —— 这与「固定端口」那条路每次重启都要电脑重跑 adb tcpip 5555 完全不同。命令同样不经过沙箱。",
	shizukuNoBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法操作 Shizuku。",
	// 通道说明：已就绪时告诉用户走的是哪条。
	chShizuku: "Shizuku（不依赖 adbd，重启也有效）",

	// ── 三条路径的卡片标题（重做后统一结构）────────────────────
	pathTitle: "开启方式",
	pathIntro: "三条路任选一条即可，用哪条由本机支持情况决定 —— 卡片上的徽章就是它当前的状态。",
	pathReady: "可用",
	pathUnavailable: "不可用",
	pathTodo: "待配置",
	pathShowSteps: "查看步骤",
	pathHideSteps: "收起步骤",
	// Android 10 上「无线调试」这张卡**整张不渲染**（系统里根本没这个功能，
	// 渲染出来只会是一堆点不动的按钮）。这里只留一句说明。
	wlAbsentNote: "本机是 Android 10，系统里没有「无线调试」（它从 Android 11 才有），所以这条路不存在 —— 不必去设置里找。",

	// ── 标签页布局 ──────────────────────────────────────────
	// 三条通道各自一个标签，标签上的圆点就是它的状态 ——
	// 不必逐个点开才知道哪条通。
	tabAdb: "ADB",
	tabShizuku: "Shizuku",
	tabRoot: "root",
	// ⚠️ 只有 ADB 标签需要这段统摄说明：它下面有**两张**卡（无线调试 +
	//   电脑引导），而另外两个标签各只有一张、卡片自带说明。
	//   没有它，用户看到两张并列的卡不知道它们同属一条通道。
	adbIntro: "用内置的 adb 连上设备自己的 adbd，拿到 shell 身份执行命令。两条走法：Android 11+ 用「无线调试」配对，Android 10 及以下只能插电脑引导一次。",
	rootIntro: "root 是 uid 0，比 uid 2000 还高一层。与上面两条通道互不影响：没有 root 的设备，uid 2000 照常可用。",
};
export const MP_PRIV_EN = {
	nav: "Privileged access",
	title: "Privileged access (uid 2000 / root)",
	intro: "When enabled, the AI can run commands as a higher identity when it needs to — screenshots, synthetic taps, uninstalling apps, changing system settings. Everything still defaults to the app's own identity; the AI decides when to escalate.",
	statusTitle: "Current status",
	statusOn: "Enabled",
	statusOff: "Not enabled",
	statusUnknown: "Checking",
	recheck: "Re-check",
	checking: "Checking…",
	failed: "Check failed",
	rowRunner: "Execution channel",
	rowKey: "ADB key",
	rowIdentity: "Effective identity",
	ready: "In place",
	missing: "Missing",
	imported: "Imported (PC key)",
	generated: "Generated (pairing needed)",
	paired: "Paired",
	keyOriginUnknown: "Origin unknown",
	notImported: "Not imported",
	justImported: "just imported",
	uidOk: "uid 2000",
	uidApp: "App identity (not escalated)",
	chStable: "fixed port (survives wireless debugging off)",
	chWireless: "wireless debugging (breaks when toggled off)",
	bytes: "bytes",
	guideTitle: "How to enable it",
	step1: "Connect the phone to a computer with a USB cable. On first connect the phone asks \"Allow USB debugging?\" — tick \"Always allow\" and confirm.",
	step2: "Open a terminal on the computer and run the command below — it makes the phone's adbd listen on port 5555. On Android 10 you must redo this after every reboot.",
	step3: "Then run the command below to push the computer's already-authorized key to the phone's shared storage (on macOS / Linux replace %USERPROFILE%\\.android\\adbkey with ~/.android/adbkey).",
	step4: "Come back to this page and tap \"Re-check\" above. The app moves the key into its private directory and immediately deletes the hand-off file from shared storage.",
	cmd2: "adb tcpip 5555",
	cmd3: "adb push %USERPROFILE%\\.android\\adbkey /sdcard/dsh_adbkey",
	copy: "Copy",
	copied: "Copied",
	copyFailed: "Copy failed",
	notesTitle: "Things to know",
	note1: "The key is equivalent to control of this device. The app keeps it only in its private directory and deletes the /sdcard hand-off file right after importing.",
	note2: "Uninstalling the app wipes the key; you will need to repeat the steps above.",
	note3: "Commands run as the privileged identity bypass the sandbox — that identity cannot read the app's private directory, so the write sandbox and the link translation layer do not apply to it.",
	// ── Wireless debugging (Android 11+): the no-computer path ──
	wlTitle: "Wireless debugging (Android 11+, no computer)",
	wlIntro: "Android 11 and later ship \"Wireless debugging\", which lets this app obtain the privileged identity on its own — no cable, no computer, and it survives reboots. A one-time pairing is required.",
	wlStep1: "In system Settings → Developer options, turn on the \"Wireless debugging\" switch.",
	wlStep2: "Tap \"Pair device with pairing code\" — you will see a 6-digit code and a port. Leave that screen open, then come back here and tap the button below.",
	wlStep3: "A pairing notification appears in the notification shade. Pull it down and reply with the 6-digit code right in the notification (no need to switch back to this app).",
	wlStep4: "Once pairing succeeds, come back here and tap \"Re-check\" — the identity should become uid 2000. It stays valid from then on; no re-pairing needed.",
	wlPair: "Pair wireless debugging",
	wlCancel: "Cancel pairing",
	wlPairHint: "The pairing code is shown by the system and can only be typed by a human — that is a requirement of the SPAKE2 protocol, and no app (Shizuku included) can fill it in for you.",
	wlPort: "Wireless port",
	wlPortNone: "Not found (wireless debugging may be off)",
	wlNote: "Use either this or the computer-based guide above. Wireless debugging is easier but only exists on Android 11+; on Android 10 only the computer route works.",
	wlTcp: "Switch to fixed port",
	wlTcpHint: "After switching to a fixed port the channel no longer depends on the wireless debugging toggle — turning wireless debugging or Wi-Fi off will not break it. It re-switches automatically after a reboot.",
	wlNoBridge: "The native pairing entry point is unavailable in this environment (the DSHAndroid bridge is missing), so pairing cannot be started.",
	wlNoNotify: "Pairing is driven entirely from the notification shade (prompt → code entry → cancel), but this app's notifications are turned off, so pairing cannot proceed. Enable notifications under Settings → Apps → DSH → Notifications, then retry.",
	wlPortStale: "Stale (wireless debugging may be off)",
	recommended: "Recommended",
	wlUnsupported: "This device runs Android 10, which has no \"Wireless debugging\" — that feature starts at Android 11. Use the computer-based guide below; it is the only workable path on this device.",
	pcTitlePrimary: "Setup guide (use this on Android 10)",
	pcTitle: "Use the computer-based guide instead (Android 10, or no wireless debugging)",
	pcIntro: "If there is no \"Wireless debugging\" entry in system settings (Android 10 and below), or pairing keeps failing, connect a USB cable and run the computer-based guide once.",
	setupTitle: "Setup guide (already enabled — normally not needed)",
	showDetails: "Show details",
	hideDetails: "Hide details",
	nextAllGood: "Ready to use.",
	nextPairHint: "One step left: tap the button to finish wireless pairing.",
	nextSwitchHint: "Switching to a fixed port is recommended so turning wireless debugging off will not break it.",
	nextChoose: "Not enabled yet. Follow the \"Wireless debugging\" steps below — no computer needed.",
	nextChoosePc: "Not enabled yet. Follow the computer-based guide below — this device runs Android 10, so that is the only way.",
	// Probe hasn't returned but the UI passed its soft deadline (8s). Say both
	// "you don't have to wait" and "what you can do" — never just "Checking…".
	softPending: "Still probing (this can take a while when adbd is restarting). You can start pairing right away instead of waiting.",
	softPendingPc: "Still probing (this can take a while when adbd is restarting). You can follow the computer-based guide below instead of waiting.",
	// ── Cause-specific next steps (see the Chinese dictionary for the rationale) ──
	nextRevoked: "This key's authorisation was revoked by the device (usually via \"Revoke USB debugging authorisations\"). Pair once more.",
	nextUnauthorized: "The key has not been authorised by the device yet. Tap the button to pair once.",
	nextNoKey: "No ADB key yet. Tap the button to pair once — no computer needed.",
	nextNoKeyPc: "No ADB key yet. Follow the computer-based guide below — this device runs Android 10 and has no wireless debugging.",
	nextWirelessStale: "The wireless debugging port went stale (wireless debugging was turned off or restarted). Turn it back on, or tap the button to switch to the fixed port.",
	nextNoChannel: "Neither the fixed port nor wireless debugging is available. Turn on system \"Wireless debugging\", or tap the button to try a switch.",
	nextAdbMissing: "The bundled adb toolchain is missing, so the wireless route cannot work. Installing an app update fixes it.",
	nextKeyBlocked: "The key is not trusted by the device, so the wireless route was skipped to avoid repeated authorisation prompts. Pair again.",
	nextTimeout: "The probe timed out — adbd is most likely still restarting. Wait a few seconds and tap \"Re-check\" again.",
	nextPair: "Pair again",

	// ── root (uid 0) ──────────────────────────────────────
	// ⚠️ This block must exist: the `root` tool is only registered when root is
	//   detected, so on a non-rooted device the user sees no trace of it at all
	//   and cannot tell "this device has no root" from "root exists but was not
	//   detected". Both the state and the reason have to be visible.
	rootTitle: "root access (uid 0)",
	rootOn: "Enabled",
	rootDenied: "Not authorized",
	rootAbsent: "No root",
	rootUnknown: "Not checked",
	rootRowState: "Status",
	rootRowDetail: "Probe result",
	rootRowAt: "Checked at",
	rootRecheck: "Re-check",
	rootChecking: "Checking…",
	rootNextOn: "The root tool is registered — the AI can run commands as uid 0 directly (read/write /data, remount, iptables, and so on).",
	rootNextOnPendingReload: "uid 0 was detected, but the root tool is only mounted at startup — it is not available in this session yet. Restart the app to use it.",
	rootNextDenied: "This device has su, but the app did not obtain uid 0. Grant \"DSH\" root access in Magisk / SuperSU or similar, then tap \"Re-check\".",
	rootNextAbsent: "This device has no root (no su found either). Root-only features are unavailable; everything else is unaffected — the uid 2000 channel above is unrelated and still works.",
	rootNextUnknown: "Not checked yet. Tap \"Re-check\" to run a probe (the first one may show a root authorization dialog).",
	// ⚠️ Plain-text rendering (React puts this straight into the DOM) — no
	//   Markdown markers, they would show up literally.
	rootNote: "The root tool is registered only when uid 0 is detected. Detection actually runs su -c id -u and checks whether the output is 0, rather than looking for a su file — a placeholder su, Magisk installed but not authorized, and a root that broke after an upgrade all leave the file present while escalation fails. The result is cached for 24 hours.",
	rootDetailNever: "(none)",

	// ── Shizuku (second source of uid 2000, no adbd needed) ──
	// ⚠️ This block exists to fix the page's biggest trap: both built-in
	//   routes assume a reachable adbd. On Android 10 and below there is no
	//   wireless debugging and persist.adb.tcp.port is not writable, so every
	//   reboot needs the PC to re-run `adb tcpip 5555`. Shizuku goes over
	//   binder — no adbd, no adbkey check, survives reboots.
	shizukuTitle: "Shizuku (no computer needed)",
	// Four states, four different next steps. Never collapse them into one
	// boolean: "not installed" must lead to a download, "installed but
	// stopped" to opening the app, and only "running but unauthorized" to
	// the grant button.
	shizukuAbsent: "Not installed",
	shizukuInstalled: "Not running",
	shizukuUngranted: "Not authorized",
	shizukuGranted: "Ready",
	shizukuNextAbsent: "Shizuku is not installed on this device. Install it to enable the privileged identity without a computer.",
	shizukuNextStopped: "Shizuku is installed but its service is not running. Open the Shizuku app and tap Start, then come back.",
	shizukuNextUngranted: "The Shizuku service is running. Tap the button to authorize, then tap Allow in the dialog that appears.",
	shizukuNextGranted: "The Shizuku channel is ready (uid 2000). It does not depend on adbd and keeps working after a reboot.",
	shizukuNextGrantedStale: "Shizuku is authorized, but the status above is stale. Tap the button to re-check (no need to restart the app).",
	shizukuDownload: "Download Shizuku",
	shizukuOpen: "Open Shizuku",
	shizukuGrant: "Authorize",
	shizukuGranting: "Waiting for authorization…",
	shizukuRecheck: "Re-check",
	shizukuRowService: "Service",
	shizukuRowUid: "Service identity",
	shizukuRowVersion: "Service version",
	shizukuServiceRunning: "Running",
	shizukuServiceStopped: "Not running",
	shizukuServiceAbsent: "Not installed",
	shizukuStep1: "Install Shizuku and open it.",
	shizukuStep2: "Follow Shizuku's own instructions to start the service — usually it asks you to run adb once from a computer (or use root). This is needed only once; it starts itself on every boot afterwards.",
	shizukuStep3: "Come back to this page, tap Authorize, and tap Allow in the dialog that appears.",
	shizukuStep4: "Tap Re-check here — the identity should become uid 2000.",
	shizukuNote: "Shizuku needs adb (or root) only for the one-time service start; after that it stays resident and this app can use it anytime. That is unlike the fixed-port route, which needs the PC to re-run adb tcpip 5555 after every reboot. Commands still bypass the sandbox.",
	shizukuNoBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot control Shizuku.",
	chShizuku: "Shizuku (no adbd, survives reboots)",

	// ── Route cards (unified structure after the rework) ──
	pathTitle: "How to enable it",
	pathIntro: "Any one of the three routes is enough — which one applies depends on this device, and each card's badge shows its current state.",
	pathReady: "Available",
	pathUnavailable: "Unavailable",
	pathTodo: "Needs setup",
	pathShowSteps: "Show steps",
	pathHideSteps: "Hide steps",
	wlAbsentNote: "This device runs Android 10, which has no \"Wireless debugging\" (it starts at Android 11), so this route does not exist here — no need to look for it in settings.",

	// ── Tab layout ──────────────────────────────────────────
	tabAdb: "ADB",
	tabShizuku: "Shizuku",
	tabRoot: "root",
	adbIntro: "Connect to the device's own adbd with the bundled adb to run commands as shell. Two routes: pair over \"Wireless debugging\" on Android 11+, or a one-time PC bootstrap on Android 10 and below.",
	rootIntro: "root is uid 0, one level above uid 2000. It is independent of the two channels above: without root, uid 2000 still works.",
};

// 全内联样式：设置 dialog 里的既有教训是「匹配 dialog 的 CSS 规则会把弹窗
// 推出屏幕」，而 section 内容住在那个 dialog 里，所以这里不新增 CSS 规则。
