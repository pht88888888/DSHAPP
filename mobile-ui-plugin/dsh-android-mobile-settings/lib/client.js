window.__ModuleLoader__.load({
	id: "dsh-android-mobile-settings",
	factory: (require) => {
		"use strict";
		const MP_PRIV_API = "/api/dsh-android/privileged";
		const MP_PRIV_NS = "dsh-android-mobile.privileged";
		// 官方静态模块表（`dsh-web-frontend` 的 `staticModules`）里有 react，
		// 所以客户端半可以直接 require 它，不需要自己打包一份 React。
		//
		// ⚠️ **必须延迟到 apply() 再 require，不能在模块顶层**：本文件的 factory
		//    会在 `__ModuleLoader__` 的 **queue 模式**下就被调用（模块系统尚未 boot、
		//    静态模块表还没建立），那时 require 会抛。apply() 一定是 boot 之后跑的。
		// ⚠️ 用 try/catch 拿：拿不到就**只损失这一个分节**，绝不能让整个插件
		//    （含移动端 UI 适配）挂掉。
		let mpPrivReact = null;

		const MP_PRIV_ZH = {
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

		// ══════════════════════════════════════════════════════════════════
		// 悬浮气泡设置分节
		//
		// 需求：App 退到后台时浮一个圆形气泡，用户能看着 AI 干活、也能继续说话。
		// 场景是**设备自动化** —— AI 去操作别的 App 时用户必须离开本应用，
		// 否则完全看不见进展、也没法中途补一句话。
		//
		// 分节要解决三件事，缺一不可：
		//   ① 让用户知道有这么个东西（默认关，不主动开就没有）
		//   ② 把用户送到系统的「显示在其他应用上层」授权页 —— 这是**特殊权限**，
		//      `requestPermissions` 对它无效，只能手动开
		//   ③ 开关本身
		//
		// 状态由原生提供（`DSHAndroid.bubbleState()`）—— 开关在 SharedPreferences、
		// 授权在 Settings，两者都在原生侧，浏览器进程读不到。
		// ══════════════════════════════════════════════════════════════════
		const MP_BUBBLE_NS = "dsh-android-mobile.bubble";
		const MP_BUBBLE_ZH = {
			nav: "悬浮气泡",
			title: "悬浮气泡",
			intro: "退到后台后，屏幕上浮一个圆形气泡，实时显示 AI 在干什么，也能直接打字发消息。做设备自动化时用它盯着进度，不用来回切应用。",
			statusTitle: "当前状态",
			statusOn: "已开启",
			statusOff: "未开启",
			statusUnknown: "检测中",
			rowSwitch: "开关",
			rowPerm: "悬浮窗权限",
			on: "已开启",
			off: "已关闭",
			granted: "已授权",
			notGranted: "未授权",
			enable: "开启气泡",
			disable: "关闭气泡",
			grant: "去授权",
			recheck: "重新检测",
			nextNeedPerm: "还差一步：系统还没允许本应用显示在其他应用上层。点右边按钮去开。",
			nextNeedEnable: "权限已就绪，打开开关即可用。",
			nextAllGood: "已就绪。退到后台就能看到气泡。",
			howTitle: "怎么用",
			how1: "退到后台（按 Home 或切到别的应用）时气泡才出现 —— 在本应用里它不会碍事。",
			how2: "拖动可以挪位置，松手会自动吸附到最近的屏幕边缘，位置会记住。",
			how3: "点一下展开面板：上面是当前动作和最近输出，下面是输入框，打字点「发送」即可。",
			how4: "面板上「打开」回到本应用，「收起」退回圆形。",
			notesTitle: "几点说明",
			note1: "「显示在其他应用上层」是特殊权限，系统不允许应用自己弹窗申请，必须手动去系统设置里打开。",
			note2: "气泡靠前台服务推送的状态刷新，约每秒一次；不额外耗电（引擎本来就在跑）。",
			note3: "气泡里发消息走的是同一个官方输入框，会进入当前会话，和在本应用里打字完全一样。",
			noBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法控制气泡。",
			motionTitle: "甩动手感",
			motionIntro: "让气泡更像一只真的小宠物：甩一甩会飞、会撞墙弹、落地会压扁。",
			throwName: "拖拽抛掷",
			throwHint: "手指快速划过气泡再松手，它会朝那个方向飞出去。",
			shakeName: "晃动甩飞",
			shakeHint: "快速晃动手机，气泡就像被甩出去一样。不需要任何权限。",
			imeName: "键盘顶飞",
			imeHint: "键盘弹出时，气泡从屏幕下方「嗖」地飞上去，不会被挡住。",
			imeLocked: "不可用",
			imeLockedWhy: "这个功能要读系统的键盘状态，普通应用没这个权限。接入 Shizuku 或 root 后就能打开。",
			on2: "开",
			off2: "关",
			// ── 外观控制面板 ────────────────────────────────────────
			panelTitle: "外观与手感",
			panelIntro: "下面这些就是悬浮球支持的全部可调项，改完立刻生效。分组可以点开收起。",
			panelUnavailable: "读取当前外观失败（原生桥不可用）。",
			collapse: "收起",
			expand: "展开",
			autoColor: "自动",
			panelBgAuto: "透明",
			autoSize: "自动",
			neverHide: "不隐藏",
			grpState: "形态",
			grpLook: "尺寸与形状",
			grpColor: "配色",
			grpMotion: "动作与交互",
			grpAnim: "动画与指示",
			grpPanel: "展开面板",
			fState: "默认形态",
			stAuto: "自动",
			stCollapsed: "小窗",
			stExpanded: "大窗",
			fSize: "直径",
			fShape: "形状",
			shapeCircle: "圆形",
			shapeSquare: "方形",
			shapeRounded: "圆角",
			fCorner: "圆角半径",
			fOpacity: "整体不透明度",
			fBgOpacity: "背景不透明度",
			fAvatarOpacity: "头像不透明度",
			fAvatarScale: "头像缩放",
			fBorderWidth: "描边粗细",
			fBorder: "描边颜色",
			fBg: "背景主色",
			fBg2: "背景副色（渐变）",
			fEdgeSnap: "拖出屏幕贴边",
			fMoveEnabled: "自己会走动",
			fMoveInterval: "走动间隔",
			fMoveMin: "单次最少走",
			fMoveMax: "单次最多走",
			fFlipOnTurn: "转向时左右翻转",
			fFrameInterval: "帧间隔",
			fSpin: "常驻旋转角度",
			fPulse: "呼吸脉动",
			fShowDot: "显示状态小圆点",
			fStatusImageSize: "状态配图尺寸",
			fPanelWidth: "面板宽度",
			fPanelHeight: "面板高度",
			fStatusAutoHide: "状态条自动隐藏",
			fPanelBg: "面板背景色",
			fPanelText: "面板文字色",
			fPanelAccent: "面板强调色",

			grpBalance: "余额徽标",
			bbEnabledHint: "在球旁边常驻显示 DeepSeek 余额。关掉就完全不占位置。",
			fBbEnabled: "显示余额徽标",
			fBbAnchor: "出现位置",
			fBbTemplate: "显示内容",
			fBbGap: "与球的距离",
			fBbFontSize: "字号",
			fBbRadius: "圆角",
			fBbPaddingH: "左右内边距",
			fBbPaddingV: "上下内边距",
			fBbMaxWidth: "最大宽度",
			fBbBorderWidth: "描边粗细",
			fBbBg: "背景色",
			fBbFg: "文字色",
			fBbBorder: "描边色",
			fBbCostFlash: "花钱时跳动",
			fBbHideIdle: "只在有花费时显示",
			fBbTurnEnd: "每轮结束刷新",
			fBbEvery: "定时刷新",
			fBbTapRefresh: "点球刷新",
			fBbTapMin: "两次点击的最短间隔",
			bbFollow: "跟随主题",
			bbTokenHint: "点下面的词可以加进去",
			bbPreviewHint: "球旁边会显示成这样",
			bbRemove: "移除",
			bbEmpty: "（还没有内容）",
			bbReset: "恢复默认",
			bbTokBalance: "余额",
			bbTokRecharge: "充值",
			bbTokBonus: "赠送",
			bbTokCurrency: "币种",
			bbTokCost: "本次",
			bbTokCostSession: "本会话",
			bbOff: "关闭",
			bbMinute: " 分钟",
			bbAnchorTR: "右上",
			bbAnchorTL: "左上",
			bbAnchorBR: "右下",
			bbAnchorBL: "左下",
			bbAnchorAbove: "正上",
			bbAnchorBelow: "正下",
			kbTitle: "键盘避让",
			kbRowSwitch: "键盘避让",
			kbRowPerm: "无障碍权限",
			kbGrant: "去开启权限",
			kbNextDisabled: "当前关着。打开后，键盘弹出时悬浮球会自动避让，不会挡在输入区前面。",
			kbNextNeedPerm: "还差一步：需要「无障碍」权限才知道键盘弹没弹出、弹多高。点右边按钮去系统设置里打开「DSH 键盘哨兵」。",
			kbNextAllGood: "已就绪：键盘弹出时悬浮球会被弹开，落回键盘上方。",
			kbNextShellSlow: "能用，但走的是特权通道（dumpsys 轮询，延迟约 2 秒）。开了无障碍会快一个数量级，键盘一弹就避让。",
			kbNextNoSource: "两条检测路径都不可用：没开无障碍，也没有 Shizuku 或 root。下面任选一条开通后才能启用。",
			kbRowSource: "检测方式",
			kbSourceA11y: "无障碍（快）",
			kbSourceShell: "特权通道（慢）",
			kbSourceNone: "无",
			kbHowTitle: "为什么需要无障碍",
			kbHow1: "普通应用拿不到「键盘是否弹出、弹多高」—— 只有无障碍服务能读输入法窗口的位置和尺寸。",
			kbHow2: "它只读取窗口的位置与大小，不读取窗口内容，也不记录按键或你输入的文字。",
			kbHow3: "开启后悬浮球会在键盘弹出时被弹开、落回键盘上方；关掉开关则完全不干预。",
		};
		const MP_BUBBLE_EN = {
			nav: "Floating bubble",
			title: "Floating bubble",
			intro: "When the app goes to the background, a round bubble floats on screen showing what the AI is doing, with a text box to send messages. Useful for device automation — watch progress without switching apps.",
			statusTitle: "Current status",
			statusOn: "Enabled",
			statusOff: "Not enabled",
			statusUnknown: "Checking",
			rowSwitch: "Switch",
			rowPerm: "Overlay permission",
			on: "On",
			off: "Off",
			granted: "Granted",
			notGranted: "Not granted",
			enable: "Turn on",
			disable: "Turn off",
			grant: "Grant",
			recheck: "Re-check",
			nextNeedPerm: "One step left: the system hasn't allowed this app to draw over other apps. Tap the button to open the setting.",
			nextNeedEnable: "Permission is ready — just turn the switch on.",
			nextAllGood: "All set. Send the app to the background to see the bubble.",
			howTitle: "How it works",
			how1: "The bubble appears only when the app is in the background — it never gets in your way inside the app.",
			how2: "Drag to move it; on release it snaps to the nearest screen edge and remembers the position.",
			how3: "Tap to expand: current action and recent output on top, a text box and Send below.",
			how4: "\"Open\" returns to the app; \"Collapse\" goes back to the round bubble.",
			notesTitle: "Notes",
			note1: "\"Display over other apps\" is a special permission — Android does not allow an app to request it at runtime; you must enable it in system settings.",
			note2: "The bubble is fed by the foreground service's status, about once per second. No extra battery cost — the engine is already running.",
			note3: "Messages sent from the bubble go through the same official composer and land in the current session, exactly as if you typed them in the app.",
			noBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot control the bubble.",
			motionTitle: "Throw feel",
			motionIntro: "Make the bubble behave like a real little pet: fling it and it flies, bounces off walls, and squashes on landing.",
			throwName: "Drag throw",
			throwHint: "Flick your finger across the bubble and release — it flies off in that direction.",
			shakeName: "Shake to throw",
			shakeHint: "Shake the phone and the bubble gets flung, as if thrown by hand. No permission needed.",
			imeName: "Keyboard launch",
			imeHint: "When the keyboard opens, the bubble shoots up from the bottom so it never gets covered.",
			imeLocked: "Unavailable",
			imeLockedWhy: "This needs to read the system keyboard state, which normal apps cannot access. Enable Shizuku or root to turn it on.",
			on2: "On",
			off2: "Off",
			panelTitle: "Look & feel",
			panelIntro: "Everything the bubble supports, tunable here. Changes apply instantly. Tap a group to expand or collapse it.",
			panelUnavailable: "Could not read the current appearance (native bridge unavailable).",
			collapse: "Collapse",
			expand: "Expand",
			autoColor: "Auto",
			panelBgAuto: "Transparent",
			autoSize: "Auto",
			neverHide: "Never",
			grpState: "Form",
			grpLook: "Size & shape",
			grpColor: "Colors",
			grpMotion: "Motion & interaction",
			grpAnim: "Animation & indicator",
			grpPanel: "Expanded panel",
			fState: "Default form",
			stAuto: "Auto",
			stCollapsed: "Collapsed",
			stExpanded: "Expanded",
			fSize: "Diameter",
			fShape: "Shape",
			shapeCircle: "Circle",
			shapeSquare: "Square",
			shapeRounded: "Rounded",
			fCorner: "Corner radius",
			fOpacity: "Overall opacity",
			fBgOpacity: "Background opacity",
			fAvatarOpacity: "Avatar opacity",
			fAvatarScale: "Avatar scale",
			fBorderWidth: "Border width",
			fBorder: "Border color",
			fBg: "Background color",
			fBg2: "Gradient color",
			fEdgeSnap: "Dock to edge",
			fMoveEnabled: "Wanders on its own",
			fMoveInterval: "Wander interval",
			fMoveMin: "Min step",
			fMoveMax: "Max step",
			fFlipOnTurn: "Flip when turning",
			fFrameInterval: "Frame interval",
			fSpin: "Constant rotation",
			fPulse: "Breathing pulse",
			fShowDot: "Show status dot",
			fStatusImageSize: "Status image size",
			fPanelWidth: "Panel width",
			fPanelHeight: "Panel height",
			fStatusAutoHide: "Status bar auto-hide",
			fPanelBg: "Panel background",
			fPanelText: "Panel text",
			fPanelAccent: "Panel accent",

			grpBalance: "Balance badge",
			bbEnabledHint: "Show your DeepSeek balance next to the ball. Off = takes no space at all.",
			fBbEnabled: "Show balance badge",
			fBbAnchor: "Position",
			fBbTemplate: "Text",
			fBbGap: "Distance from ball",
			fBbFontSize: "Font size",
			fBbRadius: "Corner radius",
			fBbPaddingH: "Side padding",
			fBbPaddingV: "Top/bottom padding",
			fBbMaxWidth: "Max width",
			fBbBorderWidth: "Border width",
			fBbBg: "Background",
			fBbFg: "Text colour",
			fBbBorder: "Border colour",
			fBbCostFlash: "Pulse when spending",
			fBbHideIdle: "Only when it cost something",
			fBbTurnEnd: "Refresh after each turn",
			fBbEvery: "Timed refresh",
			fBbTapRefresh: "Refresh on tap",
			fBbTapMin: "Min gap between taps",
			bbFollow: "Theme",
			bbTokenHint: "Tap a field to add it",
			bbPreviewHint: "It will look like this next to the ball",
			bbRemove: "Remove",
			bbEmpty: "(empty)",
			bbReset: "Reset",
			bbTokBalance: "Balance",
			bbTokRecharge: "Recharge",
			bbTokBonus: "Bonus",
			bbTokCurrency: "Currency",
			bbTokCost: "This turn",
			bbTokCostSession: "This session",
			bbOff: "Off",
			bbMinute: " min",
			bbAnchorTR: "Top right",
			bbAnchorTL: "Top left",
			bbAnchorBR: "Bottom right",
			bbAnchorBL: "Bottom left",
			bbAnchorAbove: "Above",
			bbAnchorBelow: "Below",
			kbTitle: "Keyboard avoidance",
			kbRowSwitch: "Keyboard avoidance",
			kbRowPerm: "Accessibility permission",
			kbGrant: "Grant",
			kbNextDisabled: "Currently off. Turn it on and the bubble will get out of the keyboard's way when it appears.",
			kbNextNeedPerm: "One step left: \"Accessibility\" is required to know whether the keyboard is up and how tall it is. Tap the button to enable \"DSH Keyboard Sentinel\" in system settings.",
			kbNextAllGood: "Ready: the bubble is pushed away when the keyboard appears and lands above it.",
			kbNextShellSlow: "Works, but via the privileged channel (dumpsys polling, ~2s latency). Enabling accessibility makes it an order of magnitude faster.",
			kbNextNoSource: "Neither detection path is available: accessibility is off and there is no Shizuku or root. Enable one of them below first.",
			kbRowSource: "Detection",
			kbSourceA11y: "Accessibility (fast)",
			kbSourceShell: "Privileged (slow)",
			kbSourceNone: "None",
			kbHowTitle: "Why accessibility",
			kbHow1: "A normal app cannot know whether the keyboard is shown or how tall it is — only an accessibility service can read the IME window's position and size.",
			kbHow2: "It reads window position and size only; no window content, no keystrokes, no typed text.",
			kbHow3: "When enabled, the bubble bounces off the keyboard and rests above it; when disabled, it is left completely alone.",
		};

		const MP_PRIV_EN = {
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
		const MP_PRIV_S = {
			section: { maxWidth: "760px", display: "flex", flexDirection: "column", gap: "12px", color: "var(--dsw-alias-label-primary, #222)" },
			heading: { margin: 0, fontSize: "18px", fontWeight: 600 },
			intro: { margin: 0, fontSize: "13px", lineHeight: "20px", color: "var(--dsw-alias-label-tertiary, #888)" },
			card: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", padding: "12px 14px", display: "flex", flexDirection: "column", gap: "10px" },
			cardHead: { display: "flex", alignItems: "center", gap: "8px" },
			cardTitle: { margin: 0, fontSize: "15px", fontWeight: 600 },
			badge: { marginLeft: "auto", fontSize: "12px", fontWeight: 600, padding: "3px 9px", borderRadius: "999px", whiteSpace: "nowrap" },
			btn: { border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "6px 14px", borderRadius: "8px", cursor: "pointer" },
			row: { display: "flex", alignItems: "baseline", gap: "8px", fontSize: "13px", lineHeight: "19px" },
			dot: { flex: "0 0 auto", width: "7px", height: "7px", borderRadius: "50%", transform: "translateY(-1px)" },
			rowLabel: { flex: "0 0 auto", color: "var(--dsw-alias-label-tertiary, #888)" },
			rowValue: { flex: "1 1 auto", minWidth: 0, wordBreak: "break-word" },
			steps: { margin: 0, padding: "0 0 0 20px", display: "flex", flexDirection: "column", gap: "9px" },
			step: { fontSize: "13px", lineHeight: "20px" },
			// 命令块：独立成行 + 可横向滚动。窄屏上命令很长，
			// 换行会把路径断在中间导致抄错，所以宁可横向滚。
			// ⚠️ 这里**刻意不**设 user-select：本插件的全局 CSS 是
			//   `[data-mobile] * { user-select: none !important }`，内联会被压掉，
			//   写了也是死代码。**正因如此才必须有复制按钮** —— 命令长按选不中，
			//   而这台设备上用户是在电脑终端里敲，只能照着手机屏幕抄。
			cmdRow: { display: "flex", alignItems: "stretch", gap: "6px", marginTop: "6px" },
			cmd: { flex: "1 1 auto", minWidth: 0, display: "block", padding: "7px 9px", borderRadius: "6px", background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)", fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "12px", lineHeight: "18px", overflowX: "auto", whiteSpace: "pre" },
			// 固定宽度：文案在「复制 / 已复制 / 复制失败」之间切换，
			// 不固定的话按钮会随字数变宽，把左边的命令块挤得来回跳。
			cmdBtn: { flex: "0 0 auto", display: "flex", alignItems: "center", justifyContent: "center", minWidth: "62px", border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", background: "transparent", color: "inherit", font: "inherit", fontSize: "12px", padding: "0 8px", borderRadius: "6px", cursor: "pointer", whiteSpace: "nowrap" },
			notes: { margin: 0, padding: "0 0 0 18px", display: "flex", flexDirection: "column", gap: "6px" },
			note: { fontSize: "12px", lineHeight: "18px", color: "var(--dsw-alias-label-tertiary, #888)" },
			error: { margin: 0, fontSize: "12px", lineHeight: "18px", color: "#c92a2a" },

			// ── 重设计后新增 ────────────────────────────────────────
			// 首屏「下一步」块：把「点哪里」摆在结论正下方，用户不必自己从
			// 三行技术明细里翻译出该做什么。
			stack: { display: "flex", flexDirection: "column", gap: "12px" },
			summary: { margin: 0, fontSize: "13px", lineHeight: "19px" },
			next: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", padding: "9px 10px", borderRadius: "8px", background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)" },
			nextText: { flex: "1 1 160px", minWidth: 0, fontSize: "13px", lineHeight: "19px" },			// root 卡片专用的「下一步」文案样式：与上面那条的区别只有换行基线。
			//
			// ⚠️ **不复用 `nextText`**：它的 `flex: "1 1 160px"` 在 360 CSS px 宽的
			//   卡片里会把长文案挤成每行十来个字的一窄列（root 那三条文案都挺长）。
			//   用 100% 作基线则文案先占满整行、按钮自然掉到下一行，读起来才是一段话。
			//   之所以单独开一条而不是改上面那条：上面那条是 uid 2000 卡片在用的，
			//   改它会连带改掉那张已经定型的卡片 —— 只修自己新加的那张。
			nextTextWide: { flex: "1 1 100%", minWidth: 0, fontSize: "13px", lineHeight: "19px" },
			actions: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
			// 主按钮：整页只有「配对」和「下一步」用实心，避免到处都在喊。
			btnPrimary: { border: "0.5px solid transparent", background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", color: "#fff", font: "inherit", fontSize: "13px", fontWeight: 600, padding: "6px 14px", borderRadius: "8px", cursor: "pointer" },
			// 次要动作（取消配对 / 查看详情）用弱化样式，和主按钮拉开层级。
			btnQuiet: { border: "0.5px solid transparent", background: "transparent", color: "var(--dsw-alias-label-tertiary, #888)", font: "inherit", fontSize: "13px", padding: "6px 10px", borderRadius: "8px", cursor: "pointer" },
			details: { display: "flex", flexDirection: "column", gap: "8px", paddingTop: "4px", borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)" },
			// 折叠块：引导与注意事项默认收起，让首屏只剩结论。
			fold: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", padding: "10px 14px" },
			foldHead: { fontSize: "14px", fontWeight: 600, cursor: "pointer" },
			foldBody: { display: "flex", flexDirection: "column", gap: "12px", paddingTop: "10px" },
			// ⚠️ 这里**不能**引用 `MP_PRIV_TONE` —— 它定义在本对象**之后**，
			// 在模块求值期引用会抛 `Cannot access 'MP_PRIV_TONE' before
			// initialization`（整个插件 import 失败、界面直接白屏，踩过一次）。
			// 所以颜色写死，与 MP_PRIV_TONE.on 保持一致。
			tagRecommended: { marginLeft: "auto", fontSize: "11px", fontWeight: 600, padding: "2px 8px", borderRadius: "999px", color: "#2f9e44", background: "rgba(47,158,68,0.12)", whiteSpace: "nowrap" },

			// ── 布局重做新增 ────────────────────────────────────────
			// 三条路径之间的分组标题。比 `heading`（h2，18px）小一号 ——
			// 它是分节内部的次级标题，与分节标题同分量会让层级糊掉。
			groupTitle: { margin: "4px 0 0", fontSize: "15px", fontWeight: 600 },
			// 卡片**内部**的折叠块：与顶层 `fold`（自己有边框、独立成块）区分开 ——
			// 卡片里再套一个带边框的块会出现「框里套框」的双线，看着很脏。
			// 这里只用一条上分隔线。
			foldInline: { borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)", paddingTop: "8px" },

			// ── 标签页（ADB / Shizuku / root）───────────────────────
			//
			// 下划线式标签：底边一条通栏分隔线，选中项盖一条 2px 主色指示条。
			// ⚠️ 指示条靠 `marginBottom: "-0.5px"` **压在**那条分隔线上 —— 不压的话
			//   选中项的下划线会比容器底边高半个像素，视觉上「浮」起来。
			//   这是纯 CSS 手法，不需要额外的定位容器。
			tabBar: {
				display: "flex", alignItems: "stretch", gap: "2px",
				borderBottom: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)",
			},
			tab: {
				flex: "1 1 0", minWidth: 0,
				display: "flex", alignItems: "center", justifyContent: "center", gap: "6px",
				border: "none", background: "transparent",
				color: "var(--dsw-alias-label-tertiary, #888)",
				font: "inherit", fontSize: "14px",
				padding: "9px 4px 10px", cursor: "pointer",
				borderBottom: "2px solid transparent", marginBottom: "-0.5px",
			},
			tabActive: {
				color: "var(--dsw-alias-label-primary, #222)", fontWeight: 600,
				borderBottom: "2px solid var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
			},
			// 标签上的状态点：进页面之前就能看出哪条通道是通的，不必逐个点开。
			tabDot: { flex: "0 0 auto", width: "7px", height: "7px", borderRadius: "50%" },
			// 窄屏上「Shizuku」比「ADB」宽得多，不省略的话三个标签宽度会被撑歪。
			tabLabel: { minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
			// 标签页内容区：与常驻结论条拉开一点距离，让「上面是总览、下面是当前页」成立。
			panel: { display: "flex", flexDirection: "column", gap: "12px", paddingTop: "2px" },

			// ── 「交流群」分节 ──────────────────────────────────────
			// 二维码容器：居中 + 一点上下留白，避免图片紧贴卡片边框。
			qrWrap: { display: "flex", justifyContent: "center", padding: "4px 0 2px" },
			// 显示尺寸取 240px 且不超过卡片宽度。
			// ⚠️ 内联源图是 640px 的 WebP，缩到 240 CSS px 显示。实测（dpr=3 渲染
			//   截图后交给 zxing 解码）400px 的源图就已够扫，640px 是留了余量：
			//   用户可能会用另一台设备对着屏幕拍，拍摄角度/对焦都会损失有效分辨率。
			// ⚠️ 不设 `imageRendering: "pixelated"`：那是为放大低分辨率图准备的，
			//   这里是从 640 缩到 240（缩小），平滑重采样反而更利于扫描识别。
			qrImg: { width: "240px", maxWidth: "100%", height: "auto", display: "block", borderRadius: "8px", background: "#fff" },
			// 群号用等宽字体且**放大一号**：这是要照着念/照着手输的数字，
			// 与周围的说明文字同等字号会显得不够醒目。
			mpGroupNo: { flex: "1 1 auto", minWidth: 0, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "15px", fontWeight: 600, letterSpacing: "0.5px" },
			// ── 「甩动手感」开关行 ───────────────────────────────────
			// 一行一个开关：左边名字+说明，右边一枚自绘开关。
			// ⚠️ 不用 <input type="checkbox">：本插件的全局 CSS 会把原生控件压成
			//   系统默认样式，在深色主题下几乎看不见。自绘只用两个 div。
			swRow: { display: "flex", alignItems: "flex-start", gap: "12px", padding: "10px 0", borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)" },
			swMain: { flex: "1 1 auto", minWidth: 0, display: "flex", flexDirection: "column", gap: "3px" },
			swName: { fontSize: "14px", fontWeight: 600 }, 
			swHint: { fontSize: "12px", lineHeight: "18px", color: "var(--dsw-alias-label-tertiary, #888)" },
			swWhy: { fontSize: "12px", lineHeight: "18px", color: "#c92a2a" },
			// 轨道：关=灰、开=主色。宽高固定，thumb 用绝对定位左右滑。
			swTrack: { flex: "0 0 auto", position: "relative", width: "44px", height: "26px", borderRadius: "999px", border: "none", padding: 0, cursor: "pointer", transition: "background 0.18s ease", marginTop: "2px" },
			swThumb: { position: "absolute", top: "3px", width: "20px", height: "20px", borderRadius: "50%", background: "#fff", boxShadow: "0 1px 3px rgba(0,0,0,0.28)", transition: "left 0.18s ease" },
			// ── 「外观控制面板」用的控件 ─────────────────────────────
			// 分组标题：比 cardTitle 轻一档，用来在长面板里切段。
			grpTitle: { margin: "14px 0 2px", fontSize: "13px", fontWeight: 700, letterSpacing: "0.4px", color: "var(--dsw-alias-label-tertiary, #888)", textTransform: "uppercase" },
			// 一行控件：左标签 + 右值。比 swRow 紧凑（无下边框），因为同类行会连排。
			// ⚠️ 标签列 116px（不是 104px）：本页最长的标签是「显示余额徽标」6 个汉字，
			//   14px 字号下约需 84px，加上行内 padding 与 gap，104px 会被 `ellipsis`
			//   截成「显示余额徽标…」。给到 116px 刚好放下，又不至于挤压右侧控件。
			ctlRow: { display: "grid", gridTemplateColumns: "116px 1fr auto", alignItems: "center", gap: "10px", padding: "7px 0" },
			ctlLabel: { minWidth: 0, fontSize: "14px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
			ctlValue: { flex: "0 0 auto", fontSize: "13px", fontVariantNumeric: "tabular-nums", color: "var(--dsw-alias-label-tertiary, #888)", minWidth: "46px", textAlign: "right" },
			// 滑块：原生 range，但只保留轨道与滑块，颜色跟主题走。
			// ⚠️ accent-color 是关键：不设的话安卓 WebView 会画成系统蓝，与主题撞色。
			slider: { flex: "1 1 0", minWidth: 0, accentColor: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", height: "28px", margin: 0 },
			// 分段选择器：等宽按钮排一行，选中项反色。比 <select> 好按，也比它好看。
			/**
			 * 分段选择器。
			 *
			 * ⚠️ **不能用 "whiteSpace: nowrap" + 等分平分**：本页的「出现位置」有 6 个
			 *   中文选项，等分后每格只有约 48px，而「右上」「右下」这类两字标签在
			 *   13px 字号下需要约 34px + 左右内边距，于是全部被 "ellipsis" 截成
			 *   「右…」「左…」—— 六个按钮长得一模一样，完全没法选。
			 *
			 *   改为：**允许换行**（"flexWrap"）+ 按钮按内容宽度（"0 0 auto"），
			 *   放不下就折到第二行。宁可多占一行，也不能让文字读不出来。
			 */
			seg: { display: "flex", flexWrap: "wrap", flex: "1 1 0", minWidth: 0, gap: "6px" },
			segBtn: { flex: "0 0 auto", minWidth: 0, border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", textAlign: "center", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "6px 11px", borderRadius: "8px", cursor: "pointer", whiteSpace: "nowrap" },
			segBtnOn: { background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", borderColor: "transparent", color: "#fff", fontWeight: 600 },
			// 颜色选择：圆形色块 + 原生取色器叠在上面（透明点击层）。
			// ⚠️ 不用 type=color 直接可见：它渲染出来是个方块，跟整页的圆角风格不搭。
			/**
			 * 色块圆点。
			 *
			 * ⚠️⚠️ **"position: relative" 与固定宽高必须留在这一层**，因为里面的
			 *   "<input type="color">" 是 "position: absolute; inset: 0"，它靠这个
			 *   元素建立包含块。
			 *
			 * 踩过：真机上这三个色块的 input 尺寸变成了 **360×764（整个面板）**，
			 * 于是透明的 input 盖住了下面所有行 ——
			 *   ① 页面无法上下滑动（滑动事件被它吃掉）；
			 *   ② 点分组标题「收起」时命中的是它，弹出系统颜色选择器，分组永远收不起来。
			 * 根因就是包含块没建立起来时 "inset: 0" 会退回最近的可定位祖先（= 整个面板）。
			 *
			 * ⚠️ 另外补 "display: block" + "boxSizing: border-box"：这两个色块在
			 *   "display: grid" 的 "ctlRow" 里，"flex: 0 0 auto" 对 grid 子项**无效**，
			 *   不加的话 span 会按内容宽度撑开，"width/height" 反而不稳。
			 */
			swatchWrap: {
				position: "relative",
				display: "block",
				flex: "0 0 auto",
				width: "30px",
				height: "30px",
				boxSizing: "border-box",
				borderRadius: "50%",
				border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
				overflow: "hidden",
			},
			swatchInput: {
				position: "absolute",
				left: 0,
				top: 0,
				width: "100%",
				height: "100%",
				// ⚠️ 不要用 `inset: 0` —— 旧内核（本 App 目标 chrome74）对 `inset`
				//   简写的支持不完整，退化成不定位时就会铺满整个可定位祖先。
				//   显式写 left/top/width/height 最稳。
				opacity: 0,
				border: "none",
				padding: 0,
				margin: 0,
				cursor: "pointer",
			},
			// 数字输入：比滑块精确，用于尺寸这类要打准的值。
			numInput: { flex: "0 0 auto", width: "64px", border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", borderRadius: "6px", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "5px 7px", textAlign: "right" },
			// 可折叠分组：长面板必须能折叠，否则一屏塞不下、用户永远滚不到底。
			acc: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", marginTop: "10px", overflow: "hidden" },
			accHead: { display: "flex", alignItems: "center", gap: "8px", padding: "11px 14px", cursor: "pointer", fontSize: "14px", fontWeight: 600, background: "var(--dsw-alias-interactive-bg-hover, #fafafa)" },
			// ── 余额徽标「显示内容」的编辑器 ──────────────────────────────
			//
			// 三段式：预览 → 当前配方（可删）→ 可加的词（已用则禁用）。
			bbEditor: { display: "flex", flexDirection: "column", gap: "8px", padding: "8px 0 4px" },
			bbPreviewRow: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" },
			// 预览用的"假徽标"：故意做得和真实徽标同形（深底浅字、圆角胶囊），
			// 让用户一眼认出"这就是球旁边那个东西"。
			bbPreviewBadge: {
				background: "rgba(28, 30, 34, 0.92)",
				color: "#fff",
				fontSize: "13px",
				padding: "3px 10px",
				borderRadius: "999px",
				fontVariantNumeric: "tabular-nums",
				whiteSpace: "nowrap",
				maxWidth: "100%",
				overflow: "hidden",
				textOverflow: "ellipsis",
			},
			bbTokenRow: { display: "flex", flexWrap: "wrap", gap: "6px", alignItems: "center" },
			// 「可加的词」：浅底、有点击感。
			bbToken: {
				border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
				background: "var(--dsw-alias-bg-layer-2, #f4f4f4)",
				color: "var(--dsw-alias-label-primary, #333)",
				font: "inherit",
				fontSize: "12px",
				padding: "4px 10px",
				borderRadius: "999px",
				cursor: "pointer",
			},
			// 已经用过的词：置灰且不可点，避免同一个词被点进模板十几遍。
			bbTokenUsed: {
				opacity: 0.38,
				cursor: "not-allowed",
				background: "transparent",
			},
			// 「当前配方」里的胶囊：带删除按钮，视觉上比"可加的词"更实。
			bbChip: {
				display: "inline-flex",
				alignItems: "center",
				gap: "3px",
				border: "0.5px solid var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
				background: "rgba(43, 108, 176, 0.10)",
				color: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
				fontSize: "12px",
				padding: "3px 4px 3px 10px",
				borderRadius: "999px",
			},
			// 字面文字（如「剩 」）：不带删除键的普通说明，用中性色。
			bbChipLit: {
				display: "inline-flex",
				alignItems: "center",
				gap: "3px",
				border: "0.5px dashed var(--dsw-alias-border-l2, #dcdcdc)",
				background: "transparent",
				color: "var(--dsw-alias-label-secondary, #666)",
				fontSize: "12px",
				padding: "3px 4px 3px 10px",
				borderRadius: "999px",
			},
			// 未知 token（用户可能在别处写错）：灰底警告色，但不丢。
			bbChipUnknown: {
				display: "inline-flex",
				alignItems: "center",
				gap: "3px",
				border: "0.5px solid #e8590c",
				background: "rgba(232, 89, 12, 0.10)",
				color: "#e8590c",
				fontSize: "12px",
				padding: "3px 4px 3px 10px",
				borderRadius: "999px",
			},
			bbChipX: {
				border: "none",
				background: "transparent",
				color: "inherit",
				font: "inherit",
				fontSize: "14px",
				lineHeight: "1",
				padding: "0 5px",
				cursor: "pointer",
				opacity: 0.75,
			},
			accBody: { display: "flex", flexDirection: "column", padding: "2px 14px 10px" },
			accCount: { marginLeft: "auto", fontSize: "11px", fontWeight: 400, color: "var(--dsw-alias-label-tertiary, #999)" },
		};

		const MP_PRIV_TONE = {
			on: { color: "#2f9e44", bg: "rgba(47,158,68,0.12)" },
			off: { color: "#e8590c", bg: "rgba(232,89,12,0.12)" },
			// 「还不能用，但没坏」—— 目前只有一种情况：密钥是 App 现场生成的，
			// 设备尚未信任，需要走一次配对。用橙色会和「缺失」混淆，所以单列一色。
			warn: { color: "#f08c00", bg: "rgba(240,140,0,0.12)" },
			unknown: { color: "#868e96", bg: "rgba(134,142,150,0.12)" },
		};

		/**
		 * 把一段文本写进系统剪贴板。
		 *
		 * ⚠️ **必须两条路都留**：官方前端跑在 "http://127.0.0.1:3080" 上，
		 * 而 "navigator.clipboard" 只在**安全上下文**（https / localhost 域名 /
		 * file:）里存在 —— "127.0.0.1" 虽是回环地址、Chromium 也把它算作
		 * potentially trustworthy，但 WebView 上这个判定随内核版本而变，
		 * 不能赌。所以先用 "navigator.clipboard"，失败或不存在时退回
		 * "document.execCommand("copy")" + 临时 textarea 的老办法。
		 *
		 * @param {string} text - 要复制的文本。
		 * @returns {Promise<boolean>} 是否成功。
		 */
		function mpCopyText(text) {
			const fallback = () => {
				try {
					const area = document.createElement("textarea");
					area.value = text;
					// 必须在文档里且可聚焦，execCommand 才会作用于它。
					// 用 fixed + 透明把它藏起来，避免点按钮时页面跳动。
					area.setAttribute("readonly", "");
					area.style.position = "fixed";
					area.style.top = "0";
					area.style.left = "0";
					area.style.opacity = "0";
					area.style.pointerEvents = "none";
					document.body.appendChild(area);
					area.select();
					area.setSelectionRange(0, text.length);
					const ok = document.execCommand("copy");
					document.body.removeChild(area);
					return ok;
				} catch (error) {
					console.warn("[dsh-android-mobile] 复制到剪贴板失败:", error && error.message);
					return false;
				}
			};
			try {
				if (navigator.clipboard && navigator.clipboard.writeText) {
					return navigator.clipboard.writeText(text).then(() => true, fallback);
				}
			} catch (error) {
				/* 落到 fallback */
			}
			return Promise.resolve(fallback());
		}

		/**
		 * 「特权权限」分节组件。
		 *
		 * 官方向 "settings.section" 的组件注入 "t"（由注册项的 "locale" 命名空间绑定）
		 * 与 "close"；本组件只用 "t"。
		 *
		 * @param {object} props - 槽位注入面。
		 * @returns {object} React 元素。
		 */
		function mpPrivSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_PRIV_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			// ⚠️ **软截止**：探测真正返回前，界面不能一直锁在「检测中」。
			//
			//   真机反馈的现象：首次安装打开本页，「当前状态」一直显示「检测中」，
			//   既没有结论也没有任何按钮（那个「重新检测」按钮在 loading 期间是
			//   `disabled` 的），只能硬等——而宿主半的探测超时是 **45 秒**，
			//   用户描述为「等几分钟」。
			//
			//   为什么 45 秒砍不掉：那个预算是 runner 最坏路径（三条通道级联 +
			//   adbd 重启）的上限，砍了会把「慢但成功」误判成失败。
			//   但**界面不必陪着一起等** —— 超过这个软截止就先按「还没结论」渲染，
			//   把按钮还给用户；真结果回来时照常覆盖（`phase` 变 ready/error）。
			const SOFT_DEADLINE_MS = 8000;
			const [softExpired, setSoftExpired] = React.useState(false);
			const requestCleanup = React.useRef(null);
			React.useEffect(() => () => requestCleanup.current?.(), []);
			const refresh = React.useCallback(() => {
				requestCleanup.current?.();
				let alive = true;
				setState({ phase: "loading" });
				setSoftExpired(false);
				// 到点就把界面从「检测中」解锁。真正的 fetch 不取消：它回来时
				// 下面的 then/catch 会用真实结论覆盖掉这个临时视图。
				const softTimer = setTimeout(() => { if (alive) setSoftExpired(true); }, SOFT_DEADLINE_MS);
				window.fetch(MP_PRIV_API, { cache: "no-store" })
					.then((res) => {
						if (!res.ok) throw new Error("HTTP " + res.status);
						return res.json();
					})
					.then((data) => { if (alive) { clearTimeout(softTimer); setState({ phase: "ready", data }); } })
					.catch((error) => {
						if (alive) {
							clearTimeout(softTimer);
							setState({ phase: "error", message: String((error && error.message) || error) });
						}
					});
				requestCleanup.current = () => { alive = false; clearTimeout(softTimer); };
				return requestCleanup.current;
			}, []);
			React.useEffect(refresh, [refresh]);

			// ══ root（uid 0）状态 ══════════════════════════════════════════
			//
			// 与上面的 uid 2000 状态**来源不同**：那条走宿主半的 HTTP 路由（探测要
			// spawn runner），而 root 的结论在**原生**侧（探测要起 `su` 进程、可能弹
			// 授权框），所以走 JS 桥 `window.DSHAndroid.rootState()`。
			//
			// ⚠️ 桥上的 `rootState()` **只读缓存、不触发探测** —— 它跑在 WebView 的
			//   JS 线程上，同步探测会把页面卡住。重新探测走 `rootRecheck()`，那是
			//   后台线程，界面靠下面的轮询拿结果。
			const [root, setRoot] = React.useState({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
			const [rootBusy, setRootBusy] = React.useState(false);
			// 在跑的轮询计时器。用 ref 而不是普通变量：`recheckRoot` 是 useCallback，
			// 闭包捕获的必须是一个**跨渲染稳定**的容器，否则每次渲染新建一个 Set，
			// 卸载时的清理就清不到真正的计时器。
			const rootTimers = React.useRef(null);
			if (rootTimers.current === null) rootTimers.current = new Set();

			/** 读一次缓存里的 root 结论（不触发探测）。 */
			const readRoot = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.rootState !== "function") {
					setRoot({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
					return;
				}
				try {
					// 桥返回 JSON 字符串（@JavascriptInterface 只支持基本类型与 String）。
					const raw = bridge.rootState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setRoot({
						known: !!o.known,
						state: String(o.state || ""),
						granted: !!o.granted,
						detail: String(o.detail || ""),
						at: Number(o.at) || 0,
						// ⚠️ 这是「本会话里 root 工具真的挂上了没有」，与 granted 不是一回事：
						//   overlay 只在引擎启动时读一次，重新检测把结论刷成 GRANTED 之后
						//   工具并不会凭空出现。见下面的 rootNextOnPending。
						mounted: !!o.mounted,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取 root 状态失败:", e && e.message);
					setRoot({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
				}
			}, []);

			React.useEffect(() => { readRoot(); }, [readRoot]);

			/**
			 * 请求重新检测，然后**轮询**直到结论变化。
			 *
			 * ⚠️ 必须轮询，不能等一个返回值：原生侧是「立即返回、后台线程里探测」，
			 *   因为探测最多要等 5 秒（首次还会弹授权框），同步等会把页面卡死。
			 * ⚠️ 轮询上限（10 秒）要比原生的探测超时（5 秒）宽，否则会在原生还没
			 *   出结论时就把按钮恢复，用户以为检测完了、看到的却是旧结论。
			 * ⚠️ 判据是**时间戳变化**（"at"），不是结论变化：连续两次都测出
			 *   「无 root」时结论字符串一样，只看结论会一直轮询到超时。
			 */
			const recheckRoot = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.rootRecheck !== "function") return;
				let before = 0;
				try {
					const raw = bridge.rootState();
					before = Number((typeof raw === "string" ? JSON.parse(raw) : (raw || {})).at) || 0;
				} catch (e) { /* 拿不到旧时间戳就退化成「轮询到超时」，仍然正确 */ }
				try {
					bridge.rootRecheck();
				} catch (e) {
					console.warn("[dsh-android-mobile] 请求重新检测 root 失败:", e && e.message);
					return;
				}
				setRootBusy(true);
				const startedAt = Date.now();
				const timer = window.setInterval(() => {
					const b = window.DSHAndroid;
					let at = 0;
					try {
						const raw = b && b.rootState ? b.rootState() : "{}";
						at = Number((typeof raw === "string" ? JSON.parse(raw) : (raw || {})).at) || 0;
					} catch (e) { /* 读失败当作还没出结果，继续等 */ }
					if (at > before || Date.now() - startedAt > 10000) {
						window.clearInterval(timer);
						rootTimers.current.delete(timer);
						readRoot();
						setRootBusy(false);
					}
				}, 700);
				// 记账以便卸载时清理（本函数没有 cleanup 出口，所以交给下面的 effect）。
				rootTimers.current.add(timer);
			}, [readRoot]);

			// 组件卸载时清掉所有在跑的轮询 —— 否则用户切走分节后计时器仍在打桥。
			React.useEffect(() => {
				const timers = rootTimers.current;
				return () => {
					timers.forEach((id) => window.clearInterval(id));
					timers.clear();
				};
			}, []);

			// 从授权界面回来后刷新：给 root 授权是在 Magisk 之类的**别的应用**里完成的，
			// 本页拿不到任何回调，只能靠 visibilitychange / focus（与气泡分节同一手法）。
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) readRoot(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [readRoot]);

			// ══ Shizuku 状态 ═══════════════════════════════════════════════
			//
			// 与上面两块**来源又不同**：Shizuku 的「装了没 / 服务在跑没 / 授权没」
			// 全在**原生**侧（要问 PackageManager、要问 binder），所以走 JS 桥
			// `window.DSHAndroid.shizukuState()`，与 root 同一套语义（只读缓存、
			// 不触发探测，同步返回 JSON 文本）。
			//
			// ⚠️ **四个字段必须分开存、分开判**（见 PageBridge.shizukuState 的表）：
			//   合并成一个布尔的话，「没装」和「装了没启动」会给出同一个结论，
			//   而这两者要引导用户做的事完全不同（去下载 vs 去打开它）。
			const SHIZUKU_EMPTY = { installed: false, binder: false, granted: false, uid: -1, version: 0 };
			const [shizuku, setShizuku] = React.useState(SHIZUKU_EMPTY);
			// 授权是异步的（用户在 Shizuku 自己的窗口里点「允许」），期间按钮要锁住。
			const [shizukuBusy, setShizukuBusy] = React.useState(false);
			const shizukuTimers = React.useRef(null);
			if (shizukuTimers.current === null) shizukuTimers.current = new Set();

			/** 读一次 Shizuku 状态（不触发任何动作）。 */
			const readShizuku = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.shizukuState !== "function") {
					setShizuku(SHIZUKU_EMPTY);
					return;
				}
				try {
					const raw = bridge.shizukuState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setShizuku({
						installed: !!o.installed,
						binder: !!o.binder,
						granted: !!o.granted,
						uid: Number.isFinite(Number(o.uid)) ? Number(o.uid) : -1,
						version: Number(o.version) || 0,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取 Shizuku 状态失败:", e && e.message);
					setShizuku(SHIZUKU_EMPTY);
				}
			}, []);

			React.useEffect(() => { readShizuku(); }, [readShizuku]);

			// 从 Shizuku 授权窗口回来时刷新 —— 那是**别的应用**的界面，本页拿不到
			// 回调，只能靠 visibilitychange / focus（与 root 同一手法）。
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) readShizuku(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [readShizuku]);

			React.useEffect(() => {
				const timers = shizukuTimers.current;
				return () => {
					timers.forEach((id) => window.clearInterval(id));
					timers.clear();
				};
			}, []);

			/**
			 * 点「授权」：拉起 Shizuku 的确认窗口，然后轮询授权结果。
			 *
			 * ⚠️ **必须轮询**：授权结论是 Shizuku 异步给的，原生侧只登记了一个
			 *   监听器（见 "MainActivity.setupShizukuPermissionListener"），
			 *   页面拿不到任何 promise。
			 * ⚠️ 授权成功后再跑一次 "refresh()"："identity" 那条结论来自 runner
			 *   的探测（宿主半 HTTP 路由），与 Shizuku 的授权状态是**两件事** ——
			 *   只更新 Shizuku 状态的话，上面的「当前状态」徽章还停在「未开启」，
			 *   用户会以为授权没生效。**不需要重启 App**：runner 每次执行命令时
			 *   才现连 Shizuku。
			 */
			const grantShizuku = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.shizukuRequestPermission !== "function") {
					window.alert(t("shizukuNoBridge"));
					return;
				}
				try {
					bridge.shizukuRequestPermission();
				} catch (e) {
					console.warn("[dsh-android-mobile] 请求 Shizuku 授权失败:", e && e.message);
					return;
				}
				setShizukuBusy(true);
				const startedAt = Date.now();
				const timer = window.setInterval(() => {
					const b = window.DSHAndroid;
					let granted = false;
					try {
						const raw = b && b.shizukuState ? b.shizukuState() : "{}";
						granted = !!(typeof raw === "string" ? JSON.parse(raw) : (raw || {})).granted;
					} catch (e) { /* 读失败当作还没授权，继续等 */ }
					// ⚠️ 上限 20 秒：用户可能正在那个窗口上犹豫，也可能压根没打开。
					//   到点就解锁按钮并**照常读一次**，让界面显示真实状态 ——
					//   不能停在「等待授权…」上不动。
					if (granted || Date.now() - startedAt > 20000) {
						window.clearInterval(timer);
						shizukuTimers.current.delete(timer);
						readShizuku();
						setShizukuBusy(false);
						if (granted) refresh();
					}
				}, 600);
				shizukuTimers.current.add(timer);
			}, [readShizuku, refresh, t]);

			const data = state.phase === "ready" ? state.data : null;
			// ⚠️ 软截止到期后**不再算 loading**：下一步提示与按钮可用性都按正常态渲染，
			//   用户立刻拿回控制权（见 SOFT_DEADLINE_MS）。
			//   真结果回来时 `state.phase` 会变成 ready/error，一切照常覆盖。
			const loading = state.phase === "loading" && !softExpired;
			// ⚠️ 但**徽章文案**不能因为软截止就改成「未开启」—— 那时根本没有结论，
			//   报「未开启」是假话（真结果可能是「已开启」）。只有拿到真结论
			//   （ready/error）才允许改徽章，否则一律维持「检测中」。
			const settled = state.phase !== "loading";
			const identityOk = !!(data && data.identity && data.identity.ok);
			const tone = !settled ? "unknown"
				: state.phase === "error" ? "off"
					: identityOk ? "on" : "off";
			const badgeText = !settled ? t("statusUnknown")
				: state.phase === "error" ? t("failed")
					: identityOk ? t("statusOn") : t("statusOff");

			// 当前通道 —— 只有固定端口那条**不依赖无线调试开关**，所以要显式区分，
			// 否则用户看到「已开启」也不知道关了开关会不会断。
			const channel = data && data.identity ? data.identity.channel : "none";
			const channelText = channel === "shizuku" ? t("chShizuku")
				: channel === "legacy" ? t("chStable")
					: channel === "wireless" ? t("chWireless") : "";

			// ── 系统版本：决定走哪条引导路径 ──────────────────────
			//
			// ⚠️ **Android 10 及以下没有「无线调试」** —— 那是 Android 11（API 30）
			//   才有的功能。在 Android 10 上还把它摆在最上面、还挂「推荐」、还给出
			//   「配对无线调试」按钮，用户照着去系统设置里找只会找不到（真机反馈过）。
			//
			// 判据用 UA 里的 `Android <版本>`：浏览器进程拿不到 `Build.VERSION.SDK_INT`，
			//   而 UA 是现成的、不需要为它新增一条原生桥。
			const androidMajor = (() => {
				const m = /Android\s+(\d+)/i.exec(navigator.userAgent || "");
				return m ? Number.parseInt(m[1], 10) : 0;
			})();
			// 版本判不出来时**当作不支持**（走电脑引导）：电脑引导在所有版本上都成立，
			// 而无线调试在 Android 10 上根本不存在。判错方向宁可保守。
			const wirelessSupported = androidMajor >= 11;

			// 「下一步该做什么」—— 由当前状态推出来，摆在首屏。
			// 用户不该为了知道点哪里而读完全部卡片。
			const keyReady = !!(data && data.key
				&& (data.key.origin === "imported" || data.key.origin === "paired"));
			let next = null;
			if (data) {
				// 失败分类（runner 那条 `诊断: kind=…` 行）。没拿到时是 "unknown"，
				// 下面的分支会自动退回通用引导 —— 与改动前的行为一致。
				const kind = data.identity && data.identity.kind ? data.identity.kind : "unknown";
				// 四类「密钥侧」的问题，正解都是**再配对一次**，只是原因不同。
				const keyKind = kind === "no-key" || kind === "key-revoked"
					|| kind === "key-unauthorized" || kind === "key-blocked";
				// ⚠️ **Shizuku 的优先级判据**（本次重做新增，是这次布局调整里
				//   唯一改变「用户实际会去点哪个按钮」的地方）。
				//
				//   理由：内置的两条路都以「设备上有个可连的 adbd」为前提，而
				//   Android 10 上那意味着每次重启都得插电脑。Shizuku 不需要 adbd、
				//   重启也有效 —— 所以只要它**有可能**走得通，就该排在前面。
				//
				//   判据用 `installed || binder` 而不是 `granted`：
				//     · 装了但没启动 → 用户点一下就能启动，值得推
				//     · 没装 → 也值得推（下载一次，之后一劳永逸）
				//   两者都比「去插电脑」轻。真机反馈的那位用户正是这种情况。
				const shizukuViable = shizuku.installed || shizuku.binder;
				if (identityOk) {
					next = channel === "wireless"
						? { text: t("nextSwitchHint"), action: "switchTcp", label: t("wlTcp") }
						: { text: t("nextAllGood"), action: null };
				} else if (shizuku.granted) {
					// Shizuku 已授权但 runner 还没跟上 —— 只要重新检测，不用重启。
					next = { text: t("shizukuNextGrantedStale"), action: null };
				} else if (shizukuViable) {
					// 有 Shizuku 可用，就别再让用户去碰 adb 那条路。
					// ⚠️ 这里**必须给按钮**：只写一句「去启动 Shizuku」而不给入口，
					//   用户得自己在应用列表里找 Shizuku —— 而本页明明知道该点哪里。
					//   用 `onClick` 而不是 `action`：授权走的是 grantShizuku（要轮询
					//   结果），不是 doAction 里那种一发即忘的动作。
					next = shizuku.binder
						? { text: t("shizukuNextUngranted"), onClick: grantShizuku, label: t("shizukuGrant") }
						: { text: t("shizukuNextStopped"), action: "shizukuOpen", label: t("shizukuOpen") };
				} else if (keyKind) {
					// ⚠️ 只在系统真支持无线调试时才给配对按钮 —— 否则那个按钮在
					//    Android 10 上永远走不通，点了只是白等（与下面同一条约束）。
					const text = kind === "no-key"
						? t(wirelessSupported ? "nextNoKey" : "nextNoKeyPc")
						: kind === "key-revoked" ? t("nextRevoked")
							: kind === "key-unauthorized" ? t("nextUnauthorized")
								: t("nextKeyBlocked");
					next = wirelessSupported
						? { text, action: "pair", label: t(kind === "no-key" ? "wlPair" : "nextPair") }
						: { text, action: null };
				} else if (kind === "wireless-stale" || kind === "no-channel") {
					// 这两类的正解是**切到固定端口**：固定端口一旦就绪就不再依赖
					// 无线调试开关，恰好绕开「无线调试关着 ⇒ 无线通道必然不可用」。
					next = {
						text: t(kind === "wireless-stale" ? "nextWirelessStale" : "nextNoChannel"),
						action: "switchTcp",
						label: t("wlTcp"),
					};
				} else if (kind === "adb-missing" || kind === "timeout") {
					// 这两类没有能替用户按的按钮：前者要重装 App，后者只是 adbd
					// 还在重启（等一会儿点「重新检测」）。给一句可执行的说明就够，
					// **不要**摆一个点了没用的按钮。
					next = { text: t(kind === "adb-missing" ? "nextAdbMissing" : "nextTimeout"), action: null };
				} else if (data.key && data.key.origin === "generated" && wirelessSupported) {
					// 只有系统真支持无线调试时，才引导去配对 —— 否则那个按钮
					// 在 Android 10 上永远走不通，点了只是白等。
					next = { text: t("nextPairHint"), action: "pair", label: t("wlPair") };
				} else if (keyReady) {
					next = { text: t("nextSwitchHint"), action: "switchTcp", label: t("wlTcp") };
				} else {
					next = {
						text: wirelessSupported ? t("nextChoose") : t("nextChoosePc"),
						action: null,
					};
				}
			}

			// 两个原生动作都要走 JS 桥 —— 配对码得经通知栏输入，而通知是 Android
			// 的能力，Node 侧（宿主半的 HTTP 路由）够不到。
			const doAction = (action) => {
				const b = window.DSHAndroid;
				const fn = action === "pair" ? "startWirelessPairing"
					: action === "switchTcp" ? "switchToTcpMode"
						: action === "shizukuOpen" ? "shizukuOpenManager"
							: action === "shizukuDownload" ? "shizukuOpenDownload" : null;
				if (fn === null) return;
				if (!b || typeof b[fn] !== "function") { window.alert(t("wlNoBridge")); return; }
				// ⚠️ 配对的一切交互（提示、配对码输入框、取消按钮）**都挂在那条常驻
				//   通知上**，通知被系统关掉时原生侧会直接拒绝开始（见
				//   `AdbPairingNotifier.start`）。不在这里先说清楚的话，用户看到的是
				//   「点了配对，什么都没发生」—— 然后反复点，永远不知道为什么。
				if (action === "pair") {
					try {
						if (typeof b.pairingNotificationsEnabled === "function"
							&& !b.pairingNotificationsEnabled()) {
							window.alert(t("wlNoNotify"));
							return;
						}
					} catch (e) { /* 查不了就照常走，原生侧还有一道拦截 */ }
				}
				b[fn]();
				// 只有配对需要回写「进行中」—— 它是一条要用户离开本页去系统设置的
				// 长流程；「切到固定端口」是几秒钟的原生调用，不需要。
				if (action === "pair") setPairing(true);
			};

			/** 取消配对：停掉原生侧那条常驻监听，并把本页状态复位。 */
			const cancelPairing = () => {
				const b = window.DSHAndroid;
				if (b && typeof b.cancelWirelessPairing === "function") b.cancelWirelessPairing();
				setPairing(false);
			};

			// 无线端口三态：真的在听 / 文件里有值但端口已死 / 没发现。
			// ⚠️ 中间那态以前会显示成绿灯（只查文件在不在），真机撞到过 ——
			// 无线调试关着、35589 早已没人监听，界面却报「无线端口 35589」。
			const wlPort = data && data.wireless ? data.wireless.connect : null;
			const wlStale = data && data.wireless ? data.wireless.stale : null;
			const [showDetails, setShowDetails] = React.useState(false);

			const rows = [];
			if (data) {
				rows.push({
					key: "runner", label: t("rowRunner"), ok: data.runner.ok,
					value: data.runner.ok ? `${t("ready")} · ${data.runner.size} ${t("bytes")}` : t("missing"),
				});
				// ⚠️ 徽章不能只看「密钥文件在不在」：**App 现场生成的密钥设备并不信任**，
				// 显示绿点会让人以为可以用了。
				//
				// 三态（判据见 assets/dsh-android-shell.mjs 的 `keyOrigin()`）：
				//   imported / paired → 绿点，设备已信任，直接能用
				//   generated         → 黄点，必须先配对
				//   null              → 灰点，**来源未知**（标记文件是后加的，
				//                       老安装的密钥没有它，无法回溯推断）
				// 未知时**故意不猜**成「已导入」—— 那正是这次要修的误报。
				rows.push({
					key: "key", label: t("rowKey"), ok: keyReady,
					warn: data.key.ok && data.key.origin === "generated",
					unknown: data.key.ok && !keyReady && data.key.origin !== "generated",
					value: data.key.ok
						? `${data.key.origin === "generated" ? t("generated")
							: data.key.origin === "paired" ? t("paired")
								: data.key.origin === "imported" ? t("imported")
									: t("keyOriginUnknown")} · ${data.key.size} ${t("bytes")}${data.key.importedNow ? " · " + t("justImported") : ""}`
						: t("notImported"),
				});
				rows.push({
					key: "identity", label: t("rowIdentity"), ok: identityOk,
					value: identityOk
						? `${t("uidOk")}${data.identity && data.identity.channel === "legacy" ? " · " + t("chStable")
							: data.identity && data.identity.channel === "wireless" ? " · " + t("chWireless") : ""}`
						: `${t("uidApp")}${data.identity && data.identity.reason ? " · " + data.identity.reason : ""}`,
				});
			}

			// 复制结果按**对象**存（不是布尔、也不是纯字符串）：两个块共用一个
			// 「最近复制的是哪条」，点第二条时第一条会自己复位成「复制」，
			// 不会两块同时显示「已复制」让人分不清到底复制了哪条。
			// 存对象而非字符串还有个作用：连点同一个按钮时 `at` 每次都不同，
			// 保证 state 一定变化 → effect 重跑 → 复位计时器被重新计时。
			const [copied, setCopied] = React.useState(null);
			const copyMounted = React.useRef(true);
			React.useEffect(() => {
				copyMounted.current = true;
				return () => { copyMounted.current = false; };
			}, []);
			React.useEffect(() => {
				if (copied === null) return undefined;
				const timer = window.setTimeout(() => setCopied(null), 2000);
				return () => window.clearTimeout(timer);
			}, [copied]);

			// 是否已经发起过配对（点了「配对无线调试」但还没取消）。
			//
			// ⚠️ 这个状态**必须让用户看见**，否则点了按钮后界面毫无变化，用户会以为
			// 没点上而反复点 —— 而真实情况是「正在等你去系统里打开那个界面」。
			// 只存本地 state：真正的监听状态在原生侧，这里只是给个即时反馈，
			// 不需要（也不该）为此多开一条查询接口。
			const [pairing, setPairing] = React.useState(false);
			const e = React.createElement;
			// 命令块 = 等宽代码 + 复制按钮。**按钮不是锦上添花**：本插件的全局
			// CSS 把 `user-select` 压成 `none`（见文件头 4c），命令长按选不中，
			// 而用户是在电脑终端里敲这些命令，只能照着手机屏幕抄 —— 没有按钮就得
			// 逐字符手抄 `%USERPROFILE%\.android\adbkey`。
			const cmdBlock = (text) => e("div", { key: text, style: S.cmdRow },
				e("code", { style: S.cmd }, text),
				e("button", {
					type: "button",
					style: S.cmdBtn,
					onClick: () => {
						mpCopyText(text).then((ok) => { if (copyMounted.current) setCopied({ text, ok, at: Date.now() }); });
					},
				}, copied && copied.text === text
					? (copied.ok ? t("copied") : t("copyFailed"))
					: t("copy")),
			);
			const guideSteps = [
				e("li", { key: 0, style: S.step }, t("step1")),
				e("li", { key: 1, style: S.step }, t("step2"), cmdBlock(t("cmd2"))),
				e("li", { key: 2, style: S.step }, t("step3"), cmdBlock(t("cmd3"))),
				e("li", { key: 3, style: S.step }, t("step4")),
			];

			// ── 首屏：结论 + 下一步 ────────────────────────────────
			// 原来的首屏是一张「执行通道 / ADB 密钥 / 实际身份」的技术表格，
			// 用户得自己把三行翻译成「我现在能不能用、不能的话点哪里」。
			// 现在把结论摆在最上面，明细折进「查看详情」。
			const head = e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, t("statusTitle")),
					e("span", { style: { ...S.badge, color: MP_PRIV_TONE[tone].color, background: MP_PRIV_TONE[tone].bg } }, badgeText),
				),
				// 已开启时把「走哪条通道」直接写在结论下面 —— 这是用户唯一需要
				// 知道的运行期事实（关无线调试/关 Wi-Fi 会不会断）。
				identityOk && channelText
					? e("p", { style: S.summary }, channelText)
					: null,
				next ? e("div", { style: S.next },
					e("span", { style: S.nextText }, next.text),
					// 两种按钮：`action` 走 doAction（原生一侧的一发即忘动作），
					// `onClick` 走组件内的回调（Shizuku 授权要轮询结果）。
					next.onClick ? e("button", {
						type: "button",
						style: S.btnPrimary,
						disabled: shizukuBusy,
						onClick: next.onClick,
					}, shizukuBusy ? t("shizukuGranting") : next.label)
						: next.action ? e("button", {
							type: "button",
							style: S.btnPrimary,
							onClick: () => doAction(next.action),
						}, next.label) : null,
				) : null,
				// ⚠️ 软截止到期、真结果还没回来时，明确告诉用户「还在探测、你可以不等」，
				//   并直接给出「重新匹配」入口 —— 而不是留一个没有结论也没有按钮的死界面。
				//   真机反馈原话：「你一检测没有就是没有了呀，没有就让用户重新走匹配流程啊，
				//   我要这里等几分钟？」这条分支就是那个诉求的落点。
				softExpired && state.phase === "loading" ? e("div", { style: S.next },
					e("span", { style: S.nextText }, t(wirelessSupported ? "softPending" : "softPendingPc")),
					// ⚠️ 只有系统真支持无线调试时才给配对按钮 —— Android 10 上配对
					//   根本不存在，摆一个点了必然白等的按钮比不给更糟（与上面
					//   `keyKind` 分支同一条约束）。
					wirelessSupported ? e("button", {
						type: "button",
						style: S.btnPrimary,
						onClick: () => doAction("pair"),
					}, t("wlPair")) : null,
				) : null,
				state.phase === "error" ? e("p", { style: S.error }, state.message) : null,
				e("div", { style: S.actions },
					e("button", {
						type: "button",
						style: S.btn,
						// ⚠️ 只在**软截止之前**禁用。到点即解锁，用户不必陪着探测一起等 ——
						//   真机反馈的「一直显示检测中、又没有按钮、又没有停止」正是
						//   这里一直 `disabled` 造成的。
						disabled: loading,
						onClick: refresh,
					}, loading ? t("checking") : t("recheck")),
					data ? e("button", {
						type: "button",
						style: S.btnQuiet,
						onClick: () => setShowDetails((v) => !v),
					}, showDetails ? t("hideDetails") : t("showDetails")) : null,
				),
				// 明细：定位问题时才需要，默认收起。
				showDetails && data ? e("div", { style: S.details },
					rows.map((row) => e("div", { key: row.key, style: S.row },
						e("span", {
							style: {
								...S.dot,
								background: (row.ok ? MP_PRIV_TONE.on
									: row.warn ? MP_PRIV_TONE.warn
										: row.unknown ? MP_PRIV_TONE.unknown
											: MP_PRIV_TONE.off).color,
							},
						}),
						e("span", { style: S.rowLabel }, row.label),
						e("span", { style: S.rowValue }, row.value),
					)),
				) : null,
			);

			// ── 三条开启路径：统一成同一种卡片结构 ────────────────────
			//
			// ⚠️ **这是本次布局重做的核心。** 原布局里三条路各有各的形状：
			//   无线调试是一张普通卡（按钮常驻三个）、电脑引导是一个 details、
			//   Shizuku 根本没有；顺序还随系统版本翻转（Android 10 电脑在前、
			//   Android 11+ 无线在前）。结果是用户每次打开看到的排版都不一样，
			//   得重新认一遍哪里是标题、哪里是按钮。
			//
			//   现在三条路**结构完全一致**，顺序也**固定**（Shizuku → 无线 → 电脑）：
			//     标题 + 状态徽章
			//     一句话说明
			//     关键事实行（可选）
			//     按钮行
			//     折起的步骤（可选）
			//   动线因此是固定的：先看徽章判断这条路通不通，再看按钮做下一步。
			const pathCard = (opts) => e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, opts.title),
					e("span", {
						style: { ...S.badge, color: opts.tone.color, background: opts.tone.bg },
					}, opts.badge),
				),
				opts.intro ? e("p", { style: S.intro }, opts.intro) : null,
				...(opts.rows || []),
				opts.actions && opts.actions.length > 0
					? e("div", { style: S.actions }, ...opts.actions)
					: null,
				opts.steps && opts.steps.length > 0 ? e("details", { style: S.foldInline },
					e("summary", { style: S.foldHead }, t("pathShowSteps")),
					e("div", { style: S.foldBody }, e("ol", { style: S.steps }, opts.steps)),
				) : null,
				...(opts.notes || []),
			);

			// ① Shizuku —— 唯一不依赖 adbd 的那条，所以固定排第一。
			//
			// ⚠️ 四态徽章：装了 / 服务在跑 / 已授权 是**三个独立事实**，
			//   合并的话「没装」和「装了没启动」会显示成同一个结论，
			//   而这两者要用户做的事完全不同（去下载 vs 去打开它）。
			const szTone = !shizuku.installed ? MP_PRIV_TONE.off
				: !shizuku.binder ? MP_PRIV_TONE.warn
					: !shizuku.granted ? MP_PRIV_TONE.warn
						: MP_PRIV_TONE.on;
			const szBadge = !shizuku.installed ? t("shizukuAbsent")
				: !shizuku.binder ? t("shizukuInstalled")
					: !shizuku.granted ? t("shizukuUngranted")
						: t("shizukuGranted");
			// 主按钮按状态换：没装→下载，装了没跑→打开它，跑了没授权→授权，
			// 已授权→不需要主按钮（只留「重新检测」）。
			//
			// ⚠️ **一次只给一个主按钮**：原布局在同一屏里摆了五个同等分量的按钮
			//   （配对/切端口/取消/重新检测/查看详情），用户不知道点哪个才对。
			const szPrimary = !shizuku.installed
				? e("button", {
					type: "button", style: S.btnPrimary,
					onClick: () => doAction("shizukuDownload"),
				}, t("shizukuDownload"))
				: !shizuku.binder
					? e("button", {
						type: "button", style: S.btnPrimary,
						onClick: () => doAction("shizukuOpen"),
					}, t("shizukuOpen"))
					: !shizuku.granted
						? e("button", {
							type: "button", style: S.btnPrimary,
							disabled: shizukuBusy,
							onClick: grantShizuku,
						}, shizukuBusy ? t("shizukuGranting") : t("shizukuGrant"))
						: null;
			const szCard = pathCard({
				title: t("shizukuTitle"),
				// 徽章兼「推荐」位：Shizuku 在**两种系统上都能用**且重启有效，
				// 所以无论 Android 版本都是首推的那条（这正是本次加它的理由）。
				tone: szTone,
				badge: szBadge,
				// ⚠️ intro 是**动态的「现在该做什么」**，不是固定的功能说明。
				//   固定说明只在「没装」时才有信息量（那时用户确实不知道 Shizuku
				//   是什么）；装了的人再看一遍等于白占一屏。
				//   这与首屏 `next` 那条是同一条原则：先说下一步，再说原理。
				intro: !shizuku.installed ? t("shizukuNextAbsent")
					: !shizuku.binder ? t("shizukuNextStopped")
						: !shizuku.granted ? t("shizukuNextUngranted")
							// ⚠️ 第四种：Shizuku 已授权，但 runner 的探测结论还没跟上
							//   （identityOk 为假）。此时说「已就绪」与上面的徽章矛盾，
							//   必须说清「点一下重新检测就好」。
							: identityOk ? t("shizukuNextGranted")
								: t("shizukuNextGrantedStale"),
				rows: [
					e("div", { key: "svc", style: S.row },
						e("span", { style: { ...S.dot, background: szTone.color } }),
						e("span", { style: S.rowLabel }, t("shizukuRowService")),
						e("span", { style: S.rowValue },
							!shizuku.installed ? t("shizukuServiceAbsent")
								: shizuku.binder ? t("shizukuServiceRunning")
									: t("shizukuServiceStopped")),
					),
					// 服务身份/版本只在服务真的在跑时才有值 —— 没跑时显示「未运行」
					// 是重复上面那行，所以干脆不渲染。
					shizuku.binder ? e("div", { key: "uid", style: S.row },
						e("span", { style: { ...S.dot, background: MP_PRIV_TONE.on.color } }),
						e("span", { style: S.rowLabel }, t("shizukuRowUid")),
						e("span", { style: S.rowValue }, shizuku.uid >= 0 ? `uid ${shizuku.uid}` : "—"),
					) : null,
					shizuku.binder ? e("div", { key: "ver", style: S.row },
						e("span", { style: { ...S.dot, background: MP_PRIV_TONE.unknown.color } }),
						e("span", { style: S.rowLabel }, t("shizukuRowVersion")),
						e("span", { style: S.rowValue }, shizuku.version > 0 ? String(shizuku.version) : "—"),
					) : null,
				],
				actions: [szPrimary, e("button", {
					type: "button", style: S.btn,
					onClick: readShizuku,
				}, t("shizukuRecheck"))].filter(Boolean),
				steps: [
					e("li", { key: 0, style: S.step }, t("shizukuStep1")),
					e("li", { key: 1, style: S.step }, t("shizukuStep2")),
					e("li", { key: 2, style: S.step }, t("shizukuStep3")),
					e("li", { key: 3, style: S.step }, t("shizukuStep4")),
				],
				notes: [e("p", { key: "note", style: S.note }, t("shizukuNote"))],
			});

			// ② 无线调试 —— Android 10 上给一张**纯说明卡**，不再是整张消失。
			//
			// ⚠️ 这一处改过两轮，方向是相反的，别再改回去：
			//   原布局保留整张卡、只把配对按钮藏掉 ⇒ 用户看到一张标题写着
			//   「无线调试」、里面有四个步骤、却没有任何按钮可点的卡，照着做白做
			//   （真机反馈的「按步骤走了没成功、一切都跟没动一样」有一部分就是它）。
			//   改成整张不渲染之后又矫枉过正 —— 用户在 ADB 标签下**根本看不到**
			//   还有这么一条路，只会以为本应用漏做了。
			//
			//   现在：**行还在、点得进去、里面说清为什么不支持**。可见性与
			//   「不骗用户去点一个走不通的按钮」两个要求同时满足 —— 判据是
			//   「有没有可点的动作」，而不是「这一块在不在」。
			const wlCard = !wirelessSupported
				? e("div", { style: S.card },
					e("div", { style: S.cardHead },
						e("h3", { style: S.cardTitle }, t("wlTitle")),
						e("span", {
							style: {
								...S.badge,
								color: MP_PRIV_TONE.unknown.color,
								background: MP_PRIV_TONE.unknown.bg,
							},
						}, t("pathUnavailable")),
					),
					e("p", { style: S.intro }, t("wlAbsentNote")),
				)
				: pathCard({
				title: t("wlTitle"),
				tone: identityOk && channel === "wireless" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off,
				badge: identityOk && channel === "wireless" ? t("pathReady") : t("pathTodo"),
				intro: t("wlIntro"),
				rows: [
					// 无线端口是「有没有发现到」的最直接证据 —— 没有它，
					// 用户只能靠「配对按钮点了没反应」来猜，无从下手。
					// 三态：真的在听（绿）/ 文件里有值但端口已死（灰，说明无线调试关了）/
					// 没发现（灰）。中间那态以前会误报成绿灯。
					data ? e("div", { key: "port", style: S.row },
						e("span", {
							style: {
								...S.dot,
								background: wlPort ? MP_PRIV_TONE.on.color
									: wlStale ? MP_PRIV_TONE.unknown.color : MP_PRIV_TONE.off.color,
							},
						}),
						e("span", { style: S.rowLabel }, t("wlPort")),
						e("span", { style: S.rowValue }, wlPort ? String(wlPort)
							: wlStale ? t("wlPortStale") : t("wlPortNone")),
					) : null,
				],
				actions: [
					e("button", {
						type: "button", style: S.btnPrimary,
						disabled: pairing,
						onClick: () => doAction("pair"),
					}, pairing ? t("wlPairing") : t("wlPair")),
					// ⚠️ 「取消配对」只在**真的在配对时**才出现。原布局让它常驻，
					//   于是一个没有任何进行中操作的页面上摆着「取消配对」——
					//   用户会以为有什么正在跑，或者点了它以为取消了什么。
					pairing ? e("button", {
						type: "button", style: S.btn,
						onClick: cancelPairing,
					}, t("wlCancel")) : null,
					e("button", {
						type: "button", style: S.btn,
						onClick: () => doAction("switchTcp"),
					}, t("wlTcp")),
				].filter(Boolean),
				steps: [
					e("li", { key: 0, style: S.step }, t("wlStep1")),
					e("li", { key: 1, style: S.step }, t("wlStep2")),
					e("li", { key: 2, style: S.step }, t("wlStep3")),
					e("li", { key: 3, style: S.step }, t("wlStep4")),
				],
				notes: [
					// 等待期间把「接下来该做什么」直接铺在按钮下面 —— 用户点了配对之后
					// 眼神还在这块区域，跑到别处去提示等于没提示。
					pairing ? e("p", { key: "hint", style: S.note }, t("wlPairingHint")) : null,
					e("p", { key: "tcp", style: S.note }, t("wlTcpHint")),
					// ⚠️ 这条必须留：用户看到「输入配对码」的第一反应是「让 App 自己填」
					//   或者「复制粘贴」，而 SPAKE2 要求人工输入。不说清就会反复试。
					e("p", { key: "code", style: S.note }, t("wlPairHint")),
				].filter(Boolean),
			});

			// ③ 电脑引导（固定端口）—— 所有版本上都成立，所以永远渲染。
			const pcCard = pathCard({
				title: wirelessSupported ? t("pcTitle") : t("pcTitlePrimary"),
				tone: identityOk && channel === "legacy" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off,
				badge: identityOk && channel === "legacy" ? t("pathReady") : t("pathTodo"),
				intro: t("pcIntro"),
				// Android 10 上「无线调试」那张卡整张不渲染，所以它为什么不存在的
				// 那句话挪到这里 —— 否则用户会以为本应用漏做了这个功能。
				notes: [!wirelessSupported
					? e("p", { key: "wl-absent", style: S.note }, t("wlAbsentNote"))
					: null].filter(Boolean),
				actions: [e("button", {
					type: "button", style: S.btn,
					onClick: () => doAction("switchTcp"),
				}, t("wlTcp"))],
				// ⚠️ 这里的步骤**自带命令块**，不能走 pathCard 的纯文本 steps ——
				//   所以整段作为 details 内容直接传进来。命令必须能一键复制：
				//   本插件的全局 CSS 把 user-select 压成 none，长按选不中。
				steps: guideSteps,
			});

			// ── 注意事项（折起）────────────────────────────────
			const notesCard = e("details", { style: S.fold },
				e("summary", { style: S.foldHead }, t("notesTitle")),
				e("ul", { style: S.notes },
					[t("note1"), t("note2"), t("note3")].map((text, index) => e("li", { key: index, style: S.note }, text)),
				),
			);

			// ── root（uid 0）二级界面 ─────────────────────────────
			//
			// 为什么单独一个标签：uid 2000 与 root 是**两条独立的通道**，可用性互不
			// 影响（没 root 的设备照样能用 uid 2000）。所以不能把 root 塞进 ADB
			// 那个标签里 —— 那个标签只回答「uid 2000 能不能用」。
			//
			// ⚠️ 三态各自对应**完全不同的下一步**（去授权 / 什么都不用做 / 去检测），
			//   所以文案必须分开，不能合成一句「root 不可用」。
			const rootTone = !root.known ? MP_PRIV_TONE.unknown
				: root.granted ? MP_PRIV_TONE.on
					: root.state === "DENIED" ? MP_PRIV_TONE.warn : MP_PRIV_TONE.off;
			const rootBadge = !root.known ? t("rootUnknown")
				: root.granted ? t("rootOn")
					: root.state === "DENIED" ? t("rootDenied") : t("rootAbsent");
			// ⚠️ `granted` 与 `mounted` 必须分开判：前者是「探测结论」，后者是
			//   「本次启动真的挂上了 root 工具」。工具走 `--patch`，启动时定死 ——
			//   用户在设置页点「重新检测」把结论刷成 GRANTED 的那一刻，引擎已经在跑，
			//   这个会话里**没有** root 工具。此时若还说「已注册」，就是在说假话。
			const rootNext = !root.known ? t("rootNextUnknown")
				: root.granted
					? (root.mounted ? t("rootNextOn") : t("rootNextOnPendingReload"))
					: root.state === "DENIED" ? t("rootNextDenied") : t("rootNextAbsent");
			// 检测时间：结论是 24 小时缓存的，用户需要知道这是刚测的还是昨天的。
			const rootAtText = root.at > 0 ? new Date(root.at).toLocaleString() : t("rootDetailNever");

			const rootCard = e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, t("rootTitle")),
					e("span", { style: { ...S.badge, color: rootTone.color, background: rootTone.bg } }, rootBadge),
				),
				e("p", { style: S.intro }, t("rootIntro")),
				e("div", { style: S.next },
					e("span", { style: S.nextTextWide }, rootNext),
					e("button", {
						type: "button",
						style: S.btnPrimary,
						disabled: rootBusy,
						onClick: recheckRoot,
					}, rootBusy ? t("rootChecking") : t("rootRecheck")),
				),
				e("div", { style: S.row },
					e("span", { style: { ...S.dot, background: rootTone.color } }),
					e("span", { style: S.rowLabel }, t("rootRowDetail")),
					// 结论原文由原生给出（`su=/system/bin/su, id -u → 0` 这类），
					// 直接透出比翻译成一句话更有助于排查。
					e("span", { style: S.rowValue }, root.known && root.detail ? root.detail : t("rootDetailNever")),
				),
				e("div", { style: S.row },
					e("span", { style: { ...S.dot, background: MP_PRIV_TONE.unknown.color } }),
					e("span", { style: S.rowLabel }, t("rootRowAt")),
					e("span", { style: S.rowValue }, rootAtText),
				),
				e("p", { style: S.note }, t("rootNote")),
			);

			// ══ 标签页布局 ════════════════════════════════════════════════
			//
			// ⚠️ **这是第二次重做，与上一版的结构差异必须说清，否则很容易改回去。**
			//
			//   上一版把三条通道**全部平铺**在一屏上（Shizuku 卡 + 无线卡 + 电脑引导卡
			//   + root 卡），理由是「让用户看到还有别的路」。实测的代价是：一屏里
			//   四五张结构相似的卡纵向排开，每张都带徽章、说明、按钮、折叠步骤，
			//   用户在 360px 宽的屏上要滑很久才能看完，而且**看完也不知道该点哪张**
			//   —— 卡片之间的层级全靠徽章文字区分，那太弱了。
			//
			//   现在：三条通道各占一个标签，**标签栏本身就是导航** —— 点标签直接切到
			//   该通道的二级界面，一屏一件事。
			//
			// ⚠️ **不要额外加一层「概览列表」。** 中途试过「标签栏 + 三行概览卡」，
			//   结果是同一组信息（通道名 + 状态）在一屏里说两遍，而且两处的点击
			//   行为一样、视觉分量却不同，用户会以为它们是两回事。标签栏的圆点
			//   已经承担了「不点进去就知道哪条通」的职责，够了。
			//
			//   两条约束仍然成立（与上一版一致，不能丢）：
			//     ① 三条路都**看得见** —— 标签栏常驻，不存在「藏起来」
			//     ② 顺序固定 Shizuku → ADB → root，不按系统版本翻转
			//     ③ 走不通的路**不给按钮**，但说明必须看得到（见 wlCard）
			//
			// 默认落在**第一个标签**（Shizuku）：它是唯一不依赖 adbd、重启也有效的
			// 通道，对「Android 10 且只有有线 adb」那类设备是唯一可用的 ——
			// 打开就停在最可能解决问题的那一页，而不是一个什么都没有的总览。
			const [tab, setTab] = React.useState("shizuku");

			// 三条通道在标签圆点上的状态色。
			// ⚠️ 圆点色与卡片徽章色**必须同源**（都用 `MP_PRIV_TONE` 里那几档）——
			//   分开算迟早会不一致，那种不一致用户一眼就能看到。
			//
			// ⚠️ 标签上**不摆徽章文字**。窄屏上三个标签平分 360px，每个只有
			//   ~110px，「Shizuku」已经占掉大半，再塞「待配置 / 已就绪」会被
			//   挤成省略号；而状态本身点进去就能看到，圆点已经足够传达「通/不通」。
			const adbTone = identityOk && channel === "legacy" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off;

			const tabs = [
				// ⚠️ **Shizuku 排第一**：它不依赖 adbd、重启也有效，是唯一对
				//   「Android 10 且只有有线 adb」那类设备真正可用的通道。
				//   ADB 排在它后面不是因为没用，而是因为它的两条走法都有前提。
				//
				// ⚠️ 标签只带 `label` / `tone` / `body` 三样，**不带标题与说明**
				//   —— 那是卡片自己的事（`szCard`/`wlCard`/`pcCard`/`rootCard` 都自带
				//   `cardHead` + intro）。中途试过在标签栏下面再加一块「通道标题 +
				//   说明」的头部，结果是同一句话在屏幕上出现两遍，而且两处都是
				//   「标题 + 徽章」的形状，用户会以为点错了地方。
				{
					id: "shizuku", label: t("tabShizuku"), tone: szTone,
					body: szCard,
				},
				{
					id: "adb", label: t("tabAdb"), tone: adbTone,
					// ADB 二级界面 = 无线调试卡（Android 11+）或它的说明卡（Android 10）
					//  + 电脑引导卡。两张都留着：它们同属「ADB 这条路」，只是走法不同。
					//
					// ⚠️ 两张卡之前先铺一段统摄说明。没有它，用户看到两张并列的卡
					//   会以为是两条互不相干的路 —— 而它们其实共用同一套密钥与 adbd。
					body: e("div", { style: S.stack },
						e("p", { style: S.intro }, t("adbIntro")),
						wlCard,
						pcCard,
					),
				},
				{
					id: "root", label: t("tabRoot"), tone: rootTone,
					body: rootCard,
				},
			];
			// 找不到就退回第一个 —— `tab` 是本地 state，不会有非法值，但
			// `find` 返回 undefined 时若直接解引用会在渲染期抛错、整页白屏。
			const active = tabs.find((item) => item.id === tab) || tabs[0];

			return e("div", { style: S.section },
				e("h2", { style: S.heading }, t("title")),
				e("p", { style: S.intro }, t("intro")),

				// 常驻结论条：无论切到哪个标签都看得见 —— 它是「现在到底能不能用」的
				// 唯一答案，不该因为切了标签就消失。
				head,

				e("div", { style: S.stack },
					e("div", { style: S.tabBar },
						...tabs.map((item) => e("button", {
							key: item.id,
							type: "button",
							style: item.id === active.id ? { ...S.tab, ...S.tabActive } : { ...S.tab },
							onClick: () => setTab(item.id),
						},
							e("span", { style: { ...S.tabDot, background: item.tone.color } }),
							e("span", { style: S.tabLabel }, item.label),
						)),
					),
					e("div", { style: S.panel }, active.body),
					notesCard,
				),
			);
		}

		/**
		 * 把「特权权限」分节注册进官方设置页的一级导航。
		 *
		 * 用 "ctx.inject" 等 "slots" / "locale" 就绪：本插件声明的 "inject" 是空数组
		 * （客户端半刻意不声明依赖，避免因某个服务缺失而整个不激活），所以这里按需
		 * 开一个子插件来等这两个服务。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterPrivilegedSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过特权权限分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;   // 拿不到 React 就静默跳过，不影响移动端 UI 适配
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ **必须用 `scope` 上的服务，不能用外层 `ctx`。**
					//   Cordis 的 `ctx.locale` 要求**当前 fiber 声明过** `locale` 依赖，
					//   否则抛 `cannot get property "locale" without inject`。
					//   本插件的 `inject` 是空数组（客户端半刻意不声明依赖），所以外层
					//   `ctx.locale` 一定不可用；`scope` 是这个 `ctx.inject` 子 fiber，
					//   它的依赖表里有 `slots`/`locale`，从它取才对。
					//   （官方 ui-approval 写 `ctx.locale` 是因为它的 inject 里声明了 locale，
					//    照抄那行在本插件里必炸 —— 这里踩过一次。）
					const t = scope.locale.bind(MP_PRIV_NS);
					scope.effect(
						() => scope.locale.register(MP_PRIV_NS, { zh: MP_PRIV_ZH, en: MP_PRIV_EN }),
						"dsh-android-mobile: 特权权限字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						// 刻意不叫 "privileged"：官方若将来加同名分节，我们的会把它顶掉。
						id: "dsh-android-privileged",
						// 官方 order：account=-10、general=0、models=10、plugins=15、
						// agent-presets=20。排在所有官方分节之后，不打乱用户熟悉的顺序。
						order: 25,
						label: () => t("nav"),
						locale: MP_PRIV_NS,
					}, mpPrivSection));
				});
			} catch (e) {
				/* 注册失败只损失这一个分节，绝不能连累移动端 UI 适配。
				   但**要打日志**：静默吞异常会让「分节没出现」变成无从下手的哑谜。 */
				console.warn("[dsh-android-mobile] 注册特权权限分节失败:", e && (e.stack || e.message || e));
			}
		}

		/**
		 * 「悬浮气泡」分节组件。
		 *
		 * 布局沿用「特权权限」那套**结论优先**的结构：head 卡给状态徽章 + 一条
		 * 「下一步」+ 直达按钮，明细与说明折进 "<details>"。
		 *
		 * @param {object} props - 槽位注入面（只用 "t"）。
		 * @returns {object} React 元素。
		 */
		/**
		 * 悬浮球「外观与手感」控制面板。
		 *
		 * ## 为什么单独一个组件
		 *
		 * "mpBubbleSection" 已经承担了「开关气泡 / 权限状态 / 下一步」那套结论优先的
		 * 首屏，把它撑大只会让首屏失控。这里专注一件事：**把 BubbleAppearance 的
		 * 所有可调项摆出来**，按语义分组、可折叠。
		 *
		 * ## 数据流（单向，原生是唯一真源）
		 *
		 * ""`
		 * 挂载 → bubbleAppearance() 读全量 JSON → 本地 state
		 * 改动 → bubbleApply(JSON.stringify({ 字段: 新值 })) → 重新读回 → 覆盖 state
		 * ""`
		 *
		 * ⚠️ **每次改动都读回**而不是本地乐观更新："BubbleAppearance.merge()" 里有
		 *   clamp（如 "size" 只收 24–160）和特权闸门（"imeFlingEnabled" 无特权时会被
		 *   强制置 false）。本地先改会让界面显示一个原生并不接受的值，用户以为设成功了。
		 *
		 * ⚠️ "bubbleApply" 只传**差异字段**：merge 的语义是「缺省即保持原值」，
		 *   传全量会把没动过的字段也写一遍，一旦某个字段解析失败就整体回退。
		 *
		 * @param {object} props - "{ onBubbleChange?: Function }"（气泡开关变化时通知父级刷新）
		 */
		function mpBubblePanel(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_BUBBLE_ZH[key] ?? key;

			const [ap, setAp] = React.useState(null);
			const [open, setOpen] = React.useState({});

			/** 读一次全量外观。 */
			const read = React.useCallback(() => {
				const b = window.DSHAndroid;
				if (!b || typeof b.bubbleAppearance !== "function") { setAp(null); return; }
				try {
					const raw = b.bubbleAppearance();
					setAp(typeof raw === "string" ? JSON.parse(raw) : (raw || null));
				} catch (e) {
					console.warn("[dsh-android-mobile] 读外观失败:", e && e.message);
					setAp(null);
				}
			}, []);

			React.useEffect(() => { read(); }, [read]);

			/**
			 * 写一个字段（或一组字段），然后读回。
			 *
			 * @param {object} patch - 形如 "{ size: 56 }"。
			 */
			const apply = React.useCallback((patch) => {
				const b = window.DSHAndroid;
				if (!b || typeof b.bubbleApply !== "function") return;
				try {
					b.bubbleApply(JSON.stringify(patch));
					read();
				} catch (e) {
					console.warn("[dsh-android-mobile] 应用外观失败:", e && e.message);
				}
			}, [read]);

			if (!ap) {
				return React.createElement("div", { style: { ...S.intro, padding: "10px 0" } },
					t("panelUnavailable"));
			}

			// ── 基础控件 ───────────────────────────────────────────────

			/** 开关行（复用 swRow 的排版）。 */
			const sw = (label, hint, on, onToggle, disabled, why) =>
				React.createElement("div", { style: S.swRow, key: label },
					React.createElement("div", { style: S.swMain },
						React.createElement("span", { style: S.swName }, label),
						hint ? React.createElement("span", { style: S.swHint }, hint) : null,
						why ? React.createElement("span", { style: S.swWhy }, why) : null,
					),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": on ? "true" : "false",
						"aria-label": label,
						disabled: !!disabled,
						style: {
							...S.swTrack,
							background: disabled
								? "var(--dsw-alias-border-l2, #dcdcdc)"
								: (on ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)"),
							cursor: disabled ? "not-allowed" : "pointer",
							opacity: disabled ? 0.55 : 1,
						},
						onClick: disabled ? undefined : onToggle,
					}, React.createElement("span", { style: { ...S.swThumb, left: on ? "21px" : "3px" } })),
				);

			/**
			 * 滑块行。拖动时只更新本地显示，**松手才写盘**。
			 *
			 * ⚠️ 不这么拆的话，拖一次会触发几十次 "bubbleApply" + 几十次读回，
			 *   Shizuku/JSON 往返叠加起来会让滑块卡得像冻住。
			 */
			const slider = (label, key, min, max, step, fmt) => {
				const val = ap[key] ?? min;
				return React.createElement("div", { style: S.ctlRow, key },
					React.createElement("span", { style: S.ctlLabel }, label),
					React.createElement("input", {
						type: "range", min, max, step,
						// eslint-disable-next-line no-empty-function
						defaultValue: val,
						// ⚠️ 用 key 绑当前值：外部改动（读回）后强制重挂，让滑块跟上。
						key: key + ":" + val,
						style: S.slider,
						"aria-label": label,
						onInput: (e) => { e.target.dataset.live = e.target.value; },
						onChange: (e) => apply({ [key]: Number(e.target.value) }),
					}),
					React.createElement("span", { style: S.ctlValue },
						fmt ? fmt(val) : String(val)),
				);
			};

			/** 分段选择行。 */
			const seg = (label, key, options) =>
				React.createElement("div", { style: S.ctlRow, key },
					React.createElement("span", { style: S.ctlLabel }, label),
					React.createElement("div", { style: S.seg, role: "group", "aria-label": label },
						options.map((o) => React.createElement("button", {
							key: o.v, type: "button",
							"aria-pressed": ap[key] === o.v ? "true" : "false",
							style: { ...S.segBtn, ...(ap[key] === o.v ? S.segBtnOn : null) },
							onClick: () => apply({ [key]: o.v }),
						}, o.n)),
					),
				);

			/**
			 * 颜色行。空串表示「自动」，所以额外给一个复位按钮。
			 *
			 * @param {string} [autoText] 「自动」这个状态显示成什么字。
			 *   ⚠️ 面板**底色**的自动语义是**透明**（见原生 "DEFAULT_PANEL_BG = 0"），
			 *   所以那一行要显式写「透明」而不是「自动」—— 用户看不到"自动"
			 *   与"透明"的等号，而 "<input type="color">" 又根本选不出透明色。
			 */
			const color = (label, key, autoText) => {
				const cur = ap[key] || "";
				const autoLabel = autoText || t("autoColor");
				return React.createElement("div", { style: S.ctlRow, key },
					React.createElement("span", { style: S.ctlLabel }, label),
					cur
						? React.createElement("button", {
							type: "button", style: S.btnQuiet,
							onClick: () => apply({ [key]: "" }),
						}, autoLabel)
						: React.createElement("span", { style: S.ctlValue }, autoLabel),
					React.createElement("span", {
						style: {
							...S.swatchWrap,
							background: cur || "repeating-conic-gradient(#e0e0e0 0% 25%, #fff 0% 50%) 50% / 10px 10px",
						},
					},
						React.createElement("input", {
							type: "color",
							style: S.swatchInput,
							"aria-label": label,
							value: cur || "#2b6cb0",
							onChange: (e) => apply({ [key]: e.target.value }),
						}),
					),
				);
			};

			/** 可折叠分组。 */
			const acc = (id, title, count, children) => {
				const isOpen = !!open[id];
				return React.createElement("div", { style: S.acc, key: id },
					React.createElement("div", {
						style: S.accHead,
						role: "button",
						tabIndex: 0,
						"aria-expanded": isOpen ? "true" : "false",
						onClick: () => setOpen((v) => ({ ...v, [id]: !v[id] })),
						onKeyDown: (e) => {
							if (e.key === "Enter" || e.key === " ") {
								e.preventDefault();
								setOpen((v) => ({ ...v, [id]: !v[id] }));
							}
						},
					},
						React.createElement("span", null, title),
						React.createElement("span", { style: S.accCount },
							count + " · " + (isOpen ? t("collapse") : t("expand"))),
					),
					isOpen ? React.createElement("div", { style: S.accBody }, children) : null,
				);
			};

			const pct = (v) => Math.round((Number(v) || 0) * 100) + "%";

			// ── 分组 ───────────────────────────────────────────────────

			const appearanceGroup = acc("look", t("grpLook"), 9, [
				React.createElement("div", { style: S.ctlRow, key: "size" },
					React.createElement("span", { style: S.ctlLabel }, t("fSize")),
					React.createElement("input", {
						type: "range", min: 24, max: 320, step: 2,
						key: "size:" + ap.size,
						defaultValue: ap.size, style: S.slider,
						"aria-label": t("fSize"),
						onChange: (e) => apply({ size: Number(e.target.value) }),
					}),
					React.createElement("span", { style: S.ctlValue }, ap.size + "dp"),
				),
				seg(t("fShape"), "shape", [
					{ v: "circle", n: t("shapeCircle") },
					{ v: "square", n: t("shapeSquare") },
					{ v: "rounded", n: t("shapeRounded") },
				]),
				slider(t("fCorner"), "cornerRadius", 0, 80, 1, (v) => v + "dp"),
				slider(t("fOpacity"), "opacity", 0.05, 1, 0.05, pct),
				slider(t("fBgOpacity"), "bgOpacity", 0, 1, 0.05, pct),
				slider(t("fAvatarOpacity"), "avatarOpacity", 0, 1, 0.05, pct),
				slider(t("fAvatarScale"), "avatarScale", 0.2, 2, 0.05, (v) => Number(v).toFixed(2) + "x"),
				slider(t("fBorderWidth"), "borderWidth", 0, 8, 1, (v) => v + "dp"),
				color(t("fBorder"), "border"),
			]);

			const colorGroup = acc("color", t("grpColor"), 2, [
				color(t("fBg"), "bg"),
				color(t("fBg2"), "bg2"),
			]);

			const motionGroup = acc("motion", t("grpMotion"), 5, [
				React.createElement("div", { style: S.swRow, key: "thr" },
					React.createElement("div", { style: S.swMain },
						React.createElement("span", { style: S.swName }, t("throwName")),
						React.createElement("span", { style: S.swHint }, t("throwHint")),
					),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.throwEnabled ? "true" : "false",
						"aria-label": t("throwName"),
						style: {
							...S.swTrack,
							background: ap.throwEnabled ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ throwEnabled: !ap.throwEnabled }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.throwEnabled ? "21px" : "3px" } })),
				),
				React.createElement("div", { style: S.swRow, key: "shake" },
					React.createElement("div", { style: S.swMain },
						React.createElement("span", { style: S.swName }, t("shakeName")),
						React.createElement("span", { style: S.swHint }, t("shakeHint")),
					),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.shakeEnabled ? "true" : "false",
						"aria-label": t("shakeName"),
						style: {
							...S.swTrack,
							background: ap.shakeEnabled ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ shakeEnabled: !ap.shakeEnabled }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.shakeEnabled ? "21px" : "3px" } })),
				),
				React.createElement("div", { style: S.ctlRow, key: "edge" },
					React.createElement("span", { style: S.ctlLabel }, t("fEdgeSnap")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.edgeSnap ? "true" : "false",
						"aria-label": t("fEdgeSnap"),
						style: {
							...S.swTrack,
							background: ap.edgeSnap ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ edgeSnap: !ap.edgeSnap }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.edgeSnap ? "21px" : "3px" } })),
				),
				React.createElement("div", { style: S.ctlRow, key: "move" },
					React.createElement("span", { style: S.ctlLabel }, t("fMoveEnabled")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.moveEnabled ? "true" : "false",
						"aria-label": t("fMoveEnabled"),
						style: {
							...S.swTrack,
							background: ap.moveEnabled ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ moveEnabled: !ap.moveEnabled }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.moveEnabled ? "21px" : "3px" } })),
				),
				ap.moveEnabled ? slider(t("fMoveInterval"), "moveIntervalSec", 10, 3600, 5, (v) => v + "s") : null,
				ap.moveEnabled ? slider(t("fMoveMin"), "moveMinDp", 8, 400, 4, (v) => v + "dp") : null,
				ap.moveEnabled ? slider(t("fMoveMax"), "moveMaxDp", 8, 400, 4, (v) => v + "dp") : null,
				React.createElement("div", { style: S.ctlRow, key: "flip" },
					React.createElement("span", { style: S.ctlLabel }, t("fFlipOnTurn")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.flipOnTurn ? "true" : "false",
						"aria-label": t("fFlipOnTurn"),
						style: {
							...S.swTrack,
							background: ap.flipOnTurn ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ flipOnTurn: !ap.flipOnTurn }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.flipOnTurn ? "21px" : "3px" } })),
				),
			]);

			const animGroup = acc("anim", t("grpAnim"), 5, [
				slider(t("fFrameInterval"), "frameIntervalMs", 20, 2000, 10, (v) => v + "ms"),
				React.createElement("div", { style: S.ctlRow, key: "spin" },
					React.createElement("span", { style: S.ctlLabel }, t("fSpin")),
					React.createElement("input", {
						type: "range", min: -720, max: 720, step: 15,
						key: "spin:" + ap.spin,
						defaultValue: ap.spin, style: S.slider,
						"aria-label": t("fSpin"),
						onChange: (e) => apply({ spin: Number(e.target.value) }),
					}),
					React.createElement("span", { style: S.ctlValue }, ap.spin + "°"),
				),
				React.createElement("div", { style: S.ctlRow, key: "pulse" },
					React.createElement("span", { style: S.ctlLabel }, t("fPulse")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.pulse ? "true" : "false",
						"aria-label": t("fPulse"),
						style: {
							...S.swTrack,
							background: ap.pulse ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ pulse: !ap.pulse }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.pulse ? "21px" : "3px" } })),
				),
				React.createElement("div", { style: S.ctlRow, key: "dot" },
					React.createElement("span", { style: S.ctlLabel }, t("fShowDot")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.showDot ? "true" : "false",
						"aria-label": t("fShowDot"),
						style: {
							...S.swTrack,
							background: ap.showDot ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ showDot: !ap.showDot }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.showDot ? "21px" : "3px" } })),
				),
				slider(t("fStatusImageSize"), "statusImageSizeDp", 32, 200, 4, (v) => v + "dp"),
			]);

			const panelGroup = acc("panel", t("grpPanel"), 4, [
				slider(t("fPanelWidth"), "panelWidth", 180, 600, 10, (v) => v + "dp"),
				slider(t("fPanelHeight"), "panelHeight", 0, 1200, 20,
					(v) => (Number(v) === 0 ? t("autoSize") : v + "dp")),
				slider(t("fStatusAutoHide"), "statusAutoHideMs", 0, 10000, 500,
					(v) => (Number(v) === 0 ? t("neverHide") : (Number(v) / 1000).toFixed(1) + "s")),
				color(t("fPanelBg"), "panelBg", t("panelBgAuto")),
				color(t("fPanelText"), "panelText"),
				color(t("fPanelAccent"), "panelAccent"),
			]);

			// ── 余额徽标（嵌套对象，需要另一套 helper）─────────────────────
			//
			// ⚠️ 上面那些 helper 只会写**顶层字段**（`apply({ [key]: v })`），
			//   而徽标是一整个 `balanceBadge` 子对象。所以这里另起一套，
			//   写的时候**整包下发**：原生 `BalanceBadge.merge` 是按子字段判
			//   "缺省即保持"的，整包下发＝把当前值原样带上，两边语义一致。
			//
			// ⚠️ 不要试图让 `apply` 支持点号路径：那条路会顺带改到别处
			//   （`apply` 是共享的），风险远大于在这里多写二十行。
			const bb = ap.balanceBadge || {};
			/** 当前徽标 + 覆盖项 → 整包。 */
			const bbPatch = (over) => ({ balanceBadge: { ...bb, ...over } });

			const bbSw = (label, key, hint) => {
				const on = bb[key] === true;
				// ⚠️ **hint 不能塞在 116px 的标签列里**：`S.ctlLabel` 带 `ellipsis`，
				//   挤进去会把主标签截成「显示余额徽标在…」（真机截图确认过）。
				//   所以标签只放 label 本身，hint 作为一个整行的副标题挂在下面。
				return React.createElement("div", { key: "bb-" + key },
					React.createElement("div", { style: S.ctlRow },
						React.createElement("span", { style: S.ctlLabel }, label),
						React.createElement("button", {
							type: "button", role: "switch",
							"aria-checked": on ? "true" : "false",
							"aria-label": label,
							style: {
								...S.swTrack,
								background: on
									? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)"
									: "var(--dsw-alias-border-l2, #c8c8c8)",
							},
							onClick: () => apply(bbPatch({ [key]: !on })),
						}, React.createElement("span", { style: { ...S.swThumb, left: on ? "21px" : "3px" } })),
					),
					hint ? React.createElement("div", { style: { ...S.swHint, padding: "-2px 0 6px" } }, hint) : null,
				);
			};

			const bbSlider = (label, key, min, max, step, fmt) => {
				const val = typeof bb[key] === "number" ? bb[key] : min;
				return React.createElement("div", { style: S.ctlRow, key: "bb-" + key },
					React.createElement("span", { style: S.ctlLabel }, label),
					React.createElement("input", {
						type: "range", min, max, step,
						defaultValue: val,
						key: "bb-" + key + ":" + val,
						style: S.slider,
						"aria-label": label,
						onInput: (e) => { e.target.dataset.live = e.target.value; },
						onChange: (e) => apply(bbPatch({ [key]: Number(e.target.value) })),
					}),
					React.createElement("span", { style: S.ctlValue }, fmt ? fmt(val) : String(val)),
				);
			};

			const bbSeg = (label, key, options) =>
				React.createElement("div", { style: S.ctlRow, key: "bb-" + key },
					React.createElement("span", { style: S.ctlLabel }, label),
					React.createElement("div", { style: S.seg, role: "group", "aria-label": label },
						options.map((o) => React.createElement("button", {
							key: o.v, type: "button",
							"aria-pressed": bb[key] === o.v ? "true" : "false",
							style: { ...S.segBtn, ...(bb[key] === o.v ? S.segBtnOn : null) },
							onClick: () => apply(bbPatch({ [key]: o.v })),
						}, o.n)),
					),
				);

			/**
			 * 颜色行（"跟随主题"是可选项，用空串表示）。
			 *
			 * ⚠️ "type="color"" 不能表达"无值"，所以配一个「跟随主题」按钮复位。
			 *   没有它的话，用户一旦点了取色器就再也回不到跟随主题。
			 */
			const bbColor = (label, key) => {
				const cur = typeof bb[key] === "string" ? bb[key] : "";
				// `<input type="color">` 只认 `#RRGGBB`；带 alpha 的 `#AARRGGBB`
				// 会被浏览器判为非法值并回退成黑色，所以这里截末 6 位。
				const hex = /^#[0-9a-fA-F]{8}$/.test(cur) ? "#" + cur.slice(3) : (cur || "#888888");
				return React.createElement("div", { style: S.ctlRow, key: "bb-" + key },
					React.createElement("span", { style: S.ctlLabel }, label),
					// ⚠️⚠️ **必须包一层 `swatchWrap`**，不能把 `swatchInput` 直接放进
					//   `ctlRow`。`swatchInput` 是 `position: absolute`，它需要一个
					//   `position: relative` 的父元素当包含块；直接放进来时包含块会
					//   退化成整个面板，透明的 input 铺满全屏 ——
					//   真机症状：面板不能上下滑，且点分组标题会弹系统颜色选择器。
					React.createElement("span", {
						style: {
							...S.swatchWrap,
							background: cur || "repeating-conic-gradient(#e0e0e0 0% 25%, #fff 0% 50%) 50% / 10px 10px",
						},
					},
						React.createElement("input", {
							type: "color",
							value: hex,
							style: S.swatchInput,
							"aria-label": label,
							onChange: (e) => apply(bbPatch({ [key]: e.target.value })),
						}),
					),
					React.createElement("button", {
						type: "button",
						style: { ...S.segBtn, ...(cur === "" ? S.segBtnOn : null) },
						"aria-pressed": cur === "" ? "true" : "false",
						onClick: () => apply(bbPatch({ [key]: "" })),
					}, t("bbFollow")),
				);
			};

			/**
			 * 徽标显示内容的**可视化编辑器**。
			 *
			 * ## 为什么不能直接给用户一个装 "{balance}" 的输入框
			 *
			 * 用户原话：「这个什么显示内容里面，你为什么显示剩？然后 Balance。你觉得这样
			 * 对用户体验好吗？还有就是上面写的点下面的词加进显示内容，点不动。」
			 *
			 * 两个问题都成立：
			 *  1. 输入框里躺着一串 "剩 {balance}{balance}…" —— 内部占位符直接摊给用户看，
			 *     这是**实现细节泄漏**，不是界面。
			 *  2. 点 chip 会追加一个 token，但界面上**没有任何变化可见**（输入框是
			 *     uncontrolled、且不重渲染），所以看起来就是"点不动"。真机上因此被
			 *     连点十几下，模板里堆了 12 个 "{balance}"。
			 *
			 * ## 现在的形态
			 *
			 * 结构变成三段，全部可视化：
			 *  - **上面一行是「成品长什么样」**：直接把当前配方渲染成示例徽标，
			 *    用户改任何东西都能立刻看到结果；
			 *  - **中间是配方本身**：每个词是一个可删的胶囊（"剩余" "余额" …），
			 *    点胶囊右侧的 × 就删掉它 —— 删得掉才叫"编辑"；
			 *  - **下面是可加的词**：点一下**加在末尾**，但**重复的词直接禁用**
			 *    （已经用过就置灰），从根上杜绝"点十几下堆成 12 个"。
			 *
			 * 自定义文字（比如 "剩"）也做成胶囊，但不可点，只作说明。
			 */

			/** token → 中文名。 */
			const BB_TOKEN_LABEL = {
				balance: "余额",
				recharge: "充值",
				bonus: "赠送",
				currency: "币种",
				cost: "本次花费",
				costSession: "本会话花费",
			};

			/**
			 * 把模板串解析成「词序列」。
			 *
			 * "剩 {balance}" ⇒ "[{lit:"剩 "}, {tok:"balance"}]"
			 *
			 * ⚠️ 解析失败的 "{xxx}"（未知 token）**按字面保留**，不要丢掉 ——
			 *   丢掉等于悄悄改用户的东西。它会在界面上显示成一个灰色的未知胶囊。
			 */
			const bbParse = (tpl) => {
				const s = typeof tpl === "string" ? tpl : "";
				const out = [];
				const re = /\{([a-zA-Z0-9_]+)\}/g;
				let last = 0;
				let m;
				while ((m = re.exec(s)) !== null) {
					if (m.index > last) out.push({ lit: s.slice(last, m.index) });
					out.push({ tok: m[1], known: !!BB_TOKEN_LABEL[m[1]] });
					last = m.index + m[0].length;
				}
				if (last < s.length) out.push({ lit: s.slice(last) });
				return out;
			};

			/** 词序列 → 模板串。 */
			const bbJoin = (parts) => parts.map((p) => (p.tok ? "{" + p.tok + "}" : p.lit)).join("");

			/**
			 * 干跑一次渲染，只用于预览（不碰原生）。
			 *
			 * 用假数据是为了让用户在**还没登录**时也能看清版式 —— 真实余额由
			 * 原生徽标显示，这里只是"长什么样"的示意。
			 */
			const bbPreviewText = (tpl) => {
				const sample = {
					balance: "138.56",
					recharge: "100.00",
					bonus: "38.56",
					currency: "¥",
					cost: "0.0031",
					costSession: "0.0420",
				};
				return String(tpl || "").replace(/\{([a-zA-Z0-9_]+)\}/g, (all, key) => (
					Object.prototype.hasOwnProperty.call(sample, key) ? sample[key] : all
				));
			};

			const bbTemplate = () => {
				const tpl = typeof bb.template === "string" && bb.template !== ""
					? bb.template
					: "{currency}{balance}";
				const parts = bbParse(tpl);
				const used = new Set(parts.filter((p) => p.tok).map((p) => p.tok));

				/** 删掉第 i 个词（literal 也可以删）。 */
				const removeAt = (i) => {
					const next = parts.slice();
					next.splice(i, 1);
					const v = bbJoin(next);
					apply(bbPatch({ template: v.trim() === "" ? "{currency}{balance}" : v }));
				};

				return React.createElement("div", { style: S.bbEditor, key: "bb-template" },
					React.createElement("span", { style: S.ctlLabel }, t("fBbTemplate")),

					// ── ① 成品预览 ────────────────────────────────────
					React.createElement("div", { style: S.bbPreviewRow },
						React.createElement("span", { style: S.swHint }, t("bbPreviewHint")),
						React.createElement("span", { style: S.bbPreviewBadge },
							bbPreviewText(tpl) || "—"),
					),

					// ── ② 当前配方（可删） ────────────────────────────
					React.createElement("div", { style: S.bbTokenRow },
						...parts.map((p, i) => React.createElement("span", {
							key: i,
							style: p.tok && !p.known ? S.bbChipUnknown : (p.tok ? S.bbChip : S.bbChipLit),
						},
							React.createElement("span", null, p.tok
								? (BB_TOKEN_LABEL[p.tok] || p.tok)
								: p.lit),
							React.createElement("button", {
								type: "button",
								style: S.bbChipX,
								"aria-label": t("bbRemove") + " " + (p.tok ? (BB_TOKEN_LABEL[p.tok] || p.tok) : p.lit),
								onClick: () => removeAt(i),
							}, "×"),
						)),
						parts.length === 0
							? React.createElement("span", { style: S.swHint }, t("bbEmpty"))
							: null,
					),

					// ── ③ 可加的词（已用过就禁用） ────────────────────
					React.createElement("div", { style: S.swHint }, t("bbTokenHint")),
					React.createElement("div", { style: S.bbTokenRow },
						...Object.keys(BB_TOKEN_LABEL).map((tok) => {
							const already = used.has(tok);
							return React.createElement("button", {
								key: tok,
								type: "button",
								disabled: already,
								style: {
									...S.bbToken,
									...(already ? S.bbTokenUsed : null),
								},
								"aria-label": BB_TOKEN_LABEL[tok],
								onClick: () => {
									if (already) return;
									apply(bbPatch({ template: tpl + "{" + tok + "}" }));
								},
							}, BB_TOKEN_LABEL[tok]);
						}),
					),

					// ── ④ 恢复默认 ────────────────────────────────────
					//
					// 用户把顺序点成 `{balance}{currency}` 之后，渲染出来是
					// `138.56¥` —— 币种跑到金额后面，看着就是错的。胶囊只能
					// 一个个删再一个个加，太笨；给一个一键回到 `¥138.56` 的出口。
					React.createElement("div", { style: { display: "flex", gap: "8px" } },
						React.createElement("button", {
							type: "button",
							style: S.btnQuiet,
							onClick: () => apply(bbPatch({ template: "{currency}{balance}" })),
						}, t("bbReset")),
					),
				);
			};

			const badgeGroup = acc("badge", t("grpBalance"), 6, [
				bbSw(t("fBbEnabled"), "enabled", t("bbEnabledHint")),
				bbSeg(t("fBbAnchor"), "anchor", [
					{ v: "top-right", n: t("bbAnchorTR") },
					{ v: "top-left", n: t("bbAnchorTL") },
					{ v: "bottom-right", n: t("bbAnchorBR") },
					{ v: "bottom-left", n: t("bbAnchorBL") },
					{ v: "above", n: t("bbAnchorAbove") },
					{ v: "below", n: t("bbAnchorBelow") },
				]),
				bbTemplate(),
				bbSlider(t("fBbGap"), "gapDp", -60, 120, 2, (v) => v + "dp"),
				bbSlider(t("fBbFontSize"), "fontSizeSp", 7, 40, 1, (v) => v + "sp"),
				bbSlider(t("fBbRadius"), "radiusDp", 0, 100, 1, (v) => v + "dp"),
				bbSlider(t("fBbPaddingH"), "paddingHDp", 0, 40, 1, (v) => v + "dp"),
				bbSlider(t("fBbPaddingV"), "paddingVDp", 0, 40, 1, (v) => v + "dp"),
				bbSlider(t("fBbMaxWidth"), "maxWidthDp", 0, 600, 10,
					(v) => (Number(v) === 0 ? t("autoSize") : v + "dp")),
				bbSlider(t("fBbBorderWidth"), "borderWidthDp", 0, 8, 1, (v) => v + "dp"),
				bbColor(t("fBbBg"), "bg", t("panelBgAuto")),
				bbColor(t("fBbFg"), "fg"),
				bbColor(t("fBbBorder"), "border"),
				bbSw(t("fBbCostFlash"), "showCostFlash"),
				bbSw(t("fBbHideIdle"), "hideWhenIdle"),
				bbSw(t("fBbTurnEnd"), "refreshOnTurnEnd"),
				bbSlider(t("fBbEvery"), "refreshEverySec", 0, 3600, 30,
					(v) => (Number(v) === 0 ? t("bbOff") : (Number(v) >= 60
						? (Number(v) / 60).toFixed(0) + t("bbMinute")
						: v + "s"))),
				bbSw(t("fBbTapRefresh"), "refreshOnTap"),
				bbSlider(t("fBbTapMin"), "tapRefreshMinIntervalSec", 1, 600, 1, (v) => v + "s"),
			]);

			const stateGroup = acc("state", t("grpState"), 1, [
				seg(t("fState"), "state", [
					{ v: "auto", n: t("stAuto") },
					{ v: "collapsed", n: t("stCollapsed") },
					{ v: "expanded", n: t("stExpanded") },
				]),
			]);

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("panelTitle")),
				React.createElement("p", { style: S.intro }, t("panelIntro")),
				stateGroup,
				appearanceGroup,
				colorGroup,
				motionGroup,
				animGroup,
				panelGroup,
				badgeGroup,
				React.createElement("div", { style: { ...S.intro, marginTop: "12px" } },
					React.createElement("button", {
						type: "button", style: S.btn, onClick: read,
					}, t("recheck")),
				),
			);
		}

		function mpBubbleSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_BUBBLE_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			const [showDetails, setShowDetails] = React.useState(false);
			// 键盘避让的状态：开关在 SharedPreferences、无障碍授权在 Settings.Secure，
			// 两样都只能从原生桥读（与气泡开关同一套事实来源）。
			const [kb, setKb] = React.useState({ phase: "loading" });

			/**
			 * 从原生读一次状态。
			 *
			 * ⚠️ 桥**可能不存在**（非 App 环境、或桥被清理），所以先查再调 ——
			 *   直接调会抛 "TypeError: Cannot read properties of undefined"。
			 */
			const refresh = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.bubbleState !== "function") {
					setState({ phase: "ready", enabled: false, granted: false, noBridge: true });
					return;
				}
				try {
					// 桥返回的是 JSON 字符串（@JavascriptInterface 只支持基本类型与 String）。
					const raw = bridge.bubbleState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setState({
						phase: "ready",
						enabled: !!o.enabled,
						granted: !!o.granted,
						noBridge: false,
						throwEnabled: !!o.throwEnabled,
						imeFlingEnabled: !!o.imeFlingEnabled,
						imeFlingAvailable: !!o.imeFlingAvailable,
						imeFlingReason: o.imeFlingReason || null,
						shakeEnabled: !!o.shakeEnabled,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取气泡状态失败:", e && e.message);
					setState({ phase: "ready", enabled: false, granted: false, noBridge: true });
				}
			}, []);

			React.useEffect(() => { refresh(); }, [refresh]);

			/**
			 * 读「键盘避让」状态。
			 *
			 * ⚠️ 单独一个方法而不是塞进 `bubbleState()`：两者更新时机不同 ——
			 *   无障碍授权只在用户离开本页去系统设置时才会变，而气泡开关在本页
			 *   就会被改。分开读可以各自刷新，互不牵连。
			 */
			const refreshKb = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.keyboardAvoidState !== "function") {
					setKb({ phase: "ready", enabled: false, a11y: false, source: "none", noBridge: true });
					return;
				}
				try {
					const raw = bridge.keyboardAvoidState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					// `source` 是原生算好的最终判据：`a11y`（快）/ `shell`（慢）/
					// `none`（两条路都没有 ⇒ 开关必须置灰）。页面不自己推导，
					// 否则同一套优先级要在两处各写一遍，容易漂。
					setKb({
						phase: "ready",
						enabled: !!o.enabled,
						a11y: !!o.a11y,
						a11yOn: !!o.a11yOn,
						shell: !!o.shell,
						source: o.source || "none",
						noBridge: false,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取键盘避让状态失败:", e && e.message);
					setKb({ phase: "ready", enabled: false, a11y: false, source: "none", noBridge: true });
				}
			}, []);

			React.useEffect(() => { refreshKb(); }, [refreshKb]);

			/**
			 * 从系统设置页回来后刷新。
			 *
			 * ⚠️ 授权是**在别的界面**完成的，本页拿不到任何回调。只能监听
			 *   "visibilitychange"（用户切回来时触发）。不加这个的话，用户授完权
			 *   回来看到的还是「未授权」，会以为没生效。
			 */
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) { refresh(); refreshKb(); } };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [refresh, refreshKb]);

			const toggle = () => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.bubbleSetEnabled !== "function") return;
				try {
					bridge.bubbleSetEnabled(!state.enabled);
					// 乐观更新：桥是同步的，但立刻重读一次更可靠（也顺带验证写入生效）。
					refresh();
				} catch (e) {
					console.warn("[dsh-android-mobile] 切换气泡失败:", e && e.message);
				}
			};

			const grant = () => {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.bubbleRequestOverlay === "function") {
					try { bridge.bubbleRequestOverlay(); } catch (e) { /* 下面统一提示 */ }
				}
			};

			/**
			 * 一枚自绘开关。
			 *
			 * @param {string} name - 名称。
			 * @param {string} hint - 一句说明。
			 * @param {boolean} on - 当前值。
			 * @param {Function} onToggle - 点击回调。
			 * @param {boolean} disabled - 置灰（无能力时用）。
			 * @param {string|null} why - 置灰原因，显示在说明下方。
			 */
			const sw = (name, hint, on, onToggle, disabled, why) =>
				React.createElement("div", { style: S.swRow, key: name },
					React.createElement("div", { style: S.swMain },
						React.createElement("span", { style: S.swName }, name),
						React.createElement("span", { style: S.swHint }, hint),
						why ? React.createElement("span", { style: S.swWhy }, why) : null,
					),
					React.createElement("button", {
						type: "button",
						role: "switch",
						"aria-checked": on ? "true" : "false",
						"aria-label": name,
						disabled: !!disabled,
						style: {
							...S.swTrack,
							background: disabled
								? "var(--dsw-alias-border-l2, #dcdcdc)"
								: (on ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)"),
							cursor: disabled ? "not-allowed" : "pointer",
							opacity: disabled ? 0.55 : 1,
						},
						onClick: disabled ? undefined : onToggle,
					}, React.createElement("span", {
						style: { ...S.swThumb, left: on ? "21px" : "3px" },
					})),
				);

			// ── 键盘避让的两个动作 ────────────────────────────────────────

			const toggleKb = () => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.keyboardAvoidSetEnabled !== "function") return;
				try {
					bridge.keyboardAvoidSetEnabled(!kb.enabled);
					// 同步重读：桥是同步的，读回来才是"落盘后的事实"。
					refreshKb();
				} catch (e) {
					console.warn("[dsh-android-mobile] 切换键盘避让失败:", e && e.message);
				}
			};

			const grantKb = () => {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.keyboardAvoidRequestPermission === "function") {
					try { bridge.keyboardAvoidRequestPermission(); } catch (e) { /* 由原生侧记日志 */ }
				}
			};

			if (state.phase === "loading" || kb.phase === "loading") {
				return React.createElement("div", { style: S.section },
					React.createElement("h2", { style: S.heading }, t("title")),
					React.createElement("p", { style: S.intro }, t("statusUnknown")),
				);
			}

			const noBridge = state.noBridge;
			const ready = state.enabled && state.granted;
			const tone = ready ? MP_PRIV_TONE.on : MP_PRIV_TONE.off;
			const badgeText = ready ? t("statusOn") : t("statusOff");

			// 「下一步」按状态级联挑一条 —— 与特权权限分节同一套思路：
			// 不让用户自己从几行技术明细里翻译出该做什么。
			let nextText = t("nextAllGood");
			let nextBtn = null;
			if (noBridge) {
				nextText = t("noBridge");
			} else if (!state.granted) {
				nextText = t("nextNeedPerm");
				nextBtn = React.createElement("button", { style: S.btnPrimary, onClick: grant }, t("grant"));
			} else if (!state.enabled) {
				nextText = t("nextNeedEnable");
				nextBtn = React.createElement("button", { style: S.btnPrimary, onClick: toggle }, t("enable"));
			} else {
				nextBtn = React.createElement("button", { style: S.btnQuiet, onClick: toggle }, t("disable"));
			}

			// 键盘避让的「下一步」——只给结论文案，动作交给卡片里的开关与按钮。
			//
			// `kb.source` 是原生算好的最终判据（`a11y` / `shell` / `none`），
			// 页面不自己推导优先级。三条结论互斥且按"用户还差什么"排序：
			//   none   → 什么都做不了，开关置灰
			//   shell  → 能用但有延迟，建议开无障碍（这是本卡片存在的意义）
			//   a11y   → 已就绪
			const kbNoSource = !kb.noBridge && kb.source === "none";
			const kbReady = kb.enabled && !kbNoSource &&
				(kb.source === "a11y" || kb.source === "shell");
			const kbTone = kbReady ? MP_PRIV_TONE.on : MP_PRIV_TONE.off;
			let kbNextText = t("kbNextAllGood");
			if (kb.noBridge) {
				kbNextText = t("noBridge");
			} else if (kbNoSource) {
				kbNextText = t("kbNextNoSource");
			} else if (!kb.enabled) {
				kbNextText = t("kbNextDisabled");
			} else if (kb.source === "shell") {
				kbNextText = t("kbNextShellSlow");
			}

			// 开关的样式：做成真正的 switch（宽 46 / 高 26 的轨道 + 白色圆钮）。
			// 之前这里是一个「打开开关 / 关闭」按钮 —— 用户明确要求改成开关形态：
			// 开关一眼能看出"当前是开还是关"，按钮要靠读文字才知道。
			const kbSwitchRow = { display: "flex", alignItems: "center", gap: "10px" };
			const kbSwitchLabel = { flex: "1 1 auto", fontSize: "13px", lineHeight: "19px" };
			const kbSwitchTrack = (on, disabled) => ({
				position: "relative", flex: "0 0 auto", width: "46px", height: "26px",
				borderRadius: "13px", border: "none", padding: 0,
				cursor: disabled ? "default" : "pointer",
				opacity: disabled ? 0.5 : 1,
				background: on ? MP_PRIV_TONE.on.color : "var(--dsw-alias-border-l2, #cfcfcf)",
				transition: "background .18s",
			});
			const kbSwitchKnob = (on) => ({
				position: "absolute", top: "3px", left: on ? "23px" : "3px",
				width: "20px", height: "20px", borderRadius: "10px",
				background: "#fff", boxShadow: "0 1px 2px rgba(0,0,0,.25)",
				transition: "left .18s",
			});

			const dot = (ok) => React.createElement("span", {
				style: { ...S.dot, background: ok ? MP_PRIV_TONE.on.color : MP_PRIV_TONE.off.color },
			});
			const row = (label, ok, value) => React.createElement("div", { style: S.row, key: label },
				dot(ok),
				React.createElement("span", { style: S.rowLabel }, label),
				React.createElement("span", { style: S.rowValue }, value),
			);

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.cardHead },
						React.createElement("h3", { style: S.cardTitle }, t("statusTitle")),
						React.createElement("span", {
							style: { ...S.badge, color: tone.color, background: tone.bg },
						}, badgeText),
					),
					React.createElement("div", { style: S.next },
						React.createElement("span", { style: S.nextText }, nextText),
						nextBtn ? React.createElement("div", { style: S.actions }, nextBtn) : null,
					),
					React.createElement("div", { style: S.actions },
						React.createElement("button", { style: S.btn, onClick: refresh }, t("recheck")),
						React.createElement("button", {
							style: S.btnQuiet,
							onClick: () => setShowDetails((v) => !v),
						}, showDetails ? t("hideDetails") : t("showDetails")),
					),
					showDetails ? React.createElement("div", { style: S.details },
						row(t("rowSwitch"), state.enabled, state.enabled ? t("on") : t("off")),
						row(t("rowPerm"), state.granted, state.granted ? t("granted") : t("notGranted")),
					) : null,
				),

				// ── 甩动手感：**不在这里放** ─────────────────────────────
				//
				// 用户原话：「下面明明已经有了选项，你为什么在界面的最上面还要留有开关呢？
				// 你留有也就算了，你还有 bug」。
				//
				// 确实如此：拖拽抛掷 / 晃动甩飞 / 键盘顶飞 三个开关，在下面
				// `mpBubblePanel` 的「动作与交互」分组里**已经有完整的一份**，
				// 而且那一份走 `bubbleApply`（改完立刻推给活的球）。
				// 上面这一份走 `setMotion` → 原生只 `save` 不 `apply`，
				// 于是同一个功能两个入口、两种行为 —— 上面那个还慢半拍。
				//
				// 所以整块删掉，只留下方那一份。少一处入口，少一处不一致。
				// （原生侧已顺手修好 `bubbleSetThrow` / `bubbleSetShake` /
				//  `bubbleSetImeFling` 只落盘不生效的问题，AI 工具与其它调用方
				//  仍然走那三个桥方法，行为与这里一致。）
				// ── 外观与手感控制面板 ──────────────────────────────
				// 单独一个组件：这里要摆 BubbleAppearance 的全部可调项，
				// 撑进本分节会让「开关气泡 / 权限状态」那段首屏失控。
				React.createElement(mpBubblePanel, { t }),

				// ── 键盘避让：与气泡同一套卡片结构（结论优先 + 明细可折叠）──
				//
				// ⚠️ 它单独一张卡而不是塞进上面那张：权限是**另一件事**
				//   （无障碍 vs 悬浮窗），塞进一张卡会让"当前状态"这个徽章
				//   同时代表两套权限，用户没法判断还差哪一步。
				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.cardHead },
						React.createElement("h3", { style: S.cardTitle }, t("kbTitle")),
						React.createElement("span", {
							style: { ...S.badge, color: kbTone.color, background: kbTone.bg },
						}, kbReady ? t("statusOn") : t("statusOff")),
					),
					// 开关行：功能开关本体，点一下立即生效（原生侧推给正在显示的球）。
					//
					// ⚠️ `kbNoSource` 时置灰：两条检测路径都没有（既没开无障碍、
					//   也没有 Shizuku/root），打开这个开关不会有任何行为 —— 让用户
					//   打开一个注定不生效的开关，比直接说明原因更糟。
					React.createElement("div", { style: kbSwitchRow },
						React.createElement("span", { style: kbSwitchLabel }, t("kbRowSwitch")),
						React.createElement("button", {
							role: "switch",
							"aria-checked": kb.enabled,
							disabled: kb.noBridge || kbNoSource,
							title: kb.enabled ? t("on") : t("off"),
							onClick: toggleKb,
							style: kbSwitchTrack(kb.enabled, kb.noBridge || kbNoSource),
						}, React.createElement("span", { style: kbSwitchKnob(kb.enabled) })),
					),
					// 权限行：没授权时给一个明确的「去开启权限」按钮。
					// 开关开着时用实心（这才是当前该做的一步）；开关关着时降级成描边 ——
					// 功能还没开就先把主按钮推给用户，会显得在硬塞权限。
					//
					// ⚠️ 已经走 SHELL（有特权但慢）时也显示这个按钮：那条路延迟 2 秒起，
					//   开无障碍能把延迟压到一个数量级以下，正是本卡片要推的事。
					React.createElement("div", { style: S.next },
						React.createElement("span", { style: S.nextText }, kbNextText),
						(!kb.noBridge && !kb.a11y)
							? React.createElement("div", { style: S.actions },
								React.createElement("button", {
									style: kb.enabled ? S.btnPrimary : S.btn,
									onClick: grantKb,
								}, t("kbGrant")))
							: null,
					),
					React.createElement("div", { style: S.actions },
						React.createElement("button", { style: S.btn, onClick: refreshKb }, t("recheck")),
					),
					React.createElement("div", { style: S.details },
						row(t("kbRowPerm"), kb.a11y, kb.a11y ? t("granted") : t("notGranted")),
						row(t("kbRowSource"), kb.source !== "none",
							kb.source === "a11y" ? t("kbSourceA11y")
								: kb.source === "shell" ? t("kbSourceShell")
								: t("kbSourceNone")),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("kbHowTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("kbHow1"), t("kbHow2"), t("kbHow3")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("how1"), t("how2"), t("how3"), t("how4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「悬浮气泡」注册进设置页一级导航。
		 *
		 * 与特权权限分节同一套接缝（"settings.section"），所以不需要碰导航 DOM。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterBubbleSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过悬浮气泡分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_BUBBLE_NS);
					scope.effect(
						() => scope.locale.register(MP_BUBBLE_NS, { zh: MP_BUBBLE_ZH, en: MP_BUBBLE_EN }),
						"dsh-android-mobile: 悬浮气泡字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-bubble",
						// 紧跟在「特权权限」（25）之后。
						order: 26,
						label: () => t("nav"),
						locale: MP_BUBBLE_NS,
					}, mpBubbleSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册悬浮气泡分节失败:", e && (e.stack || e.message || e));
			}
		}

		/* ══ 「交流群」分节：二维码 + 群号 ══

		   ⚠️ **二维码必须内联成 data URI，不能放成独立图片文件。**
		     官方 "dsh-client-modules" 的 "/plugins/<id>/<file>" 路由**只服务
		     "client.js" 与它的 sourcemap**（"index.js" 的 "PLUGIN_ROUTE" 与
		     "chunkUrl()" 都写死了这两个后缀），**没有**通用静态资源目录。
		     把二维码写成 "./qr.webp" 会得到一个 404 的碎图 —— 而且失败方式是
		     静默的（"<img>" 加载失败不抛异常，只是不显示）。
		     插件自身也没有别的落地目录可以断言「引擎一定会把它对客户端暴露」。

		   格式选 WebP 而非 PNG：同一张图 PNG 要 93~129 KB，WebP(q92) 只要
		   31~70 KB，体积约 1/3。而 WebP 从 Chrome 32 就有解码支持，远低于本
		     项目的最低内核门槛（"MIN_WEBVIEW_MAJOR_COMPAT = 74"），不构成新约束。

		   ⚠️ 源图是**彩色圆点风格**的二维码，"cv2.QRCodeDetector" 解不出来
		     （实测原图/裁剪/灰度/二值化/放大全 FAIL），换 "zxing-cpp" 立刻成功。
		     验证这类图的可扫性**必须用 zxing**，别因为 OpenCV 报 FAIL 就判定图片
		     坏了 —— 那是解码器对非标准模块形状的识别能力差异，不是图的问题。

		   可扫性验证方式（比「肉眼看没问题」可靠）：把 data URI 按真实布局
		     （视口 390 CSS px + "--force-device-scale-factor=3"）用 Chrome 渲染成
		     截图，再交给 zxing 解码。400/512/640/852 四档源图**全部解码成功**，
		     说明「内联 → WebP 解码 → CSS 缩到 240px → dpr=3 渲染」整条链路的
		     余量都够。 */
		const MP_CONTACT_NS = "dsh-android-mobile.contact";
		/** 群号：从二维码图里读出的群名「DSHAPP交流群3」下方的数字。 */
		const MP_CONTACT_GROUP_NO = "1121898890";
		/** 二维码内联 data URI（WebP）。生成方式与可扫性验证见上方注释。 */
		const MP_CONTACT_QR = "data:image/webp;base64,UklGRqILAQBXRUJQVlA4IJYLAQCQZQOdASq8ArwCPiUQh0IhoSEYqsUgGAJEsTdsfiND94smw2/sf+J/YezLl3zh91/x/+D/zf9y/c/5aOL+o7xF90/w3+L/tf7bfcF/M/ZHuI66/8/3je9n5r+q/7j+5/5X9svmv/rP+N/kf9N+wP0y/on+C/3/+L/fv6Bf5L/Qv97/cf8T+2P0w/7v/l/2nvC/xH/d/LP4Ef1z/V/uT/4PhM/4v7Y+8/+r/739xP/H8kv9a/3X/6/5v/o7n/0Sv3w///s4f+P92f/J8xH9X/5n7n/9/3p////wu3/6O/lD/gPWf8s/m/9T/dP8x5z+h33d+5/47/o/5j5o/4TQP2kai/yr78fz/73/pfgD/g/tB43/L76P9gX8f/of+4/wn7sf43iEuJ/63/t/2/sC+8H4X9kf9H7Ov4n/s/yHq9++/7r9oPgB793wkvX/YC/p/+o9XH/U8s3177Ef9G/yiFKxjKXvKKctXHzJZAP/mdg2WghCEHFyZXt6sVIT5ksloILouBMYvZYwcYGcJ/N0cQjgP9ZbfNSLcSUK9mTSNFqbNQ9kHSxwcY3fwl1/nljvARwW3MN1uKAxUiJNsodSQJ4OoOPQH/rbkqkBMGPNMDex7Pw5CIWXXvYEpD5yYQ2UBvZQc3gO/ERIQvP/3tF3ZR0MaWN2Jovh7LnB23Skmb+zlWnQb3Pq4uB0L3eNQI2BHNRHuIjc6UL70dmUOj6YMpwV9L1K3XZSDFapyVWpOuJgdjxURpRtDUI7q//OvnUoN1EzRPDS3sJUSenB8duGrq1EAxV+T6yt9zqEEz5WOB+g/IVZ1OcG5DIQWumbZlU7B2Z7DQ90YaHOLPNFRDxxHDeIDKf8ppRL3AdIXVef81jA1A2qgcI1U9em3bCqy/g3FGSOuy1Y3pH79IXtVa4z2P452PaEZjfn6iwiQ+iOhWoEcANHRx0LEQTomTaO/ZwDOxXn9uFD5Qh5DHqsxyi4l84e/GuO0K6R2XWxEcDgfdWATgGHCqEfjCB9dQbdNuNFF7ld6Re17E2RDz2xKN12iNEpEG4GULKWuyIXtetXthWp4i+ae4/dUhvL/HdCspoDCkqxbPnPHQJKiisEyEcDzVE5a1alcTIoNFPazeUv3RGIViLsJ1Ohnkc9krWtQr4RVzboxNMXMP5/T4cYG2mhp8RedBluP+S/0HnD0GKZNRjjR2XnKYHr1qIC5kamee+Z2/o5qdcOaXcuOZVvy/Ou0xXmoELM1YTDMrhMuSBqSyLP7Pw6UKUSpVLbPXrwDwDcW3G7HDA/c1bplr3/DqG6ltQL9AahIwfhN3Kvy6WHPN4teK8PhY3b7tVbMj4fGyJaueG3E5H5c1IhOU7g4Ni+8o8/QGYkGqWc48CnLek3QvCwYxYwd9ZQ9a8tkKH9Ee3Dv+OYrJfGvKDH4U3Q0CiwycEgEwW4ePJpyQ5UCt7k9omlOXADM6d9ddEXgSrPUTj8+UcO5oDn4vvBB2K9DKAC/W30Gz9zsuQfhhonzIkX7NUGkc116EzFDCLc++xPYBi/XgCymbzpGxBYZH3puDbYC1FHwToDFdUPKxlr6YAIjn22roGicP+0BnXVqG3GNgtkrAc5PJT5/fmA372FQyn5GofjdGPRQLePboZ65qteXg1KR0yRT+0cY3H9gTn8pLLsN0sGezJfklfvdvCP2abJ5v47+jP0ppi/tbkYXc6b/wamuLLQzBj4GHtWgQpd+kzvQ5yF+vckMYR9E72kjQKhgME63d0pG2vGh4bIaH8+gM4sOBcOlfwG7tNFyEh3mv3ZZOMDbojst53x/l5zLdKBA9gIeBIb47116GoTHV9BfKJRhJtW9ZT27Z74Y5YU81202fL69jxfv5Ayk6Ylk/vcvPF376LfnfFrB2LQNY02KhO3FjqPllzZ/epPw66jO+klK+2p2pXtQsTjFH1WxiHeEgTSByQbkVkJqfNmXVwDB2G4W0lxbWjWeW7Vhd8rS1PvuszBf4h+4Rjd7oETnfgXAO37k24ArkKUQaR59xBb3h+/a7SRAgH/WNvIF770kYnELQPjxiuQSAHFnUuWmIQ49bfgpu84Do6czOVqzMMvRFHej4ewKQIbIAf7wyLnWaO44VD0X4uUexprFV+xexSra6VS06YjaHYfjGflA1sK+ZVOIAZICR/bUTT2OsdzC8MOeJn2CQs/AKFbEJIgOO7fnKnfXaSlma3MCND8BVwoT6W7OXXbLwbcq6GbqxzY7vhl2Zz+evSTGVnuoDtvv86EhIcldEzv2sPtdrBB2Ns3hVyC4ccP7F85ryh9F4OQjx6SK7dLl3kKwffs7ytPnjJQJ1xcqXbHDBeG7+Up5D9XDMeo6m/De+X7vvmwGv8gX3XGNERQwjERQInQhFo7CpCgAjIj4t5JTNUt6E+POfD1LRat8VWElXgZjZtpqL/eERBQ/z0v4+J28q2CJpBzCjksYxeyt3Roh77jh+mWEkUJz14MDvyayVTRmWuKHvuSUu+bpjRBVC8+DOwSCUvlFjS1tRiQzERTXOjbfdXDBS7r73AcYk2vEPO3sr9ng7g/6uWqhikpv/CHT3I6OLU8KjZy0/p3ckyeOn631kYzVTeFWgg/n8x4rN5/jptju2y2UogQCSE4VCTfs8HEMS1xYHjbIPPXIXhsY85OFIuNBmRzqmalWM6Q3YklcdsEZkj/cLarYZamG/PF7KeoIWc/OngWzpt4nfFaDui5egoxHSGh/YmN6D+7L1YYWmuWUR7sqRaFfM+OR2ztYP5FUpUKW7ymk7Q3CYZNIL2BSZC/MUs9dKd1ESbq9+i/K43iAyqfCNtIOlT/m0CIcWQI87E/ndYEObyYFVhaFZvZFw0Q0olrZhx87CR7dGTF4IEYWybqwN2Fu713qqwBfcT+lj0EkJzOyZaOv4Sh62SAoKhvWx4/GSiCNAc414h3d4v8BL78gb3F2PVM/kEied1tZCCjc60HQHB+r15pCDabbmCG1++7Mjn7wU/6i2l93rYi/N/xkgulxIVUJRU3xqH7kRybF2Z163Gi0yEgvdw9q1rkK08J3Aq5DILYxFn6r/UbHOVnqsbtdhgt16Cczo0h2kHYyWUm5GmCSaeKSoNBEDhGM1LeH0NMMgXp95djs2Q/TBiXGARSl2Mvu2ab31lohGfcEmXepfTGuaNRS2SkbMpmoKM2tcZHU79E09hy9jCrHz7s+GIoR/mkMyGric2iXxU1Tn4F1twKa03PMajGjlGk/iGEG2HjHyTwvL77m8bb2UysS4t9ZzBYP2kos762T6tf362uCPmuDkKHoou1AarZjkJFA6NovJU7C++x8UVi3V/E8nQN2ycz4IjgVU+/tHuPjQoF/i6IwaF768Yw4iBIXYv9RO4aV2cLb8Et9Cc39ZaXR5aBHk9G7EKOd6r/4dC5JvbPiMEeK9dKPofarMr9cx3uJLV0bq33qDtDYD2H0xR25vjhVap+1kkfp/Va4HjpiiBPxUnjfL3aS/mLvDe42QNQiPRHAuQSWdCqUa48cqGorTE9P8d+fVwjuoCktsczTYxzxD008ZIsFdtcfzX33N6gH2HdxNjOm4N/raRujUa8pfscQ9Ef6yBSzkzboxhEZ3RPCTVStQZ/FgYcL7Cx2oTkp/mCP4Arnz+kZZhoQsSeqkVL863zvADDyJoRS/UxaMjkkfMmRn9rX3WlY/DgnnRYr3WPGLtxv/KNopZBSqNkpEMpFn0qsSoDZ4pogIqsJXAiBZZ9FB2niv1vSHIRPVFi7eXBoGpxkptaiB9goYCqttVf8AOR6AjSrkai4ymRoGelOlZWCQ8UgsVt8XD6sSdc6VVGMUCxZ9DJzxUwSgXjB5+RNQwYOzKQo9x7pUwdom8v162iZnPW1t6tjd2sKz9HY7jo25KJOC1iW3mt83S3cU1dnBpOfy1kTbyswFvuBdo11Y8Qj3G/Z8jN5tZvEZbWgtgST9mZDw/IGztj4b1iRrhj8SoV+30O9CQsazEX+b4B69kfGvWkCSKPT0tJW1jGQiXuqfHKlGDvwPx1Fhx/KPO+WiCHxVi9mCxXKpUKKVpT9mOJX0GQLmpRM7K/XCmSLYRKHBHShKpMv4rqw7UZQ6qQH2P92ZCXgEOvj/D3iZXeHqbi+TUQaYxr+zvrskUdmCMeiqEki0Y4VfbivBXpXg4pPCFuTyxXJyUC8TvuNLeUA1NxocwixRrDM7ZieA1tRym2dkTJVfUf0HBbqLDjJnOJK0N9LTsUauoCOola+pnUtDbn3id+zXb/zoykRB0AXW7JCUkgBKlEmIlx/FaY09jPoM4rc7fVJ361I/sVnoTQdAxKWiV/U4koQJvCfXXzNBu4qRsMAd31MQI9PC04qJn8cEh1zmzsgJJKmZkTOV3DO6MR+IfyvuzR83QNtEtcajkw4ilUv/VrkGU57g3+/HvzOurqoEgtRSFo7mnptvVZJ//9ahLmx5aaSjAWNhaKcadLuSgZ/Lcqvxnw9S+yJTC70v1z6KxrZbipq1iTqfKQABh+VYkUbZDSfUReXImGC57PYStqJ6tnUpqW/+TUJHymrV296WRN3Z+6KS/oAPp57j7Z3iYpch14961dMVfNufuCp+EinLtxpQz+8PP5Lsv/F5Blt4e9NhmEBJD03DuTC0riFkxHgXLR4l2qkXOsC64xnCauJ+bPqS0uo9RPeSLek+/fjmN6rWCiBG0TD73qwS4gjSnXEMIIml9UTR7/45QPu77ta1aBA6/7QWRN3FklFOcX5lLeGKq/FwYwgjsTPD9dLG+WxwqD5Al4Bw8JpRtbVJh+PScAO8QF5M3hufPF1b8QIeJq4ezoqC+E0nlnUAISgg2m3AQ96z09jpu2PsLRdeMOufAALAyVoDZWdq2M050ValH0p/rjI0Cb5LbElh4RALlhDCqCV6lW+W8yulG/6ZjuRUxb05RJHfxW3AHZLgrw9Mfy/ruhEA9TjR0b96U+UlwVax44JTB5xG46FU13pS5yQQANNhwXA4geuJw9GJ+muY3TNBRbNbVthXb2T+6f2q/zT9lsFvmQRjM4a+IjdJ0mT615W+YlLdOv4/v8tYSUnkYQLNfKDM7S9UizPleD50B0PWQL4rPq5m31/9GlIfGlcXASjh3P+6TfwyvkqlsUdT2fR4EnLFR8Q6iMgOPTg6NcCZnBtg01ypscmJOsglFTIzoE+L6BLLEIP4AYukt91hEHA3M1MKU6U0l7vW/S64LH8/5SvlUavsnAlTus51PSWqIjBjok0VDzXZa17v23E0aZgkDA/hqlZ9PYH/pky89JZknROEpfUJ0IXrfw1qBwCyHluX2JZctASojcihtE3jpALyHc+Nq0B29oMOXl5pKh3loxg5nCDlumzHLO2LK0yU0ik0ay3oRDPnbMPyxH4NMvlnxZoropVunzgKFJDbeUWbhnZcfAqtzKNyAODr2N3imxeJfbLfcbXO5I6nAcbZoeFeESPqeBhG+KGTYl05XZV0ItxbfMobno1xiZ5FleTLInrzoxcFde9zGWPIenv/7CAUhw2NkO7zXNqEPXvsaxlzHvU3SRLsP1Ov/z78uEpb7dZ4J1MUjx/MjF0VUjHgnTMrYT/sgClubwHc/tPg37S2oXolpJcDBRwOa1I/KWjkSy53D2mRRGsCOqxVbzymlgqmBbpDTCfqUf54mgAX5PKgmBqawmukYTDIgNkmsX1qV3zQbXPZeOFzVYPM1EKEMrYl71XHjCERYqQHX0yQJk02yqe+EX1EHokz8UmNy0fGQRMGY+2OmsB2hdH2bvGnak+d8s2TQSUY7eSCr2oa3QQTM+zTYFnAiXtnlOxifp+Ckb5Wm0ZaHOaP1/xzhB9g1xXV8P/4fOcSSgt7/c9LUlYIDrYU1tRklwLAP7cBvf0SRJh7tEFuuJGnZDVd5KB1lHxzHxBG5d6cW73y2odtx8iiEy4V7LLBR0j9JROe5OmHaUzrnilw19IJCtkCXSoROSAxDk50vF1gD/C1TUXgCUJrA0oRPWcAOGPZHub9UsnRpHx9PTu3PrG62qsmNOZLeCevfvbmBudb7J8yZLuW4atvu1roiXgfNZqNpy9cbYwgqGnDZhTV53FUIogXGkIAHNpB9j9aArGfosYtfo9+cXxEioVPAIoi+QYEgz3oCFIADFReLCCHmqzEujwhriPYYLATL5l/PaF/qVoQmNPgwD8Qb34cntxg1Yef427PhWNgHME2YRYRL2kTzuUhmro6SfsCipcevcMR27JKR3Aox9kH5MhRgrAogbQdsfq9QtPltLRp7TQHI/n5S/1y8MvhB4BqWbHJQ3BPUQb8I/nxbeK3mJ/TOHQYP7O8WSH0y33XyOxTFx81NMC/uSuHEBpfZcsKfxdzXrl9ftpDwnGwnPxcJMBCot1zuGu/R/Rrgui/CMSt4lB6mzhOCnPLJDbXj0C7SANnhu4L2JVacM9KgKZ7Vvl3o7nYFO3lnT9JqNh6lh/4GJ4eHTqUEk5p8Xav3cRzlpiMFN0LByupdlDzLH51Sgp4IiGD/vJXVDvyJeCsLOBjExuXddoUs7+XLY2CoSun2i+mITo0idL7pueLSJRydXNVsMIMpSuX87UqODbAysX9fivsWRvEl/ttDm8MssPe1WoP4hnM6PuEGqNLvy+XivMFXzhnWL5mtpRtgGzhUtdI92kkTL2gs0jxszm3UpE+2kk+QIaheEH/j4Ky+pJ4JFEDU7U1rQ7B8apcVP7KL6CfaBqJFsbai2p87UmLTnZRYZbpoUB0ZcVkKCuaZPECNO+JrwQ7ScwMNq/cQ1nrKsdxhIwAdgd4mRiOtncMh0hU5192yG1U752H0AxTtfe5d8BAEErvr9QtOU5i7IauXpaFh4bLgLxwqmLxD9MPA+4QnETPYmezREi2EFb0v8yxdbweSYLCxpeHiNQ34UIFDZrpkWyv73e/4c97l9nQJKj9DW6x6xXhBWRDQQ26RSUSOprxKxJMc9jkdifMoiX3yAfIlhVXqhT7R3IwZFqGT8e3Nhtkob/0H3bemUpYUzV7Ndr7P53Fyu+jjLQ7bjrPpv1oSo2+OvaHr3/78smCIwym1ODPPcwplATJsQQQ1cY/40z+YmkM1AvxbTrOSvqeMFP/2p8DwrGQo/KP88HCFI/eBU+XOa4ZPhzyILqqhJfOeKuL3v5TW5ReXsckPjpBLS6LSohEV0ITgX5xGQsZxZqU7TJLeHDXAo+zgNVrEzzzcWBEPc1kXM0vQYB1nwKFyhc1glVaeVpVsnry17g6s38tXOVRHDUO63clDbEePDhcDWAyyyfeM7RZsFl15rjX8wtqDKUG8yb/B+BCIN7HH84A5Uwlm/fQ2hMM+YguERIeQeVbRQqJHJVysVqU6cF961PkrtlVpvE0M3USFUb9b7A6erA6Xt3fnmvRu16v0M7smT1Ks98QUq9APcfHEt5fKyupqVL3P9pjBljuGwt312Bs25WVh9VdzgThw6YAPoYkS/+gauUU//zeFIDEIH7eTruUHm/p3wbssWoyjVyo9VIw11H+xWinCMMwyxDk+wXpvTuYx96HnWvKFwXhj3hvwnPb1OaFDvjlGUU5YsCoERtHqoGHoXcxqJqFg7hE0FOpkuGu3tHKBHx9pflvL9/2psj3a3Jn3U3qP0jvcjQB3ExwVaF1qcLojaFm67e2TA4cFodVIwaiKOJqrnJJ1/7P2PFAiKfmQpBPQoWKGh/RnZ79L0ZCr6wNnzAbkryQhASXSFj0cq2Oc4uoxQETOn3gGVXtzVaqNZD69JQXQW1zhpj7Ah/H7tZo4H7kqrmsE71X/EGbVwgnYz9LznmL7FoSkeuc5gJSRWnw2n73ONy9ru/XVYNGYEERbQX6H2lTNZILTDEMDwcyKRi2PYf46qTONlyYaOPS5BpdHZveSB7klzpatq/8gz2Enl84TUZNUskaPp1EhV0dDf6oVYgODaYnuCiVfNtGZ00XvYyQbBO9HONS+87HfhBbhNkYX+XVGydcssA1lDoFoJWSKB6QZ+dOg1Tn1Ws1dokInYKt3Gb6CAUWPsV9ShBh0swTSxiSXt8/hKuI34Cy+3laznpghF2BiJc4xwNCKthBeElsp1XMFlYKPLgdmYu23FYdfTTVKCLqh98gC+npbyqi9hJBCkMYUbIjMtNDo/uoLO6V1/3+cm8g+ULVGAYpty0OjeP7uNQuBbcx0TQr3O8yhiF7QFzMqBI9QmRNmeXXvJjaxnz6JWGP+llRCMl/1tyBCvp7aNyGkOy9XhxAsmikvoMfPNbuK/HUEcDAHUwPQW5xzA68ZgtSumoyQBDdgw2/eWHj6OyXEbfOdi4dL1VtG57HgMg2fbj1l5hbzgFBDLblmCgTKkj6KknZmtEmGDWQsG4LxU64/4XThrAprUaAukxLpHhT/otCudA+t23/GPIfvuRAipsbTGzGLdmJytHIXxk2Wv/RIEyQhlLx8nRSBWqx+TLvpQzGyRtFX+FF9AHdtHYHk3Jz8zT2+3a4yF1IQdjj93ZtAYyRJCe2KRJK8NXqF4OLwNtxCq+8eMTFxZIGemBDzec1D5iBVEBpZtOk0MHka+xfsrdg+x58c4HSmhTHmNhUlY5lL8jV3SzfAGEJZuEPG/rZMPdZnRxWHtWT5nhigYfeKUD8IKGaHZkoUVeoAIHQkxDgIPa0G4IU037oVu2FPN6l+aloR/zmt56vnuyyVsk2fr2W9leSgri/JC7kOfmlDSFDud/36pgB/RukcrgbgbU+qR0ehx2rOCQVCnsuXoTrQ5/EWRjCbXbsqHDkEnzJJzkNpX4zp2ZokLxSQ7kpuaxFaM57b8RTHQZV/hBl5YupLWqTVLFH689Mk1JBhKJ6tpuiXxrtfMVI2Du8lUyS7cP0qHAjmvmiKPORxhw4b3AS/SeSSiWl8ZGH8th6gMV2XjtgwDAysQpMVYSVp+6L6aPZj3YRIm/uATeW+TXClKLh7WcpxXg+zfiMeN3vijScf0g+7S/d5T5URO9RJj9F/HAHWwDpozt6VRUPC59MoT5OVccD+oz4J+AeQ77361U7izs9GMN3PD9TeSDSIc5WHqzlEG4y0uMt1aCBWmzYlO1w2RIFyTIuqOw+KaLr+nvOTc5slXaOtcRkNvdoFXXUb2mW4GFlzhPPUXgbzf3+98Yn+zIKduOw3V6SzXRXrtCSJYkC6Uk3593WMtWKBpfrbkNadU3OAbFuqq8LDFYajZ3/oZTlmvC1RdMMYZGi7g5S11d5k0gA7mA8RdmBPvc+5IIjKKHV//VOtVPqnomrPRmyykUn7yxjGL4/wdyCEUgtjpycvpuWRYiAQoxHAOjtNfQAD+/yzYLAnNKjCnBSXq2LVyvOteMGZsP96IMHshiDQO22ByAHz4EiMQfqJHktb1koTVTMhTWR1aFmcJ+SJ9gHYkRF96mE4OjnYu4C93oqWntpwELCPt9MXKfAd10wLhocAy0sQALFUVIy3Wlx2YcHrXtDrWBADcBue+zkD9S4dFlKnThaKXdXc7QtxdA8+ZHuK7USF2K+Rk4Ll1Jl1+5gADawrPqyChV66nensswgV/7bvx1r9eWeUCAbGv0zV8IqWwxq22U4hr9UFKiuEeEF6X5PGU7p0G7H19vbhMk2oKUaDkZCh/7TeyBETFu91LPytor7hREf/2OU8XPds7xKHJfLcVsEoATvl0xBxNL3ihvtosBtaDQlJfqZR1hAyI8p3N8jGz35czxEbDkIkocqc/YeKPgpTEiH+rVtCqHt8oH9k5OdubrNFgbd5m5hWx/n2sPrpj5Z9FPbZQEG6ut7f3Wsl9S79jCzfs/nUiBGZwOigNLc3SWuNNrSiUBKieDlxdU6MbcrM/VPfN4evOcT/EVHtelNcmdEXJpFqzKKhXiosFhEuMGUkmo15pLEfbJeIdF35PFOOeTgXzh48v6LvUyoCzuym+u02H0lOx0dQfUKG3eTFCnBrCNVniqJwUUGIY6ELwCwQknKcVfNUXQMAXBCc03YqrYIGdD59A7fgN3U6ovnx2J9sFUJO1KkvqX/0LzLLVNE8FxTmt1rXXJJ6HCFbiGSum8TkFZFmKU3fU5FLBgrU4GYSEHY6cU6wBtI812WCG6mqvpmzfbTRHCWHzKtGawYEO16CzfzMGWOqGTyFJlbhVryhkmT0PxcjCQnrmeuErjaUjqI01gI3oBUiuqLAQD0XpCdtczRL49zwXyshxk0Hh46pxA/FYm58UCuRMgz12jaicpJlX+QpeMy/pRr/r/owT7V6LR0jB4zibHtEHOxdHHgBCtcSFKjVhr9TcVDwjlk/kas1CZKZX2ymDUlMsyuSF+K4khZr8K7MdwKthkheHdDrowQbOMfFWc05YSoNhj5DAk2OlHo8OUs55QGvy4E4iCBxlG1ngqOgRDGdWpg7C4bHHe2/FyCYuI4VVa49zjoZKYfBLTjd9dKVpyLgu0v2sz4pVoutxd1CyLfbNDOg7/uBRuHDPsCtUOCgpHa8geuY8+h0MVQ/8QLqFRo7CDcnfoI8RK4Dp60/tHe7hAZNjMW+44jMTEOOxBNUydFZEuJuXhn8k0CMfC07DmKkwNG3+882Ib434HQdE7shdMrnXUZCQ89FrpT2x3i/p1/WIwGlvUVIirRV26XxJ6cZPggwkGBD2sLiF+CcubR0kgr2GwB8Rwxx6oc+txYhutRLyYtViLDQNAWdjRQfl6o/Wa2uWOAseaRypuQqSbkp/qcoKW+7mEc8HJvXrMnx8OyHAqfuXCTwzPhQmdMd7DqMrzMOA3igA+UZWmbt9J9V1yZNQGQ34C/YYZRQg0hKsq+e+T8QGysY7kvXRyxmh8mLpwi+Qes+UmSUksfyTvqlB1mWl49QeMbtoBzBRiZnerl4g7mLChUb+Ri6uTk6QqOGOopDh6iZfMdymkXYPtoAf8PGE3Ad0LPSTJIbvAHJy68/0YcW8NiI5D4SZckLENQQPAOzlTPlh6whaQrWmIa1Cr523tqbxVltTwxUmAI+Bi9nYXlqyH/rtpAfqRAOWuBobpF6Y4F8Q7iznmhuMop0k+hLv9FN4S0wPRlrsBXMNYi+WJjV+QfL/SfgwAn9AdVPldTIQpOcq+Us8od2pz+GVC7IA2fwouxXoMe5GPfflq5vXfXW3+U/LBJ0KWcPsPEzfEHH/RRLGBQR0JrehticGTxxQ9whpYYoub3U6Sp7PDBMfUvmfC7Tz5cqAprk9ZjqOYmkJYyuLdt0U2Gg1VfBNpM1tUOvgKfeYZXsnX0lfn/AY3KM0SzjMfd+BW/yz3ZtjWWv8X+a2IAnGl8B2I2ixe8qPEjxHvWZhReKzsCi9SYsHTO17MhMQ7kuv1sjsONCWtgL2X6TsbGJh+pbKSJ/FuVvSf+CqDnFD7yU816tc1L6ijYdL9O2DKQp59/OIfqf8dUTE3BP9LkIFOsqB0AoQY04xNEJJpY8hiTQeU7cYFwQTOVliqz1fW/tIKTaxnLsz2eKi07DZtSSmRXz5Qf2yXeumuqCfADBlq94NlznyHCKW4yt7MeqZNTzWltqfm6d/cjshO3O4TnUeMfgQiSWU1RDNu34s0S7L6xG5PhP+sKPDnWKmVvnNTMqXBtXvhRr2MG+tBlw/ZJ5KiAON2Z6q1w1QUGdzIr96qWcTCvni2pFllhCiA6w2te/fmBrbeDmGetDhlOjDGGpC4r6ihJTqwpwARt92OSv7PkwHspHfEx6Pl4+YAdLsg/7/IP4QDq/oG8OQKJ/4ASSilSkbbn6iezk4UtFtIsJtUUXhNHZ+nQBwN4/EjAamvvPVqssN7yLU75YNwmULl4DkGjVh3zANnSXbArPkToQCx7PpxtSv2mxXhLj8+RClzHGcBEAIL4Ms/R5s54DNcxIGAPKFeC2z7Q2cL9ptFxv4gmPlIIXL3b0wYZdpCraCr1BZeAgUZ1Qyp/Zd9z9JdjmGU8kNU7lRAj5oDz2wWSeuimvE9d1KOFR68pm0Pfyrk7y/nDiRwybTagBKD3T5Nan0kTex2eb6OazRQdD+7TSuugs7C9Ms7FL3C6Kq08PoW52ZhiUcJqE+97C49GIK0C8uQLrHDKIW9HDKfsKPWxNPBnOS67D0LwtUtCDWTxYrhFHiZtfkUOrjQ5cR7lvVTZ1emxA8pSuWpODnBcyRAYq7LRioVhAXATrdFP20bRsjWb5M3naTzgWdC0kOFhoIAcnT0blMsfAa1ObMNEjJsMiauM1GzVeuwetoCYGAo66avkyvz5Y9PbL7fnXsrT5W6sX/3H8UfDAZJ3Vq0gy190dhyggaBh5NNInYRPEGipEJANjyZS/Cg/qCOxnF8rsQJ5iJGzXhBXhzlkKXlqlXIabwXksedffw2m7BQy073yhNAlwi+r2G1j1xrHavxvNdu2LHsnANxkqoWoPxSDTc3m6cauRa1KUx98XRliWtYbSQTg1M8qj/UmmHGzXmErimzsTcdY9rvsMUnZ3Qc5HD/dhOKqEhexJpZcy8zj1Zr0XM4HZzFgRZQ2JxA25CbnxNsE5OkkIrIww0sk+yPPW4/4u0esggnAAcHUSobgiSHsD0yh2Bf3g1rY7uJ/Omyk/WQH6YNYDMtTKxQ7CZmVljIxnAXLfBY4eEnRbNTRptFvOuFo2pBxL97r6RpYMaJW6lwZup4O8mkd3cUS2d2vXFzsKIy5ohaifcTY5+x0oULTC+AeZJVd1zijcSUwTVyD4CqMPoOzTabOqkEpeD0b8ICcdbbB84HsUZkM0swbw8RDzekwRbm10AjY4YZUWTaVyixcFW0qGTcvgN96+G1ORu8HH62BENcvoTwYCeGJb8MX7Z44GR4IpJzGRP2r1FCHl+eYGJBcJ0FkGY4AkqPMdmOJPvNW7aHSqw3TWWJYums9T++APSFLlY354ImNDMNsIY7C6hnzAWQekDOHmVHmlvyBWeE7B6eR5ZQotdu+X20/awEHYv9tHbU4ABYbIU5hbP2xh9+bLHgGWZtn5kDL8PnjdOAfX1o1sGZskAfLzI1YEfF8ipm9zQQwrx5obVhl9rD5LXLf0r2pdc1fQ/L6fXH5S5rXCPuYMqr7ZuLB/E8qPQbgbhLOAJskx9gxatyxP9coJRi0n4QZ0oEM2HDrPiol3nt79pDKsZ+HlGM95csBrWNyFcIYoKLdpERTHqqBTgex19J6xR5HiAqtYTv9HBMOw8XWPLlF7H8rdoFO8PkB/rXnMV4/+qNdbfzXVGYPvoGW+zNUSRYOoM851pgo1OdiSk+DLOgOlOdr4FakGo2cUKx0JNSOsAdbxkVpqRk619Tt1isThgmNXmPxdPe96uFxpyI0VzfA/x1MEvRf1WdoA0DTi5K3qySICBH5VqJFKOzB33YALRpdzChw9pVqePJgQktEcktrR5dHMZAq0W0iw+4N9Or2Sy86haV8AyU/NtR3GPKy6eDwgG+jwuDlb95D1hRbMIyuvUuqb9BOYDhQ3rqNDGgoVBMIjRQArALoSmNB4wsf/A9bPxDozh6mfmDfMkZnYZSowv8OKdiHpYBV7YvdgOWJenL4UGvCP7HejfMh+5+XKX8/8rkodgNPz5Kno2tBuJhNSHIpnjuyCsmssjbZw7k3MA1MUdqDM/xchYoqVLYJVKNCyo+p1SzV9L22nEqi897ZAJ1MgwNPJ/i87kyfHHNYatfWho9Ovgwf1afvzHfLnDWTZ4dQUaZN7/l56HHfepNV194yrwhzyTKlQcwnPDbJ2kWVF+O7Uhw8bxmGY5QUSkQul0ICd2FGOQiSafLvxnfmoX3FKh+IfzFvwm7g1O5f9W401sJIKHV8ux+gnV7OK3f+iC2U7l/cooNj5Zo2x/9fK53eFDzEEkGHmJwev2/FPTjST/X2YLu5pDkxQQn/k5UziEB1XV76EZOY/KbYtLgS4EmSIX3bA63bSXrm3x1yJfymd97XcwPyy+3tQFPThsoGmv4oundzlhcsX+GJfO7zK3+GNHnVxPk+EvETU1NhU2HedYwtif7gebfYdwrQyfbnh29OppWdrPlMCxjNMW3xy4jnkRGXWvrKwgzUIF5SEV3yPzc3gYTodGSj7a/xAE5wmeQHJ055wT8Pc25kYt6Ttv/n96pJ4G+WSaAXQyXvdJW3sBGc9boJgzSntk99xVFh517kkTaHGYSttwt1MgJwQ4h3vHOIfOF7PpD41773dXfLww09McFM2xqMSoHlr6e+Ghuiba5XoGlA2r6hZIk52hzQOVzLDE0pa7fgJLbDfGk6VIt50lQcg8tvEwVPNab/8u2pC+2tyEj3GKkon0E9k57nDBE4QDNmzxMpoaXFNATJBw67KiGZHdWgyvYd89qGkGGY7aFY6iLAAsFOYgoQsw/gtYM5zdrtjSAg+S4c008tcWKmiRHm6dKoZXE0q7md4iTFyZfo+CqNEn7tajm16BOSej1rKgz6QK4GMwEMoVHyAc5hpavYortuudF24MvtMnyoXt6egWrVc3IYqLkw108hXGy81+4Jzs7rWf3x6DDHbQNcWYySS7hTcUV+3oe9TlMQcEhKyNM6YZ05ibMQpSwvrtGJZQAn/LUAXqvAweaNlYUt/NILZTT2tJnSIJylGMqkbGeFqNNWF/qH8XDteIXeOO/lICFW95nqGelOWjGuPkLzB0KCEd2i0ZA2FiBcyoNyBTVCadV+INiCoNS66Zx1P7hUmhWJAgKRpOuNdhm7gRvBWC/lPs4CInydDWU2+dyPCcsg0RK2V9AYclKywmkoWwESeCofUCDK1+FQg1rLgybBjZzFVCGHiGgNql2ofoirYZaNAUVaTtm/EyEzMUwAdl8Ur4P69UGbvYfeCV6YnNItv7Xz6PHtHljXAUMJxuEOpkz6+7RQUZy1Niqp6Yc9PDVm8wyw+xDUvYpPQVoO1B960sJM/1equEdLslpceil6WuT+8jXtqMR+RaWj8arsU0AIxgXy7Q0zdWTGXYtuzDwbLAgWBQxSSQslnFBW1IMGjRE8TL5aygLG1KHn6aSvKsaH9OnPvIg91RmyLZxJZ+dmk8/ZkUKtzahrrIWnjnlnTSA2Q3hUapHDS5yM5jRfs3AWYqz2CueMnXWBqFhgbfO5pRZR92+bXIXNjk0Tyhb6ZbF+CZKiLCQvcNbTpqVpX9YaoGRu1ljR65H+MFDMPR63WWfIUYHFAKgn5AuSO4d2C0GdkiB3HysLblVLdaJ7JOkoThT6jIYCbcL2qk7S+Lz24JBB5JmIrgGG4OWW6iUOvooRaLzHsdqO5Lcxf3Pda4nMd+ND4E7Kt+OInqMeV1XGTolBGBbOGgE7+sN5avVKYhGbDGi6GkwVNLATl0w/G6T8Wee2uJwwxr+bb5M+1i/hkkNg6n1N+6iwK3SyTF5VkvLMYNtroGdMEiV0CLioaZ+UpdptzAINV+fUilwEQaggo112lOhR1nY8XAiJb3mQdWM323Kd/6ko6oPSON3AGmUEDJvPi39WGFbITW09wEyTr4CXXWEVm3iFYTW6Gy8rqknS6J4A6zeTvltt32LamLBUbh4hW1dDKVWU8Idnd1lDo+EaLxZ4xb6+U/4h1bKBjl5+BnaM5+eZ+SG7KevLH3GQeKyVbZhyh2WoY+9MFpxs1RSMwScd8iLN+2NqvsPuGg8tk23j1XH+nPqZ01X/e7ckJbMi49XklYM+1D6iPi55uplZc7tNN32+dbqe2jZvIFvfSRUTxIgN9wSytSchOM7Wc+THv5hVKEgFCmRIfozhZxuavcW5mlgxjwZrR702yiDU9sf17eS3268DKGZvOOGS4hO7VMu3r6/e8fM3TNfouVfNbiQE7W0o/KpCqR0Zvl0KJj2QgNDdf8uTrgyTKk2SupOqwSGk56yl7HbFTpb5NgiXFvd+c7y0E8jaSymfDfN2DpdyQr8GEJr30wz6eT3frEVyRvIzAjsSrR5/Fnxo2zvEvjrlYZhdbQLqgR/usC8s/WyQjvxqAPZqooIKrz2UxD+CnFe8fup3zqgKJgwPUiYw4V/0pNvLaHf1vLfrFq8XQVsBqfKG9YgEZsnMODBSSKG9xbvxVKMBByU/FDBSYCd1ZHmWNUmXGt1Q7Eki7D2TipRxKnmLxLlB0r087ug/B7loQDpso30qq9YSpBDgIGQI1m5q+Pr6welXzSo7yIcouXutHmIOzfB8PUmzXtOJa6ttFD2IhsUmQ7nfwSFFpyAxY2XEBB3sSPUb/hKhn9lf8Jbe9onfUty7xB8dMluiHUCvkToKopW07/6YcEzrvGiaD5lv5vZeyEo9d+tLK4gSfefcx/tfDEvRIo67CuCTOojEYVWQaLb8a5G/6IeuBpx0BWMCDUHy+mW6IIsuf9PbbEHImgkKWflrZB1r5c9N35l11uEmIhYvmKn20MnY0tFySu9F+5X+M0tRZav9ONiq4DfEyL6SocpkxL0n681ptIZEPRb74sQBwhsYcL5O3bpkrXZCSBZX6NBgI3cS4Vbim3yyfnac3DjU3nuiyTSLXUaVFOqIAYp338wnORDetwa78MTiDogEWpw4XNpWeQkbrIyQ/NnywqUfEOJWQzNmXkyQs+i1jSZoUFxfvQwNTdFrYBL3iUbhN6dF+MoE4ttNuBKBYeb544Y4IDkG6k33hMJyKLeaDQQ13erh3gfdjmjQL7cjvHZPA1Q0dXHXzVfnxO/wuqljpLJ6IDwrKjb/ITzK10jXebvH+sqEEO2ND/QTcN8+5IBMcnblTSq7ZEP8GBSMlp+191y2t5GB8WmTzpgIfrg28IXoFjYNJuPbNE3ITMOen6Mwr7KaByoelp4tpaILbgBE+UQG1ur5CqaWg2yB0wVB9sw00iaztXtHtbcJV1k9dOOW9l2wQg+D3quOM4SJtT5o/M1Ycy3XvE6Q7fauGxpKDWUfDbPtdYx1VDGszubFM2iU8ucWRRuu/SWNdYIvuDArkTXIvk1QTr08owwgO2HPSIOkI4MVNHYlLzRKkytKMlq68wud/JRYNSwxO9gHsBPaU8f9BYu1V3jHKMRItHESkqzndLJzI8nhR6PMxTSoyVIDiqZNezjD2vzZM1765lP+euXRvs5KZ2rW1Hyb6ezc2zFL3hbp04eQCl3TYqSHZLn/x3g/phLMUlXCq8vgNUgm9PEMyTUaLt1mGZ6UtZmCPj/le8pl8zFVGd81Xn5IcaKBYHWoGYGN2tSxMFW6+5aYZNOPJRi5uSBz+K/BfIpzYiZbPDKvZHIrNmLuGkNrZ3skME4COxsvuYXV5myXLxtX9w8DQsO4UoMEDX3Cg8m2ABNRXIYb3ciJzYRlsK7CaEHsYiVTU04MtV3xIRowz2X7EIlAI8m89VQWO3vTmScpRwpqHB9ab64eDcFVQDmN2ZJOHvi9dwy/oSgPSt4TA6CS36WJ6qQawRXtUWwcdsYzlSStoIk2Xr3EtHszafRgVTb3emW4eWhTZc9UF4qVkizfRflyIQg15SKwN6rMTx6Gy9Ad2BuEb6S7dWsaINtnv7nIAMsgV+5Y9EtTLxcG+hHAjhD18LkbcLqRh8ZKCzZWZ1BgPhhl2ilIToMN2ZOa5Mt6ENWvCLUARkJ2IfV1vZYoit6dJfY+Dzxd9K502drS+UaoNM7g3yHCJjrWR2+koQHv/UiiArXw1hkEAQ+chsVPXGeJufHKqeywPUp3ulthvXmbxjILgFNpTgU4VDgnEC0Q1eZcdeKWWv5s+NQ2L7O1cx9UQ0AakZgyRF+k61Zna1sF90t/x49Ft4x/ZylYccW1b4raWVUm4tCBOnrDt6p9iKfRhT/ItKgMopDSCG0ul38FAtMJd5tet/BSDF8Bo5GVqER4DtkfxEfoxKGmSj3MWuJCI3WJiWQLntepoITGHCpBON/1NtzhRNF6nxyidaVuy1pg5Uj1XD2ExT8o6MXewIyfXISo+CPRB8ghuYYyiSob2/AP0VEWgtZG62/39DGnCBkMz8PTcggdl0hFJKNgf5w10S+jsJxKFI31p1Y0d70ETxIz4XIHsDEeRoUG9HmYSwk7u370CGTv/6/D9axCcuuyUTLd7kF6ebqftLAYTK6ckbo0XByz3meWp8r2YYOnX47SBEe/tEPPZ8x6spYJ9/+pbGa0ioACXJpRhdtMdVz1NEOAnSYBdyxA5q+te5YMOAqnhMXdWZVVHea+h1d1FVO+RIjG2kyR9fcy4jyY+gJeBgWA15l4khXRLngoG9g3Ihkm6TXiNGxkk4077xC5dShhdvdLEAY5lsjvR5BXh68ncNkH33NhrsKDTzLlADJ6Xd83hAqvyCmdpKYikeuxFzkCyBSWr5eBLNkWb6vdfUzyCTd7k58cvgKRkeGVHysDJfJHire9t7j5/sfn8Maz+aHqDf6PFg/L4HZzAyRybXLbG4ihwC0ehZR1RNiIn/5t9P1KVDHnOBIWBuUhY8JzQ3bbAwR8AC9D/1V9LPdBDp6St1biU/fH+gISsnOUtXRTKBSOE/AiAgCTB5A+FYX/sg+DUXHtq43qw59q9XKmu9o/oefk8r1NEFvBVbAQH9PKSHNAH8TLvfvwlSyrDQlLLGsl2HMOFO+oxK+zIiqkQDpQRZh/zbXZ4LmHO6lX9zkUjAxOAr1cGhS9QLqJ+kAzJ0Jo4ScMGhVIXbitJOQswrCYljqgjdtbyvK4RjDC+dQPXomreTGu8OgtDowqzPp19CFUYi+u2OFwmzNbaRIaGWqPwEpMCBFfYoyVMF76XQPSfSxclMh8m8m3saLHcPqklYDLvxKTkD5RtnIQ+THF2vKYyD57JNLL1lGhl2eMIXRJvAc9v4u0xy2cv9JP0BoybrZf+d3Hv3ofJg4iTZgX90rjadIzMVI2IcBBvz8xHcidrIwNtp7XhedowElN/Ge/RAvXN3ThsyhQGi5jSZqszk6/NDlTjXQgZgGF+5vS7b3gUuPnWD32QBjB7rwSAi5+xLTLGyztyp6mvXU1KxUKBc+h4OgCwASZyY0ka4jBDIRe/62qMqO3HPMg1wtz42YqlukfEJFwetGVTfDMuZvQZqLaNcyVkIYL7WK2gOnAesn2l/LYvN7604aeK+GjZbjzx7oeie88CjC2ti8OPMXGJ/YiM/+88tktys0l6FdvG4eoMCjMKkOKaXv4IQKFfF0oJnIs0c42OEG6JAFiS+6r3eO6vSs4tF4KUhklSy5/8hXXMoHCcW17npXy6kxFN9sdNWfWZgVGOPXyw64pur5Fj2edBIAN8FWiq0utZSNg6KU79Xd4EhJHw0mE0HofVhSHmT2wIkvw1SGBiszA7MfryXTxmQiYkCd1tK/9PB/8OhsOodpX3VFBJH7/nFHX0xcEHANrecbdYpXLmGQJF+GmIUyqpxSvYWJtf6WjIHCnXQomleq7D7wz5x9FdEn33ZGb3njHLZFl2OpmxW7NjZtEpd7YQdg6Wfskyp0PaZ5DodAy1O8xSlM37ZUiSb3r3JlLVHS1WaL8K6lMtm8FbI7zRxzR4Mtbemz1qpVYz2FVpRpHhY/8HaK59M0zoN1dYjL0sk8PWlUd/lUJmTA3f3qOmGZrEx5m4PFQTYypJdrzJEVr/ZCW+hNkT7jiYvDjtIoZLRv/ChyCZlWt9LreAhtE8VZoI8P1TA4TzJ/jH5NDAMZTq73zQR+qox27qNu2c/5XW/KqmMFYDwXbYyhZm8RAJXgPAIOZOl1WihQnk2g0ateAdtgsnmr/Muf8I1nofX8/NXGAso5D+sPyYJEYMe6o+XYVpR26BYP4aFTdSxDksnxsEas7+sudK74eWBaFucz5mPjVcFcIh5LGHe5H21oPJ0xTDn3UDQ2fXHVIBhwJqhP4/18Y5F1GF7TGhlfWxVyukhx8K6dQIC/5uyrWI9r5LQYxVgcKhzIgRCO2c6JmxLHjYnIWfbQKYvaN0tc/AgjVNY9symUiwgIiwv2+lM+ZujSvJsVgErNg+UUyG7j8pJgRYPQPok8dzMSU0jYtMho1ZMcXYqBGySQQhANXF/+wUblujeIjr1Ihw5/XeSTWgaIYkqFJ2LW8gcFips65Kkt0aohhNxOwD1xEgfJjEdDG+nWFPXse+uRQJeYUaCrA67mYHelzOw+otL2vWBHHmhFjwl5DY65cQzb6N4UyOEFdcxyBg/st6r0/tngJAcl5M5mDgBJbO26bg4AAiUQjoNUxqD128De74ckGy2LPH3Q4EPoPnIw/zeF5+ODy/4PISlLylTgPnCxP1JKmWabPQTI2UHAqqcH7I9CCmPzroHSqUWCuqRfB4Ne3dXfWZxhHOm20BOvnm1SuAXysI7IiR5IzlgHKK86s4fyeu7nhQbWiOofMHU4fQm1EKsPru3OuKJBfYgd+TP3bI2tV89OcxNy+i5B08jUXIfGEQeVV733/31w9pdy3RuExl89mgwQ47bcFNqYArOOeNX7XlmN3f5zqiS/P4L9nYQ/LKpy3ejFIuUW4ew2thR4nhGGMtVlWP3VgWN/M3+E61MqF4jvOTmGu83quIIiJcNBFVX+5s4HD3LrZ7YhqxCVfrbi5yC11WcG1NDsIjXYzJMHLyB8PxiKm5RsMJYfHnsH4U4ZVGpzZHFA2tvdctPMUKPXyUgf074d7KJYgYrQ1O2Krm/xp3wY+rYw8gdQ0FUwKqXUHd9nL8AoI0NQA5lQ9JfukJVis95H60zQlNeuecxV16clNMwlOH6dfsH8EQx0vEGASiBhA1Z5jlaOOWbBEcHY/IDgetM7Foific2+n+WWl8xBvJkgmnnN3XlrTyoKWhlZNdvXXOmx89t4w4wm8xmGlxvdsttb12JnYdlL4QICR8rKT8gLMSGIGbMLi7xe0hoKorcRCwatDPNFOwN+cNvYAJzAj9BzLpAsViUiEhEGxMO0suLpUr9IWKzgG3HRGQh20ajs4J25B1i1dgfOEg5L+cqeXrIkScse2Zb3nWy0H2WAHU71yJw6uR897ABOxKd+lQKhqWFmSjbqHudgTnH7GqhrWkjira01Vp7iBFCWosUPP1Q7jKZl6KokaErsOP7wlixnNunaa7/u/Y1Ve576PrKQBUle08NsgZsxnftto0Ydv9GHz0fDckFdnQ5RmATLvXOjN/JmtIQ6UpUh8OABFeUvzFhP7HAgm77MrI/S83PYBwvKWYYzFnHJTFDRICPKD2/OigbJDG4UGNkpMdtlSQ1wV4Y+Oi2GOeSWzAfipbNRSErO1edvjXn57hOqWoUh+t32NdSk3ChsjbheCz6c943aynA/Pdsa+M6uaPNd/XZPhYLOj9VJhfuwvmQLkm4rGuN64kq5KM/aDIb+IxLRsNLlo1tyf0eLfC1jAyJF21jIDR0qYEEJDsTiZ9/3RdIN73NZ1EeWQRz+D6mmwVrtxX3a/g/hWtMSdjgtTxhlr2MJrkY5xKy2CggqVzDD8KazqMIBqxqEQ1tQqfe5g89YGCYP5EgWBfuKVLc3BGxEdfffzLdhCDI0Cw5I4ClMZ+WWi763v7LXj/4ymRKRzmnIhBgJHKCdFZ3Rtjo8y5GMEN+RlTRDevtgdsyW4Dwn98lEbSe0mTZ7u040bZeeIV1nAkTNcuJaXGBOx+7Qgy6AuEBfUwL8kl1iCRPH4JSHnAGkpmc3sYUCLnf9g57QgbSdITSHcJA2zPdVbj4p4sxteRGBNzBbizcu88VyG/r66cGiUCtEfbFwET5Ca5Vf4AeY9UqdmSCP4fMK14omi7G465HCLHE/JkfHn5qU/9+d6xRpLKG2yMvZjTeeEtFi8b/7KVOZ5tgK2W6zMBBtShzH4In+qBsvrNffvelkQTxI4jXuxBuP4/iFi3gpLGbcD0loo6wFilRRDru7XQzUnl/m3URxwd0L/k+tqnjGilxog11ctShvtTDFIcNMMsn1qJgmfXUWPT2ba1vTaSJNsdb82BTdtE32CPlOckucLgLsCBPNVEes348WZ7ctyGWkWxLQhpbUZmML+RKdDKkz7v2f3KmMy8FIfo6WPbIHcxdg8+K2BPu5bandKaWq6a31rr51L6Pr6HkIa5hO2+cAz3IzG57VWdPCQ/403BTGwM6HPUizZNjiGK1mfbCjzF9Poi7dr06mG30Ze3THHGs3DmVGFGAtwfkoaQJh06CEi+kD5VKM7zP1pjGk2weVjqFAGhqzaR2Pu4chV5yeeiGV/rJ4THMxWUThwdDtIDz8IuNj9SnUBtM7m7KjZDUekfj1BVFtw0deZFK8nLCPWTK5fnQ44uKaJwhAqTBRfSzoYeendCCrLfI+SIFE/ur5e/KbpK4G9tUIZWFF5FtcRKOoPwPUbbrtYzsHoIlcBgE7YR9uYyiVwDKEPt3OqDU0hItgZ4MUvaHY6lTk8+tOVLB2Vywo/xTss9IC/EwY4PrL8gOrqoPh65GMjD6J4cetP5vhFNQhcUg7PB0+z15wkUZ38OQqSys171oCdRh9o5ORAw5cL58jvztMfFdmjiEWE1gL0kKVkG9jjqEGc8GSg94XkT44mwiYl90UyMgvIa1DWZbTWmE6sQUZ3FsIsEDXmp9m420sJEH7g/u7N32/y+U41MrN5Zrh3CkXI/BVBpqBtRiUY0RkRN02HsJZpkvzyOzTI7rzyqtLfLuu0he50ddwmUu8HdjtB7dO4+5bUVoetpxZRh1HVWT8xmxotVisI2+LBBhfy1jsXxF7xuHSMSFxClonjjFWY6NUATPQ4J2oESeLwATbXvf3yJJ7o+QISSVZfnD4RaFjNxGNog1pl9o5jUdMYG9/Zw02rxE9hR/+5FC6PIWdSBa/AwNkrLs2AI26n4LuGRUK0SKhNBlvizxECRwtDAcZ+45qOXgKmXujWmpOZub0HHtli6YqGN2GgljZTttkdXz9IKEYHjooE9sjhIw2Hqmgy4NE1PwWidUw7VcWVa8ChbxGA3xT+RYKCbd91OSmZFsoAc/Bj/QkAR/qTOL6ky1FMhJXzBP/Vo9oksv7bzJGojgOv6ZjGO078ITtkzdlWKvb9+ps6AKCAOR9c2BkM/m74WSQpOQNTDIFJYmGzxlrMSV8NQZkbASNwA+4y3wX5nA0zgvB2BrxRWs+q8HopQP438D2H3iR349kjTEZLbVY1Jy6+6uTuKen82XejuM3gxe7uKLnJHbcWg7W7QkDCjs/KAtUd/ivrt7eMw7r/I3HS4PCvjt1XTcksHEfnavcxhF0leEKWM8GEjFdbiJw0ZvfWMMH0ilfjG1KxbGx/oZQhcE7i44uOuKnE8R69I65z+JIBr8mku5OFMySCFN3rQtX+WtIVaMRNRaKuDRnRavHNBN2go3DySAT9kWT7Ny4ecpoAm9bWuj8TpitLYr5Uy/MynF3+7x5ng4kLbK1gE+dE5X8EfYMFmf8rv4T2NF4gnyBOaPdUgEODysSaoOKbzAS56lN4+jLvK2PwdaT4IeNRGVqj8gO0bqY+2yOyoClvX4Jc27bNOuaWkZDjEtSXvcw6iSM7NVIDP9f2vlq8UOUek1m9LIKZdikEU46aqEXfLsWW3a7I95/5hRuL/VHlYSb7C1qHClIsp7i/AEG0OnaTe4LGSW7n3lSJrGGkrh3ONX1d+/LRjVJFz9YQt6TFp68+bGD/x6bo0/TGsJgtAWnTO15ndqMJm4vXqUv+ED8hiwpLfoZ96hlN7kiB7lkSSDvdKAIjTN5uSMC0WV4+I23OLaIWyRXP1m1yxPYyOavLp3ui8JtINNZyPoUVh2ZPnoLWGRjSlwzgbo/cD+H5WQkJvlronpKCClxgWdz1aEZQMv/RkIxwWVy4y+i07c3OTEz22ndQdH3IEKGJyOVeNX1V0C03uPzDyn4BMjDZn1kF2ReKjxBtkML6Cw9TVKh9hKAl0YM0gqKfbtaeO6Q5CHkUfodDnNJumgnKsMi6lCV3k4459uWQurt8JNVhIanehcYXnv1ys5J2kLnVuPwwaTJt7d2PHenoW131p38IVsYAwKsgcIs6Pv5b/8aWcsW9PhQTZcS0uR5N818AbUf03PJGps27/m0b4zyCwxxVLrLBMflbB+P5A8Gew+VgeoEcwZBr1ir+DpwFhL8p8ddNDhf7i4Br5kS4ruScrQBVi22DPaQthHlX08bFiP84FjHi+6tXCVywguI4defJBhvALGuqLA6WuCGA7/EkEj23xPNSA8XnDXsKMmXA3ocyvq/yB3ukNljFL+qir2fF7TD6m/fLjX1tzhBhlGMFPKa65YbMgM0TPN1edstbQ47qArpNG9H6uWIikDVU7Ph+K0ctND6Srm7ApU2Or/EydLSfKizxZU0thq0Wa/Sdi5gUn+0gXy6vscIH4ZQgugJ5UzIR1xXqbig02fJzifjhik0nGmUnq9gJbdIg/vla+cY3vFsytEAYDAO+/TVpcHfWtmHGL0ECoQG7ruLtQ0w+r1IDbZpIUFx9VxqqwMYc1a4ysj0CtZW+4nSQ9eXfISARlxRISgdrwexRhYxcudDWI42jZ/zhh22vX1sOGncxCLx25ROYyhtg8lS8XITyXxSic/SmWa+LWR0Fj085AauzIIMHCzlfh1BqQLCy234OEY/maItQ2IAaWy8C4MdlgoGcUUgwRCPBz7PByLKlszbTDA6SbRayIaw+IKJ4qIOSiBLhj0gsQs7u4nGEoKn73AOfwTBPo8APSLopHAnKNOyGO/gdD7+jFmTDKRx8mF9xq5bXa3iCZgaKcQVjiGBs6Jy1AtDK1ryajOcbFu2WOVi9p5yRs+UJ2XlKM/52os9UkwB90A9sdjaZJK6Dfi5AYcsNJ0sFfJquPaxVOslOLNkBnO1ma2lFLViKeza+33kaW7MkQACHkjcImLTQyPgLqW21NiLqkp2RvJBziWwEPhXO9SbXywE80YfikNhvKjRZq6jsItUDOau18TMiwzYOsXNqVoaBHvP5N6vDbHqQLI5UlwtrtAadA6YgwUzU7Oc93nYOG8T3yqNeHnbEOFoY9IYM1YEYwnbMlqG+PDVtx/Jwl5/661snazLouqSIiobwNXBNyviClx09o872eMpFSDiVyL5+yi36L/HIwE0zkj3UWr29pan5REoZlUHu5AUxue9S/qKylT3DsI853P4Y6haJV7f2Yd2ZULJqsUlxE4qS5GWnXog++Db42YQDrpZ9Kde6fZU2vAHcI816JV7P21lLKZ56vCmywvRdUk1k+wSRiRZG5qduYXufP9iLpR/Yyx6Bw0SLZpq1hoaQ9gYZnMmMmCCkZdJRuBgJyDjQda7eZy7SOLpihvuQnhbnH0UU0vAot1R1XHx03pUHmx1SDQU+k0PkAaFdRjQ4w8UcM3tC8WKewtF/q0k8seZJqUXNR9y7htJodvdY7I/EFN7+9C/JTkqTHayC/mmNmAMvS8Is/Dx20AGWxG2SAgoaTmOiofghHdMVA75GOtn6Tbncu5RzWmuiW59rbDzUQSlFDS6WxygjRJDyawSYPzp3cXh0sZ+Qj5RxNUeAv9wTnWFZhiQ2+Fa8qwH+jzjUGPqRRWgIPf9MC2EVPc8bqu9GBo9h168I3JAc29VPV/V+fE9k654JIl13yjTh/tzVSJRZqsloDcenzgm6zGMZtdAIgF81dQK73+c8BKmzJlkFb7CJYI1i3AeAW8rp0gYfq6bWWDq5vrjHzwPJpiG/9b4ebPXLVC4uuQBV1GdXo+iU7Dy+/jTMC4tQhaQK3KCr9cFq7aoGhB+fXgCNy4B/tMVDrDSQPi6RJayt8CuAx2x69ELQbnVT8Ldoeqee/c4AlE925V2xVb9qB46jBQ3CFwEgPLO8WiXM6jK3bRN1s9HiP0tk9E8oV/qrQbG1knIe7+6i4Hk1T7QjebvFtn+bkk2dzuUWO4aopY5aafHaAjmrdBc8FoQY8KV7q1dGQGQD/sbHazEK9aAvG1v1l9Bgtk3HpGVjLL2hkcVD4Hwu/EktnSm6IVd2PtWJYPUidvg09RwsnX6WWtVP51ZPGeAaBAK+o5fKzuDX3YCg7lq10CPExvzcy0zq1D67niCojmdPiP0+KwoXqyM4sxHSDesM7u5sMpb7pLnnFwCc/w9PV7sTMUJpYm4Zqq7ZZt0RSKKmD4xnsZbd2xaWTAlgXnf36ywtQda6XycAtqbZvHgXS0oPvxqLZM4xnYOvSe3eFZse4cEH7G3m23qIP5hE4huwlI7ZQ7xAthMeOXV9B6a9usM7INgCJe3SMCnlwP6xV+q37fdtu5BjXallS5n0xNLPObGyWjLq1jhybaM4vx/U0UzYkerRygb1DKub2y9T5DgKOxe4dBNe/vo8+XdPGnHQ32uA+y/yDxW1bD/jbRU5ppNTMrRUG1d68ICUsPNed3KAjTPdeLmOpU8oS3utIZEZ78CKc+bSpL8YHf/V1dcKrFrSvyRzBipSHsg2Y4Qe2X0Ksqq7xrFgnyLz84iHkL2CkpIb00Mbnj7dJEpEZ5ojIdOmfB+MoNwqLDbztj3qRMrvuzTGoUCCUmaSJq4+uoaq2iU9+jbaBPQdnQJNPqEkdjmqV+TtnlcbYONI6vX6V0Ty3SDRUIpGo6h1N/JLQel7ePgyR8zVx1MzIdTE1Tt4ft1JMC+81bZftHFaJoJKsW7gHiX/5zoDs06s4FiDNrWMkG0MH5z1VOqc+Z3NM9KQ4suUoD3zcVWLkwUyjL7358O5d/yGLLIkRt1QtY/szgTyC2IQzVplgLLZxTGWrm7D8xPBh+YRRmZV2gEw9XXHPr8hBYCt7vmYW0E4Nfwtr+aOCtJ+Nmz6mz3zxtd6ATxFwGPTpEv+zXt5QVL7z/JuXWglrWP8imwpPI2MWJCpos7SwURKbuK8hdcpscqXYR4LAJ2k91PAeSUDQZDFjFoMzXxX6BX7GcirgFJf28FngDBARdQYdJfyDhLPa+BC7rQevOXr1QugRYwdimJHXhMGGg1VdvJTVQA7h0TfK1kYmsUEsKOGwdi9UdK4XtwyglpDWNAm7DkNCGEh89H7esQik2BpNbEACbFTjrCNVLCPZpE7ej6FrP+G7XE/IUOJOz8SYHcDAsPss7nMFIlHcD3JnWi6a4WwB6Yo72od7X1CPR2xccQherOhhDeu4e5aKMD9yDP7NTp3KB0yE/hoOxE16oADRmr/POWh7/C8kutv/Du6mq8haHfDcudFZJvQA50DoXwBd1oenIu47d0oE4AAGhgTcim6rOZXWVOl3ZIAR1NFfTjyjmtPwe0KEyFHFC6Evh56Lfvt6pafaObPYsLMA68jc0x+A4p+ZEZKyVMizJ7rZEuojaL1sAv6kLKke/vZuMhvBAfKNuZREeLt8WwO0Pu9EROAKeYb6hJR4wWj9/xUm/DfNmBOFNgYyQ5BRcz8gNHbgGiNb3DZ2dpcX27RNXSQUzQ7EOhiaaeIT6CSK6FBOYFol1ICTBDR26EAUe2cxT8YAcdf2Nr19IRD2u+j4SJySKXViufejeAqEZ4QYFV0MayWYhO9YswinVcW7xPwBVoRxxBUt0H/342uUxruhy7uu9mBSELmNkWuJHuDyh5wXFelBtbVYGhWYt6jf0ITFhdU12+zhN2o11IZBFIZwxyEj7fvJYMi3wwsYvt3R3GzU6ads/2AnB5bNzbMOi/n8KCEJdfq7veGtrJ5UMYabLlJ+UGf5L+55J+C+fPq/ekxsZof+dIHg8cuxbv5sAYFUOtAS+mZCW3FgjdVPtzZ7xr98INqpd4gCTf/vFiRQ9c7Ceh5D/7vQsnoC1ShWYKk/LJQtI6VBhagVfXqBT48u5VYxwiPSU3DCrqd2sXUcgMQFfEIgVM3orqzUM3gYXjp/pcxLwUlwg2kaPE686jEisQIPUDRMs8OpcapoZaTHg8Iu9wfZvL+Ftmy2Wp7NFsyKukC/oTK3D3uJBe0ueoegblK1dD4aW8MLfKIwamBG/bVkC9sUXITqBOLNVKEI406Swq462+d5no3jz57q1AFpth4CGi5P0iWZJCohXKqPlzxhC73zTPocqaQa7tAJ2yxDFaaAFdFCadCRQdpnZEPtmAhsjjqOlgfw4LWWJS1/q9WNICKHOr5uftnPJRjt9JvmHtT4kilvig9LuuEU+rMZbe4PrJzdqVNr2zrZm/syT+YjRUWNK694HBxGw+LF2DJieMGg4ZEUDyqmNQNAghi59Jg3Q4r/Ci5Efeg889vs0a9JQbXK/egusCqR8bkeRmkagHZ/VRRp86nmb8f9SX5iEiil9/0ykHtIFloHWpDSD8pVQS1Q1ApOV2Uaj+duvR307/wNbcTB9TS+PIhBk5ByKVJzftBAt9dtKzagH8Yj+DIZi8hmjhNDvCj0EQQsDpI4XOfM8YGqwGj0lfgyqd31D6l8Hb3wlqt0q52/C18M6xgx+rD3F2y+g3S6w7XHoLlst5NG8cuq+quBmpiMYbDrDGXbqoiZkNhS2fJfliNzUYZJCx49AdMZ8S4BJn1wM3ymHPTqojxyBN17kXMKxren7IxP+YKqGZJxC46Fs4rSxWQEzxp5k+1C2momxyDhgD0NYI1ZDTZ1MJcQT++BmqEPI8KPncHHKZ6iJuDp266LZYC300sqQ6RSsuNGngbJtx62C5oktGonbl7bP7LWsbIBxRrqIEBk8ANdznL8k1cliEyj0pKKxFBgN3Rw6E4PMIjRe1FYUQGuFwibI4xggHK4vnMk5Pyz3ztqFyXdhj36gdhjRgplf2cXGf/0dfcOGqgv3obevAk6Wsdja1zdYN43yDUHDb1sq0N7U9xSIajXQ620Uj+vyS5awc5FYkZ3MyhbSCWdP4LMavaZPcN9PF+xNb3Aey+YnBUMPr/cIJt3KlBOnAr8KG3+9wp0V/lAC7JyUAwY6I6wVvEjNkSOZnNifZJ/008nAFLgWPPGQXDF0JSVHXK4su+2I0AyoiQa0r6p20sLLBp/dO5iBedByS/cwDt36b9mxthQ1pAdvkld6ELDZ4Nd2E/qTbXngTEVrgaE7wAKZ++X61aYRDcCGsi8hVOBwwvdDKd2HSdJrP1GH+7BQcRzoguEJdyAQWbxk+rTsOzzjCfVhd+nHFQE2yREvVdMG63el9jf0M4QSD+qWX3fpK7BP7bPBAFrtACicYeSCQAAJxqf9U5NH1qztwqt9SlzjAFGZWqCmqicIoOrLluoGT8U8/mcugfLXX2IkYSNU0ylwUMQRNEIc+D6LVVTEMUQJHutIkaQ69Y6CUyNhyJkwUDUDQNt8WxhCzkseGI2VRsov/4ruEc3cprBMlOWEjODhc0FqPm+UOC2OIa6Za3r8tu85yHckyomHzHK3SF5vZ4txapkJAvVkn6DPTRh65O/VZSGmAMIWt1Ung+DKNL6hZFG/ZmYnSPxBdoq5l6JysL8FbgPid/A+2LdHulk5rptbZrZQNuq+EslVAw7uTJIozXFEmzPgCUbkZIZ17eingVsQXSEuz0S6hzNzpv/Q/UadI1RS3j2iNuu1QJjMUUeP6e0xDrvaS/z4oiDVKeM7OWzXdf4geLVk+ZZkpntnu+5AVj+udr3C/CT9WSunkb03HB0K9hj/ZqadxIZ7jOIchYV6uaCXvVhu0qrCJn9kUDgQHCp7sEs9J7PBZiBlB7hpnYnOI6f2/lzjZJcC3CSLw56n4uu2E26tkcGKo7uciubvYQx08hPwPoNBzcwmnB27Iy1dNFXPzPu/GRTCv7Y4gSKGCsYbwwUL+QwhslTmceiKVziVB2kUTo7UdaP6w+DXn9rox4XDTDJgh/+Fa01a4kkGMapMhg8CjBBDKQJ1muBgx7Y+rdvZwCN7/RoxV4+XBz9LRpUSPXFJUWDJgg8L4+P/1yuOtgze8vF0Datua5CamZlJSBBAKmWv8AG6lCLLqaA1Bym5E85CYeh6DxnCew+iK+ppx45F3brnkRwDNLkNELNHI67LC9CLVsYQd4/QAVANGcbo+Q8/Y431rRa6ABr8grEDHNcFShlKta3+dHaPEoKKr8GiCwVSwiN1enp1OB18bVClsH2Ftvz2POsWpVJ0MyzxTuGkeJeIwMgB3Cll0L0udYQISZN1x7e6GvDY5rXd2tGNbIe0s3WPdJrZ6uFU+hfz2VRC242UiK569HdA7aT8oezAb3nDyNgVRv8elf/99Iv9KtqWlCGe2kBjL7vcK0gnKD7Iz9ytd4147BJ8NeTxKV2UEQTvEokQI0OIYxBBVy0g8tAKlGmYZPRYDbauBIZ/i+7xBrHatfl79/Z7FtaNvdpsHIGuvbZEL4FuU9OxSMnoFPJTvRJA1eabtqoe4NNJZCRthgLrqitzhUJYxvQ9+cWYrb5OsIZR/2nKlJUUvhp8AAAHKJ+8lkEtNzBsbkTMQ5xp7Dm1FcC4f4xIUn7xFpCQOv9saS9wPg4wchcKiQO8sEoSEmkvrYsKDS3ND2vJ85IK4RLQJmUet6b5wmzLkGIAkqS5GsJNWmJsBZtJDXtpR6zn/43B8f3NVBXdisvdIj5C0aoCr77/Vcgv4hzE1+PGZUQV0ccQljspJnwJz7Jck24v3Kdi8RrCTJIA7wR7Gd9+VPrFjgyH2b4HwEhM4UATEJREA/x+CWb8iOYtmfY34tg2kU76ytEkgUuLdXR9C4VZFSFIyREaGJSahbThfeUkpn9bOXAMxdPxRbkwtljFsQR58drqL+4sNl3BWjNIvh6/1L98Rp6SVbAMHoItKQ7j1SKjDC3OxTF41++UMNtAg+m2fTX7QZl26ZqbNDFcYmw65K1pnFOKdK2qdEkdMeZY+7VhNVgQ8QAlbbecbpZBcffN4o77pXuS4DENXZ0TemPu6l4yRn4+Mdaf2JDnFHLJc6rRwchdrZPNMnfVu+XlEHnmKFINHsUV7yveszL7ZCJYBvyFvjSCO7EdweBcHSaps9+nB0PALn/yMSok17x9InnbVUPTwOUxb2AnNhJF/OFJgMkdwMHFX73TzljDULcELlCtymsv0huMswqCaDzru2dY+Wznz7UZsHvkJBLPrXt7G0+T0Mm3Co8jaa8boGFPCm/JtR6Np4oeXbuj1yLMmmfaSN3c900YiHA1EKFB5w9vutM3gm10MLu33QVvCt5GpCkFiZf92Lytoz+JhRXMCrs0Hbzo9VWpcVyRkrEYvuKNMu+4cSmt66Lfsdg8USkvIDpnXUokI+s3UhgSLJi1Ks2E9cV8Ya/h07NHnm2c6XcGu2uuXxDPPleRZY8bkP66pc0dDrxRiCaw3zwy+YttmnhgGHjqObBXAlv9Hr1S0BpB8RXtrlZNYQPp6o6UlyG0dcbywRg0f7bogT8TDbRAnM+d1U9YxdqbuaRuOxpql0QCijxcLCvjzmll3GS/Z0XJ9WryHRnAayFTmHfIQqUbnUlxel4aud0v4Fnu8KugwPEt3xagghCQZZGZbhirphEDUcjFEsb1KJelWQCSU0JaKXuxrrbqEvXU75C73ttsXH8/BS2TC+XO+btxptglmY0AVO1+ZE/gb7yaa9Vbbe4Av6bBEy+t+RM92kVg7DuLljWDmQ4Z1AnQbcSKH0lwrXb5NsAiqGVYVqNIRJwy/tzXRq60Sfnh3VDBN0TgME/D2+HxQNXQHP2LDexcK1Xx+xDiXYE0HpCqtYBgMl3+B1zN8ce5plsqQ9ZHemAiqc/4C4uj+PMPxKHB/89NtjraSB9eHSQUPXagjBGUvPyNXSvABTJcTCnh0wNxxeS2z7CW/2forWd71WVVl7UOy/30WKW/Ro/rE+UEc5e60tsLEUmCmuwruralGV5De3vitw03hcSj0BN1tmSBGLL1ueiFBzm+QVvFbWMIg8SrgE+3+ajuomcdV0NrGaumIZDkORCl4pItyjNYcA1jQeKN2X/eY5QOcHYtUWw2a0OnDtvkM5MP2D1f9R6tDzJs017dpMnZbZERcnl0MHTj8PuoIVU+iaKqucZBe66FJAbyIWMyxZuffOlAUo+A94n7enmZaOwFek0OlDTTrZ3TXl7yMv2eLR7hIcIzsG0ty4bFo2zv/0g6jn4tfh9PYWHlRnXZh5vGh9Bq23L2BHHzUfGa1czYaOT59aY0yTx22wE1OvqHXoUbLGIK/uew3ffns6+Oewkw5HQsJuk9RZIOBfUf7c8QjjPnU2cPZNRxAlIAvBI+GzaamXbS0h3Yd8LoTFbCjgidrbjYy5CZEUe49snaylAaadDjUvxZa0TC1QTlQNqVuxI4iH24MoKHzunsYr7TOwK31sFX+lPPTvyJ2U6dD29ev6G2kvvJWkH0D4oUF/cKTjY8GFGKyPzH8oD8jKW6G31g1TmQkMQZAdIOqMlHMTf60kxBW1In9SvBlkHcf4p552zV04+OTCz13MfF776sLib11EqwuQpsOo5WfynYhJp5F+efRXzp6yZPmJQRmXYPRmVV8nPxbRf1ZoAmWY2XpevsnS/BMh+Tylg8cET81etWk8IjJfV/KR854DYabiK0+rj84IC3HPUYW/m8CDO6bIVUE/VR9bcl0WD+64W2upp7lby4UicdQBx+oAPmQfe+g+7uu60Cl8zpjQI5YQzzLWCt+gI+4cy5CrZ3eGd3SrmC4pkSVLyIWRo3zIlnNOVFTukVNe6/d9vZrosCR/E968UxBsl9GWOFCWJ56RB+voqlf0Jjo1+IfJy1ia6XW9nuvns7Vw+BLFvhNja9Gj9FmrjHvzivuQDW0U2ZkzaYEr1nBzHIv3MR6RXTd4siJTa8BmmMOuKH0nvzT4P7ygy99JHhhHxb2AFZXgapkZLMZGqeywRGonTLqEVw0PGRLXuZbCQF9drnPYTc640Pa19i4iktarrhfymMWdGW0e9l53PgYyCvbIlD6XugfWDKjGZfCl8l19PAEhgfBBFXTU94Ba185MA75VS3xJPQgAREsMgANGwi8jAIIpTGXMFFTLh2BfdPjaTeOX0+IU6p/z66GoBAozJp/i1lhy3f4yjMomVkupNdmSWWXQe+qHKc5AI6XCeJgeUngwczeFS0JW5isT6KJA/m7At5AqXsx5QYEaX6zuCexMfDet2egPlH1gaw9Drjykw+3L1Zmub2HN6sRO34daCIC/fRIXOdyoNWImqczr0bgsGCDU3r1DxV+v86eV0JBZ0LGD/J//kwCzh/4/Ogc4ZLepM+g/OZ1YUmqgbA+hUCloypsA4Q/OTRoR/4Ul22mgqhXfHxJdebMSoRuUZzWWoBizhTj5pYpcN7cEUzc112pMgKjKJe7QRDSmw7/vt7OOSLU/MXyBBwo7onwq1jTRulw/dg+AwjwDHJDj8qYfMLaSyR5ms3CGDVj20pNj/C2zPHLrYPB11hiQ6/rx3zFkd0TmVjwXykLckLOHQFphzridwlrrB41cXHOV49cYXuBjqHSWa5vwRdTb3dbatAahRECcpVyMGPTlAze2rZj9xNaCpgkBjeet4ZJy1SFeQjuYdn/JcnC+L8pbE73/UpwwYRVJREY5g72rMBh9sOZluBqmfYhcRkFaX7O6xNAjXXNq7wgVc1qBgJ2jjaNcXIgcNTihhnAGLHfik+tvZ5bPa83dPjRK/O3BxHqC+gO/VBEopbFDuCH5zJaMS6676+ldDa+YeMs2iv3ZQ1N9gfUEz8WaaXK3mu1L/AuMrR5AE+7amPUNvk4iEpZSGOO/z5DU4B0/V8IB9SMujZ7Jc5FD0A06b8oV1gjnAU+G9sLCgX58BHbDy0tHPRwlWB5l27cAAAOjY95GjF0AfgG+umsYrRnGu1uD7I5kPW6RrPkJQun6VGu4vkFOfIurJDL7X6d+hmSUZgkp/NacIXdMBjdEuNzcnXJs4grMTzJBuAaYsMnoTZV0EuPz3y/modD9Y2raZDBqqVmBW60m2Du8eA0KzvJOczOlXp1O2pdRcA+EY20G88b+Ux3vza/cz2IJAxuEUCe2DbNJYK2M5//e6kHek/9i5Bhsj2U7cKiAWeKg7FpLCjIWyjAk3De8oPeLiaYBdPYLJz8dKCz1wOBSfBsFfTBmf64poyYr8wU8Uc+eOQbUvufmKTARDtVBTt8ERnoYZKuh7HB6XvTsomdRQMNBcysu4GCTp9Z2d35gE1ZoARMUTWzgOW/DK7y5f6YikusTLQFKqFmSDl7exgpnTfD7/nHXKP7o++NCbpbef19QKO1Au4s8NNI16+XxcaHilf1kpR2YUWz4OC5wVLCU1+T961RxEGM+uAy4LsgEWx2u5RDJJjOqReM23O3S5CMfR/s+6hrzmQNZIIOJGhmfQYReiaAuDo14EfSLg2W1ugV5zQbaE/RDQQgMs4JssY9gtLBNktwXITlauMatpFl5eBkhCXJF7kLU/c1ePG1dHihg1HhPQklmtPeLToNaGlM7zDkv01sPtzdZgt+DnqlZB0GstoZZIYo66Zh9rWlJAoGpNTqfhp2gQ4qrnBJ8GNcWFVbmp9R3noSgX2vTfepYXR4DVMDGTxoSKv6YsrnJbApl/Mgw2Jrqst/pQ2FCGfdTvYGxFSxAbVmkQ+y07/BxhwsfffNZeLogCAdSk19tHD/L2+7HKaY6iktsNtd3neMAzx+NmHz6OhCBF/O8EEsed5gJRGJznMQOCDsz8wuP5EFF933iPNFc751ItFct+MRY91V5LM0w3O1rHsERRnrBdVLN/gTPo853qF8FI/3G3KxTQKJ0SGR4eP1KMan8PvoAgBHDBH4F13egDeogq0pesfIc08BoobjQTgb0Q6m11H6VGPtU3++khku5wBgjqmOSS8CRZG0LyuqZ4EbejhIO/CLbY3fIBuJnbk8rCUPwV/J5Ywtvp5veCiD2jYk7MsaW0xDmLNbmHNkWQf5pwraTcgDdAANT2KK3CVngL9fbtCIchxKHQC/JnzIIRVZFnRBE5iThxsbZYgOfBHmQDzb7q/24lEmmip0JUaXKLJ2+Xyr+feYtQa4H8iQIOMcGWTSnw8w5yTXe7xUb5NUCukZNzDVkyaY3hvR6vTRUJOqUIfGYI9QMjmhYkRX0ZuydAGeS7hoKPxpWHEzI64aqsFwRHUu591sVClZZ12zf9uFHeC0g+qbut8tXHaK/D17sLd3wGokI3mIGSZUoOfIg1w3mSoMZDgXJCPjN1YIvQBwsoRE+2YovOI09D2PpLRAalaa8tqFNpRQbbyBxRsAmzM97xRPy+wkaEJmeijq3jVOWreBlGZKgVY4uzk6W1J52TppEfhnltVzEVpR+wyUOVhkQ4+rDjKufQ2TakXwYMDffwfgL6Vib7jL3oZ19ZDkFbLAJgwYcC9ndE7q4wLIUOKf6Iln6rFzyUsPt64fmOCzM8Ps2Bhp4UtKzXK9WL6ttTzY/flLHSSiaDWgRpnX5e/TDwWfPnkNz+HdPXFvyNtnOAt6F/Ms13rdimzUunpUWkEZz/EL1Iy6WaO5EQEef6dt7e2r0/9Y5r5Jqk/8nu+aeRyQWH87Fh0BRxPk0QoHLZKnbtSGr7tg8gi7bfF1wQ1lpDp9d4quR5IFXws/vb29sqAH1X1qIi8hJNjDhKzrL35uzCfmD2m88Hone3vHRIN7fm/B5oe1sf1LpQex9geMN8aw4oSueP46d1ojhNh/KpSzNEkRgPjwQ1n24BqsDEPZ4gEHU5gQlQfK1nHC11TLpH6vXfOcXv5bYwo1QkvvU/flsZehrwHnu8x4Bm99TpaFruTkLvzscyFPehj+7T1XeQ4oIeq7hO20PI32QqGjAgllw2AdePmcGjRBoXwd4C5wn9h0k++2QH19WOICvjyt+nVhZiQRT57JjhHBxOSYjxxWeWPTk/8QFt4JDNerLgiSLzqZf/eGBE/zKWZtFFTFraxQAh6DbT+FqVfV0fgHLQJEFxw80s5R6CAWyjW9mHQakSsNKTEmwrv0RsVG0w4cNc5kK5xtB3Z3i/HKbHP4BoNe9A3Fz8xiaTKLUZP1BLWX164YtGc5NXSL3/tlJTWWks5goIp425B1L5izt0rMYzpP9gI86wGLLCJjrOwb6liQrh7ktpnejWR2LBb3Wwh844fEg09JIiv6uvY7o+JZb195dxXXw8x6e5aGAfHowieTz3fJjPdXkQGGaHaYin0pIWHQ7lxrKMMFeDR/EszALuHyrotYzBQDxvcG0en5lPHFJcFdwpUEnbsccG9piK4AivFL49SQxVp5UANokm/SlBJXIgOfHAoyKeMVHRDOlRLjuEuVaZI2aF/CikWUIWJWHFFc6XsxfCXStv3RG+oSjQtMMDLtoU3oJ2jxR2r0J45oteE8FPtakLF8Rcvq9M8izlKkk77plgMosJlJEe4ZIHueSaVzPExcNmJBJXvy6xew1pMmK5QqSSilWNqlLQey4VOLrbxKWqAtrX2KreIGeuAhK6nRGqdDf0BQjGxQnS3YWAVFjJzQpuTyGqphnp739ymK6vJIAV6/MSqZAe4EoXeW7cDGIG/feHUmD+y6h2wi0aAArqYXCx+eQA/cqgSHRSXMV1dsmD/C4PAyfsn11kR/z4Klt3ddGpsgJjE5Q750ZPGW730uEnCQkn1msTHrnEdIykjeMcKBBASmESsaeai5kiUBSGKqWpeqKiAoKTcMFGHWYrpIiJ17I0bgNG1GF1DzhUzJwxIyyTZ5mHDsAzOLcRDBfvNUU7CZE3cZNpXZG4iaMqHlOhf5ymxXMVGFgyYxeilPKQ6xJ7OQMijdNDbtuqegYES9Z8sNQcg/u/KbsV4esrdtl/9PFRgtjpoFKDe21fmVDY+E/Fer83XCCai9l9AXCBzKsEAcXA4/GcOgGAXKe+1HgrSdA16d1aqTxoiiR2ve2CPJesL5rbmj5K7DWf8+ZlYCHAeKOgduNbRxWGHKkA/ucO73dsMzJRa9BZL+VCACC/kXhb5HGv6KKCkV/OYCLqCsxcc6X5zYmdPh8wl7hW4SgMX9Vq3uCRrdCQemYlSiF5+DVYHektuZlFEWLDz/0Ese6D4gT2RxkLI4f4bRL+thsey1OknB/dN29c9/f35bKvXsz4Txouz6YrTaL9u7SJ3drptC/dtp/uiXFXtijxbLVgj688fHcNrUQ3QPUVibkshOYYMAru3FFdCdu/u1wWHgGeoc6SMnQ+6DBTHDijQN0RYV0A0ptBl7sG3Y/j/xB/+AQP7J1Prd7vOAM6XtjXSEMlVLgrVmnEgYalRl30PlUyk8LzjQUZmkKnCjt5NgcTIatiR8hRhGhM/BFwJXcIi4qDAB2FD2JcLV+wHfFfQS+f6MvNemCSQrDIDRNMOpNlgRkyqWGIuTQEkQ/zE9rv6cabOM8OWEpxIufkXHCf7ycO6PERNnHOXcgm7wkjal2CXvlXp8voIgMgD0RgKd5zpOSxnSiirdqFCw1sguvY1h4GYnb2zXnjjmTtFGSt0fpvTNeu35NQAy1llqi2D6VRr0KYFMO4ffCREqUmm9o/EfgBTImYDQzIuuLMODn3FB8QPTqamJuGSy45ZpQpjDNdMALNZnoaQjm5MS6Vb23koFXpDmPmQ1yUuzK3NASl+og/QzW/nrfM0L+3Drmtt7e0GUGNJcglMu2ZfOMioYZDbd6ePh20ke0CIaJBHmq7u/pYZYtYcr4qhWwyTBMTQfw+bATKR+cR5vBnwTyMbD/6utNfqXllfN3vI+taSplln5mJf2zqO9phjPyOYjnyjSZgKBDaaZvtQCU3gx/wMqNSzjQEyUqg5/+p6VQrePDbug7j08ijAPKdJxPyBpgaJJMdGmtxdFCnF0kAZwTrBVEk/UGhOctX4+t0dClKQFVpyZtDV3z1XGUKLQScT+0yyHgydxRj4iQo+6aTOfjFPjJKjeumyeQ6EUPAlkLYKVYQkBtzg29cNRxc1X0yXt5D64rO6OOWcA9mw63sMVS+gB94Atf2lCV0xQWxvtp7LHaM2K4tF6BrN0/TV+qDCpyb+yMWvdhq76bSmBZCi/XaDz7hYnWgC63w3DA08GM8Bgnj4TT9r20wD0xf8mkhuZOuoCvWYVHYZIb51G6T0PREovG9aiJzgDWhOCIqAGn/B1tRGCU70EJW5Oxf31TnQotVzNg8L6mjsIJQ5Dj/GAk3pzgZyaV791vKIgNZcbetmq+pe2TM2jtZjTxO7dW0ok1o2DAN0MBdzMNgjSmjH2lIXtpb37al0BB1Lc/16Dk4jKFT3ZooBvM1JJfJyo2g19LOzYUv/CDv+uoAOYl/JT7d2ioIbHEHCG4sZquPSWq6PCX51WF849vfsOUhPPya/lRL4q+wrH8b9asajju4zaXgRDA5J3nEdHyttJfDIB+omrLTNbdaESx1AVsR6DlYBNzWpQSd+J58Qnu06GmSztxEeOD3rUtC5Hhn/pOAUzZRTW8x3/uiAsQZsqaTVyfKwzOC99tdgnWNtvzPxiBQE1xVd2kHEm9ue4YWncXeyMBiSZobGmfwXCQNttcP7vBjt8M8TAaYdw1KZ15MQTIoLylLUNZ3yrTa9GmtX435Y8Uz9xIFuPRtkUzjHDY45Tf4F5O0I3ktdgvMVjJMKPdHn6DSElr6ORQYa+CDLOa1SCgS0vF3s+bHqb+Je4YSMCgT0yFhyZLoIBxnAjWAVq+1MyHR4T2BvtNEhUMd4BCWZo2ZQg+GGKQIL40HYE+iFDtNzV4nufFLTrleAyM5se8dFJNFLLc1uTJkDw7EaAIRp9FVcNPWFWdREpqmMiJqmqKpxgnDEVQuTF9e6wHNPQqvsI9P/IZ16eIc0/uHCzWuxP01k6czZMq/caNutjup9zrQCt1/Y9NoMN+TAPVjDScBcHLb90FmH0gBMOc7koqoFzcxYzBNoOl8rTvnqlxom4uvvqxFgkpdWGmxju17CjfuSM9DHmb92DTnOWoaMl4kHmQj+CrCu2HWp31T9TyenPb8/cksp0fxfTQNQ9zoCD2EhvVcVJDsQtpTP9oJ2z55Iswym1bnqKl+aJ5GtVuzE4k4tWa5TWWiTip89Cg81dQjIYRXRn+XEeuEH4dJqEiOOvbjPhYuABF3jt+N8i+ws6yw6fGMjSBT/N8qV7jsg9ZVPL+VTp2/UBWefXtcUd0xoRHO+EEip5T2/Qrv9eav6Phkyl8lGmKjzoqkk/lLC3j4jXtlR7gSHiLoZNeC69d57XiB63oAzQUwv9xWRp1VwRWPUws8RyUNEyvUHlYSGnN6EIodQ2viepXvvysEomWh9dbwtxCs+6CMI1sk94KYh3HTZXV7kyF8KB3EReVVr0EwEhGrY8PQb839zw3E1WCCtz/81GWEx5+nvC6HRKTSef2Fi1orLyG8zhEFweKsrGL+HOIV3UM1XCxc4iTvklL/pr8AF3Y6hKEbM75rT6S7MZfr+0QCrHIyIQcHRTPG1yINjyLAVYW38VuZdhJgmW3og2OdKEkTowt+psSlEeLm8uf+f39K26rYIULOB/X6KAzei/LwJqKEBDivWqZlBgb6uey6ICMZb9QWGFdQ1p4ZK6q+f5XdVF46E5K+jYQvW3A7o9zh8tAoFtO+RorOvbV1nVCkYC1lSTIIHFfbIiqrdNq67x7nGLKg717aEO9arZatwoYtSMJu0H7ZPK2fwklfAHaAJk4GghYp4fzIHHvVq7ZBPK8xpzTZeOOmD9VSTHlWrpvB15v4fSxVMSwDKv5LGMvI9bt8zpX/e5btog1jMklXU4rBibEe0CptwzsJIiedfmpjGnzP2HGz+KSWSxJ0P41qeBT3ZXbIumw0kLv/eycGnSJA4CYeQKSwS8wQ7cDOem/dF5Vw38iEztDp40XZyxs+4pKg5bj4YFZDrLz8+oXEvUjWpSMenHzC2DDGfbtAwgZFDESiJc8rTNPv8XXx+U/2LUD+M4KqeyRjPAccC1CSD6KA8FA8CGFKFX0Fn4lkLNP2+rScBeWk76kOqk6Fxv2KV075+/clR55gWiQvMO3V1KXRVTSDNh9nc5eiwlt5/wL2VCz1VAsjYZYi7WVwc5fTzB15QrDJdQswfMzKRUJwNnJNqHTfN/w3NyKNY2F+yfVcn+M0c6R2Q9JW+3QWXqIk8WIS0WspCLP0fi/osX4ufpvhidMPgWEGn9TLMTHJi2zClMpnlJ/ziB9yq45zDEA4btt0fcw95AbA5P22g8M++E4ISCAJ0zSRYyIAdBR3WmcjHoqvdo+RcnI8YxGWniecXZEs78Dls7Fgkwj3kUqdw5n5imZEPoRZHlGhU8bkZw+pY5ceDFxTrV13KD8UtNJQxM/ffMUSG4gyswFndfedrfZ29nwLr6fVDt6vXcjrBD+4dp9TGInVWtb9NdskXvwsMRAoCmCvrURAQENCDVq8Da+BTBWvvC73U5SNZ69WM/587vQiMuD/KV5CvqyBfZXeWiSqupYVN2/QJEbAU2SopYz1mImgHjshzPimzHY4B5IHVO0dbVEZFQKBFAgtar9iWe1SAjDZWdhJRuOXbN6OkMlu0QXMY8anJ9hYUV8jesiWB5HaxxJtUl8vKpqZIu2UH6anOXkU40kK07J6b4c4yO1UQ6XG4/XRk3uCkeqe1sLDipRDybaEM4ZbGJ52utTCybN2BPJctDdnFfqpulLV9iFZuVvV3hl3+8Vc6SRwJKYv7uLbNnui7G3Npmyc2hI/DkheG9jOKnbwaUU35HFimajzK5+bDH9ucZ7jqQsE7nfITNxpJ83rnVIXJkHbcxTh9OGzqTAxtZrgTKPlqeAGsflqWgRrG3itOyDsy9atGHR5eb9D5+9DQgCYk7lPWT08fT032v6BPJGhIJOKd3ApstFjZIOBbidih0uAE06pyda3aUFv466TFVyV5FP53Sbxx8BptXdNw6BtiWZalVkIKiQ59T4tFNGNIJ9L3rOW37fQthey7TJ86QD+T6lfAlgw4E8lmYu770Xw0XdlaVvtLN3jrum2Ep4F5B3z2jXz8sVQ61fYFol91QAiskLw/e7/sW2vQyunsAMjAOYzkj14md5W9iWQGY0vv2dHOFunj7YN1rOJrS0L2brSOGOhqhRpa9gUilAkqQBf6nIdaHbagBXigEc9ynPN4tDH/ua89YbpPywtjDb5CRcVMqHR4rMwvWBwn59LZk70cGOrw+J50oldCYjuxga1WojtOCAHTBFDErtumUOt4aX0FrK/tLY87VZyAYIkfQw2q8SXnsdcEa1phLsujJZQEIWOFSoSgT0XW2ZKo1cV9FWNC8qoX6FzPk/hr+uU2QgzuF/YvdJQG5j1gGnOibrzK3xLo8+4+mgECGA/0HB/OLW7gxZ2EiTd0y8UWOJxYss0t9Uty6xnAQC/q73KNAH6fp01rRiGTV1srH3i+UOeS1GHNMPx+s4Pen2/3IumJ4z7Ro7tkgmRDTuqOF0uAFCTW059SN1a0TJBklnh1YVb8YtzzwttJcB130KYMkeFWHfGit6IcpGucV5GTHudSHqlGYDOrPCaMZCWPdKBgubyui2UvbTC2A8EgxWRT9DLpN5Lc3pMNyozBPLhi4jYv+T4y2twugQdZ7xMNv9HvHM/R0Sd91G2t7rr2i0OQtkdhzhIQ2gvN6ToR90H3l4/pNMymHe22QrUygyudkGIc/Z3eLpR75JeWzaK3gSjweT4psUxU5Z9kfJBRuZuvS2ARcrq95nurC/sEYe8C3MSSpZsI+sWACG+KnGy2NGY5XA/6EYRlasBXMjO+ljjdQvuXT/NvOejUY3LZuljjFwfXCPekRbOgHeFsUjWzrwNPPKguEfRpMscZjdQbQ3vw4RxqU0oOz4fU60Asev0O5CdWua97D7a5s8RP/UGFK3516KXSmWg8mi4gur/SFPU9RtorCTlnEU17sk/+rR/ypBhD6Gh6dXRGHWkHtCrJsJp2Y7OqFfYqcFwSBXb3cmxlbQYmvl/fPPgpcAs+wtdhBfW24nE1SSFWndatv6g9S1CcmcVe2jdW9edhXpaTB2N/dHKMS56rn6N395rlHDBlfX194FT4LOGrDUArOUs2kZtPf5bBjvdvTKnxSVHpt6pnECKYtro5k3HQPy52aRO4UHLODO6IqiV4oK4K2SNhtlDmE/dXFKJmWyaXjcc2DB9PNhWdtZjDsIcI7r0F6j6RUQ9AhmLSL367tgBnrs+J+WO5LOoW7VmLdQwEF7nIUfdUeIVtQhp7BSMD1l6BB0V8Ds7/ZHUz6SGi1sLcFHkyWyT+vgcDralgYskmG2MhL/Etv2vi8RozrvTlUSLY5BUYf85PF+avOiLTy3QbxPkOws61P2qzC9om0eg/BjASbXwmnz9hSY964c7rXfHTrZLPLJVeZQyCWMQKWZ62/C9z5s8H2CLia4IqTx4VbzABm8mor7Nm3ircHwMssMPyi5aTgBA92cG/gn/P01TZrHGLVZNvgiiScWBPCvEhXDKezJjFnAVocy0q0bIYfr6a0Ga694go0mldHRad9sCBd1wpbWkRI1nAFib7pHDvASVvPiHgST6gQpeqKpAM0zf8mmrP+C0LLnDZwMIXGye66WaP/3phUJJ3VqFbc9RWMPn5C9QeEsJRtO2e32xEHfs51+7Vfe600NLIuyUebqqNPB1iPVcv3+SGmxgCkTc8AJ3Em4USjgsMG+1CorMy9BArXEHDVmsAS62+K+rOdDzMM90WwhwSfr/Df+V4Ha5icXl9LnXjW4wOo1ZytDHCxyKAE4FnDUhCm/KA2N7cB6VUjWUpnMAyq/UprJy00bBxrh/mUFG+1LDXzvXvKm5YVi6dmLeeWUJcz6c6obWV0wGfo4L70Wsf293wDhEj2qJsxaCxjJZ27oMYWDs3cW7IHJcNN0Hcq9f9KnGGu0DzVoHuIUGonsQ0NS5eJVrSdrZRqK5u64xrXciA9XTjl2bs850XXRrL/0Lxo3w9JIuKMqefDf5nnlUgaxgZgwxW911SUM4JTwdB1z0sh8C1tC2OTMRA8m454qPGfLC9jPJFNK3o9zna8kpUCVuFh94upUyWLo8PChy9Cyf4B2HqzfgWTrZ62IFfC6scs5WHHSzSADLpPyzCMqazjegZSlYt3NFSq9nsI809O5y28THafWE2dZ7nQ2WBGb/JFeDp4N6QK+eH5KHAkKa8GMh9heDVgIJHWxu2SWhyymcwLGW9xATeL2OsUMwHs78YhZUPdPh8tZ6KZ1wTdA6ZTR2r+iZB7sxGTRh/SZ/GopTYwJJEISlXPWmieYTuw3WXUJsO8c0K4OCc+ppCnSzNH+aS8OlGeYg+Za/W1eVgEVSXV+GDqQvg52hLHEwFfrZzFvmAhzHXpV8QWuh/v1Uw2WlduLjJ6zx/h7+xVLOUo+HRJN7rMzMwpcKhCDfkpJG94goEEQisLKt2A1bp97lE97BepG1M18b67I375Q0aHLeml2crdO4fRr9WvXs2v9otAM2ivJHUBm053G86i8UA0DttQGUBkcG7+6F5sucbmAqEzRSHsdX8FJgBWz4Q3HoQ/pU+H3Dtdcnrop0ALHih+za9Ul3SyO63F/uelY3xlZ4A3XMHWPecR3WnlH8AHGgpOkJqe+dsJ5cbtO/XiGrFfJVRMFkOH9P1nWV/NQufKN0RQwdbhaLuBEjHBnMo+5vw25a99JqLwIoT0hBYTVsLcPP29Aij1CypWVd5Le5N1PrVGjZnc01tkzqXgkGcfgQJwDDM9dlbgR5NUvzymMVsGGRyyWrR5ysRiyjE2Jg02agzPOfH2oUHRaGjfRAHrYQ/14WOMbqtT2rMJSwJAlo8dJquBVzpeGI8ZJT/1KHV/lu7fGHZTyIEipyXm0f3ycNFRxqFCwXjjXu4/Ar82Bfzi8OMDZESwEAdDoR1/z615i9Leh4hZTwnMwD5Uznf3iqRwrG77uTK3YqrX0zHZceIfkb1fRsgqnSg0JnCscIky6GtRH9jvFyuW47eUsGq3x95WqEtb+Zn8jX8w50iOIi0OBX9z0KF4WNfZaUwmnQuTCuNjb3/Zjhw4cW5vYKDBl7fZKkf+V04ZAGLJQZTClwmWXeiFBdIMgQY6/R40q4HW0rR/En98/lpKmKDUb+i3Ux4y09p+Yeq+f7A1Auy9yJNHH4cr6V66sPQ9Q/tsPd+7yko/olu0Hu7cEs1J7E8q3/O1UmcaPr+6tCDgN+lqjbDi1RZep5gN/oqOdM0zyy1JN6U3hMbT4LFHnbtLbLDgWk7ycyzA2LhrQiBbZbzpeM3Qeg/2JRpICmsPW82UTRgICa5kAqiFMmzkudobwZ5u9x3HOoUehC72QHZuxefN+VLU/C8uJe/M0QHzMlF8MSwiDMid1Ikxx8fEy+j38BF0cPmQScFDsXPV3rQqSrahHYpzDlIXxZ4JLPUkNUWxfkqE1U5yYhzb7pFHpq5RucrSNecMcjRSDW9226/3JCoGTRSGVuqPj/u5PFtH8488qQVt5mOm4bztYqAE3u98g9qbj01ZWX2RcK9sxBVsgrMsVUXag1SnyAZKMWoEaeaceNKbYvHXHSayx4gwg6jL1O89XyCdDkm6HzqekRmmObWaO6bUbH9ZNsVka1+ViGoQvQ9xt48UzidkCZQTWHKUh/vaVLYYkwwubuaDkxYBPAdxf05e3vNgHXXP/A8fyQKd9eMy7UYel10S+rvtwHr9F+kKxY9NqVI9opGk8PVrwpKSrNwzHGCix5vJt5XQ0CNB1JvXh+ezZcaQrS/EA3Q+yEERkYUFFZxj9PXqsCZBCugeHFh7Q5dgW1veRWZUeWODGndMlLck/ntKp2yKocBNTmmWm45Dez34TbC9Vv9rgHnCfGrlGCDWLJWpkaqgSmJXFsCeybD9pBpV7N6zK+LbympdfpEsCll8fe8rlePqkmt5RXAXXm0uIRdFlYxvihXaXt0VRZI84MVK/TAP6aWmc52b3kyQML+hmILf2UcDXBHP0DDOPwYPchjv7ZwzTd7zaOrMztv8C/WU6/iF4PYCHb9sJ603Tl4AhhlC2VZ7w1kH7v39rha/UyrTuKN3PHcnzXQNTOhO81qerVtMoUHf+S7o4tMPeF6QwgpHuIkFHEDPHevItmTdwUd2lyJPHcMkI3bnzK0Eci6s0IhJlMuxklDFUL6IRymn4R8DJbtxBoTqrnIKqDom2nQeMbU3Uygb8Vqhj4NaDDrJAmaRnxOpna0j2CU2FeBl6tvd0WEGkEbrg9gF71bqLRxf9sCKLIZlOdZ5mt3iLLT6mUr3d/utHTwrVb5sLLDEqJDLMN6Gv32A9HGJmDoQeM2+ELAAu/fWdewNnyO+UJff+BPUx7FTxhKukMfpW6SzlELecVAv155l6OZCSzHsqa4abDZafKI1Kxx2/bh26Eu6eC1g0lplpJZ7e9MmBwV1RRhDo+aNR3e+uVAZhyxcA5nbHv9M2BI4C52Nu71slpik8p06r+m29+yWjkOQHGoGf+9fAbpqpf4N5jYXiRo6Sykb1YwIonjN2fidqPO2sgDoSg0uJRsBQuqACMDuUSPCVeKpPyTbAhVgCp3P7iyKH6sRQsw8L2Q8esTxd5KbUgrwTRs7EM3Ep/M7nd4aVUIO6KNGL6AWfMrtlG1Dhbce1k0w/H+MRbOpWhzfVOJEJDlxLMwaX7ztG4PXvP/ni8LQ+8s3AdwS4xkS5yCwoaxUGBL7l/AHc3MyonR6P4LGVz89lGuxrFvB/T59KN2MbkogpTX9cSzWFulesDipRFH91kb/ysLI8t4PT+yTwFbyT+9b9LDweWhKdQMOrL3b0FfzIvCLJuUII/lj56FLj4MhOmvqRHvlJXZZrI+UpwK1deZDDIACzRlQoqL26qdUobourIYLFscL4S6ZwuwjTZjk7wc3jeFA0CBjC48bojSkfbRNDl5FDZosbWeAwEOJjmZKktQGDkWDSywVJ5TsDNh04gVvFm3GvKVi2vSojr8OFOIb0jfKrPizvJWZP23J5PI3JJxM6qwKZAjU7t7JHwSd1SU1LLIRNO1w0cSywvGr13SkZLk4+BSGsYx+9ctM2JtMlE1YWQUbt011bedMzThJFOo8P4fFDmWXtxYm/hhaaZgKWXlq0EiZz48XNLiLUXK6YWSLT9UXzv+RRdK4Bb9efJdRidekkqGB6vd4ZyTSpwpjplB8kPI5+EPcehjvYHBCbgneBrjH7b2kh7PVFyXrhJ6FVOCfCUna0tEWlwymMsIA75IFI08QvVrTiae6imDBpH6CrdwmPnpJ/Kr9Z6Oq+mQiBOJygjesoJ9XMDiOiajUl3ohDSZKQMW+YA+fPoWDBHmlNtcOGtodN5ktYmnZebqTPrRiwQUdofz/lgNc7IEpkS+AJW7kF/TfqCIhXt6m1GEKRsyQxLUmCtPZd8U3xwqJCDhdSUZf5TYPdseM1T55LXECaKA7+/a2NJr0BzpRpaFKgyG8lAyGz4Ta78X38hzGSu7dYe7MR6Yer5eEvSgIqkMgFkiAwBdLRiSn/b0EDHWUXBpxADX6NFhh+PPctEH7AsJ2mAPetRjzTX7GfSpR4nJDg8fCNRaelA/DKrJZFhePItJB6/aKL2IWuMNKLdbAv7MI6Dl2uqJPCqe57KxXFXHGnhaTbeTJoYvX1guFjN1cBbWwj49z16waEbAsGM4StGuskfI1pwn+BQ2CZiEiOn3MTnThVrMpbojydBsCMWwww11kD7LD7umTpk4PSy7zNp6a7eC4mbfyMgcZAMoelDrESh368zAxeMBwYQQGJDbmZbyOAFLBCYKcvfP3+Z3jwm1e4+mCZU9jWE3b9PpFSTykmdC2hMOmLIq9TUJgjDnJaxInAbJdHGzHSVpr9LdQG181+g9ChT9EBcfWj5Ppzq0jrBcVWCzvsx7gccsxwTDRm6S705n/HSiutdzSgT/3iRK2Cbzq8Zofsltdg3mG8XK4W8HhK7fXjcP+Zcdr10X03ro9xXelOz3xXxC70oVtC9rtoR3QRTo9x7D7U15hdPCMVi/m4/NNVXID2OBVHL69mgwFnkkPAc6qy0XtQv9xUNghLIUsEZGHKeLqtrPqwOOSPQ10p+dGntHfcyLuQxgv6Rllw9zOduS9dJnocDFTvsngFxtbtPvWZ8DvhID6UrrcBGoxQMlTFhyhlxzkrI8PxGqaGb5PhB0H/8snfAZIYoQpT2vzRnBxH/OKUS5d/AfOdyT1XbOCq2NoT1vVQ/r8LhLQWbdAA3BkrvO7Dqd/Ig+4vC+BxfcOtWGrnN6ND+mzF58zs14Qi3hQoia29tuRh8jExBHWXzSp04LLP+yNuvcdylDe2NIgLJRllJKGXliTA/S6lFeljdRz0JBJZTVjgK3QO7ZM/4ylpklULOKYm4jkNxSADLdFtB0acbcGLrrb8RA4ZKjiPcNHjILdyQ3dtMYeIoAeTpqNDVpe1DNn1ON9TB0IG/cDPheuaoC+OjO2ihlJ1Os8pY+DeJyRYWdR3ELfvbb+a/ePEMK378gsiMohSUfa+Hz0U+SqvKosmcZLJDfSNqFknTO8IYrhCnmks0QVL/U4nZhGavH4JBKnQW5YuZbfvhuDA6oN3Jb/1dwNW+4WEHHoWHLe6w4V5v5gaXmqdTqoNdB/KXrQ7VDWFdhcGsPaZhazFu0tAyPbo5ap/DuZ6UGRdomuAFsWjPR2CalU1nD2aOhw3HspRraTzfAg8a5W7MV7XXw3hXlVnIbdUT6abh7ZQVpdIM+nS6Gsibl+wmiRDkuQrA15MyLrDxMHuOnGDsUi61v7ZUa7Cg7E+7Q5Q449VRtCvOtYpq5usubs3J5kIgICZ0WIBzu/g8EzR7Ah74Vc0uwwFs7Qnw0lXcwgYndocZnKfYSVNA6//RPnsnFlmgRK/BSeN7b3Y+kpw0/mxpNeHBG6fyTYGkm8q2nIHiQrvpjwTfht9Nf6KTtUuK6iJGxKgcOwNh33SCu2fcGvWlczRYSPBXPwhRva5SuIOqzMb4b9WSYvAP5H6IFjgIurkyPge1umr0zoOtwvTcahEsCSR637u9eZnADjNqqG16oPb9kxtcCX+jGSufbgiCNGd6A6hoVKGtw/ThTO6BFussHBO5cExUMtcsV6U9GPWDF3qwgIKEBOo5KT+3a+cnin4CTrCYkZLgJaLuGzmGTNA9LZ/NdqW8iynEGuSGirZabrBqGdCfN10zkXMuuGrY61teXIWljIF2U/+yPIm9/Kl1wszTNvXHatF6Z5JPNIO8k6uRAzV/UdWg5MfhHIcjl55LJjkXBQlHHRrmfyVlaHaMcEqImAA3R7CIn02hhYsH8txm/5pzPN7UGDGmG8nnzNDVsclDtAhk8xY5sYzPo9uLUJvMxyhwcGEMTSfI09DWvFlNqzmNC707I/P8mCdwrHtLdrG6Q/8uj0havSRaTyb1mEqGRwTMOt5u1gF8xWNeUPtgCEKO4KiAZHbO8KxSnbm37wYNK+EuyrHUux23lxVXD6tOkcgvnpYGjiz99CCo4LgQwp5ox0YahGHR8lMX/JvilMYV5YdNlVeL+ZYz20yrMjlwSjQKSeZbxuu+FXpOm9QXK0nQd/ozcC7X1XjtBZmctVX8hSndw/hiikLHuDnn4BY/KyDY+OkVw2POIGnpl2QghZrEH27D9lItfqjn7+h0wnung8c1nR3sbCDp6Z/O/R/3mZINJ1kCI3uKz80NlsLpw3CqwWohDaLRvA9zFKrA7x50NGZgRphmFWi9z+7md2QKi4XNnWRdsIK8R34f+tXYPCp7x5herZuV3XpRyRoV6psVmKr9l5VTjDJZ2/GYEDLfzDLQW0l/zNXu6Q4HOahbiy8RYCdqAxayj3Wziu1XJuVQzSTs1wVL29Dy0o71LfxmBFFPeeewOA9hmCAFUbs/vCvXScEHPgALK8GGRHOjdkyZTNv++DPcf6gHiEZYu4GUVCaKy1JupGsSq/1NQTqZFJtG5ldPvsinfBNQr3LOdYL0e8up7RrDEfUhnLJLtPCrQ2+10Jyfv7azxrDJ1HPxCkK0C3FSdr0G0HJ7cOBQL4+ochQpCWRw/u3NFp9+dptEkBRpM4OBA//cCN043ChxumCXt0gOXFB6fdhLkM6os3qoEQW4U9OkeHw9DxaJuWUFYeLxqacM4/1nm4o675Haz44FkmYf2KIwfHjtCnl4cev4rgaVHOsZC4mbtI547/1IqZYvA+Znatz3619JTW8HRVyTEAaeFv26B5ZerBVGyjib5tU3E5a1vnr6a67s5BYeD+0Fm8GrigrbLqkJrReIQpC4nLphVVd5pdnPm81wp2mxdFDX9CqWS9n6qQQPZai+0tGah4jCYpssZ4fDfMn4HbdQnaXKfBBEAOHwLwXsD/f6MzinDrO+26vNVtFvrzGUxNnM7yJMi4Pru2lfDwPH8A1XpVEm2tP3gJW012Yt/2OqWx/8PPtcP8G01I4C8qjBzcojbJ6UymBLE/SNxA9p+Dl369jT3CfDlK0X4JP/pA+OjRnqTLcW3pWPMjCFxi8jg3YlQA9ztol9v0SFLfHyNUJucIj8h1Aorlg09DwvafydQmLTMMnoEzJNt8yiksmbwL5atS9P2MpcFFPWTPkZfz/ZxVRZYMhpBNFgpfKFjtMWPk5ERTiGVK7IILlmTKi+1RoDXW3MFatbkY5srCblB3A1JBDHR62FhFdCbQCV0ugJ4WpawlsAiGUb5xR0GEgIaDz+PjpinPXVsACJ2VUBmylqSV1G5X3PycNpVojmOp5HDDESOkg0YCWTNuwz/aHZoGLMh8mkur9J/FjV5MgsAF2u3H8QYSoIJfWdtGdeR7zhHZR44G9DM3DmXW3A4kJei/vT9U5gpoMHhoAfz58mi/VopHZloq3swB/KVydtbkn9AtlyPRsVinNDXRfEp8p11jh71WyxRETK/lqzJ0cTDnenVfG+N57iITneS4s8loRtRIn2kf8dGCJbcPRZZIgwhLkKcRnLYz/YUK0i6jyZjGU3q1WFiE5WS/SLJdofnOyDTsPVVH5QK2bEsayYqlnpyyFYCchsfjpdRNtAcQdGFx2SFrd1uRwtc3B2WF63c7hSPwDBPuPO9qpUTcn7rG1ntVahEx5t3Br1xQKkSORGjlB/or1luwpb7HnwjzY/SrvsZxUkxKbOWczouQETAtxdEj4IuYWFRnxLLUDM2nfKh8OWQnAS/DZAj8XZHg/FpZ9raMrd/a3GfNhadVmmGXq3MhN04914j58eBdBFz9kPThE++iJED/4d9cgsJ1gZXlriuZi5MMF4N28/VE21Z/5P92duNAfhLiFBLrUS6VWe1Yy0lAnIRajb2UzQyPjyp6meVUZSqBUR9L3aTOoHx97QLpvNOVbDgPnA7i4irAqZkFmMy3BvuDmzjNcQilyGFg8VNv64Sl0xgvQCsNrVttgR3yYFp6Yo8YG0KZSGuxQU/BACPiOIlGXzeWyP4aIM5FN+GzGOo602FJOfmoZeBMn3ktIUB1pLNnImwLPxeoSM3EFla2DRWemd56r2Gt2ZpVvW762BQAGfjwXx2lYlIXEIpg+5LYpvqWLUbbUAHncFFyTEAnsbQnAyE7em9NDV5vrRdyBYlMFjM0LZxPUNhY7xfPCJXcfwQ0frIa7MDXpGQQB4N5ZJWbql+AiheAB9uppm9JkqE7tRZigWwvs233JAvXUI2GdPzxV8S0eAPw44KB0/+Ferjg901zktEEgG7FcMb02G8jO3lWKKgeJ5lomU5n2LmDES0hTL5iTIpkSYQvbKY+e3DDHEdZNeTmh3Tt7GmAcV0UPF9rffLCw0iIR2WQfX7vMVmb/iHPJR0K2Af3hUVhCH/wXeylu4IHNv5I4vSWU4t+LM7/8ISgAe/YcE8Ouph+N/lamd3y1uVB4k6JMNOS1ItrjCRHEzI8mnPuakiSC5ZqCaBlRQ5PEQunM2rLAmZR/nw94agFYnisP37xkFtWVKFQsjjKCO6IU7sNrOsvPqRDn7GA0+T2hbbZAMpl8Je0DquYIYN5fIy1SO4l46oR+48KCXhgQM1rGM2o5kYnz+ADLYMVe8wEnaiYDif0Z2AHc8JL4lAPmGgKPTbdY9FxWvwt2314lZWKfSZPCQ4s9/ihioptgGDwcXfU42OYudLf+DcBHzSNMD6W7uoCz0pLIHJS2xNrK/Ni3ELsZcZ6IcQlw7YaUNtW9L/cYthlHvWkdVv7gxEaHAZlfe64fDkJdBID/viBxRAf0uTGvsbcM/zO6jRaXjs5nvmAaqfrc7qIxgtaP91eBst6jFMtiycqnvBQfowzGvNgeNykxOLsbJx/TPAG8lYcQ8YNTtEIJKg0aGxB4Ar79n+5fcdGMRgGrAYL/2l9kwsEdWIyHkLENZYJUt0gYvO7u97jg4g/paUJY05I/Sjj3i0jdaB4HdzNf0bbNK3bKtOUlJXsdOJQI5l5HGV44RGqUq28xwXKQ3uy0kMSxZYJ+qqbFN+blvKNSVfeBPym1fgJYCIGMxZ4Qvk9umYVDBhRZ+Hg0adQhx9OjIBLswizSPb36XAxXv/0UGiDLdQ4D0NSDDwtbIXHct6U1VgcX0diaJT4PbUAtvHxjRVKIkk8k4C3bcNeYgwZXoT38leGTvs5klcrMmG2umWVhHA7ElvDHkFqJJbLWg163U8GTCDn8rh67oEYX8EJDmOSMgrNYppMvnKb8kO5LpDX4NJ9AqSmhsz6amkjqEHTewdiE58ler+r5DUMqeoByVtStldXpXjlWN/5HrUrXh0Uc3ucDtZ9FvI4SOqdfLj4hdIEzGMwB0a6VxaieefMlcJPPR9UwNPMzPHuchNKKSJN0De24XEUVFP95QETPFjJkqbALVzmagwx3+3DUTm8NiiPLMpLBp72WVbxO+CkOc5PN2AdEiJfYGIZvaW+DkjVLNtTmGOvHKnMS20R+pBdjU9ejKzgVWx03gfoNBQutS0K83tQBnVTl2kZ8qJNaCkmnq3fngmRDqF43UzinkS7NuMhSfCOhjSeXGTU5ise+RblgVTC5I3Zjs1FfXvii9dAFjPwF55gyV+15ltVMpbuNnstzxNKGfNQNFImyJ5+vtSsN32AxXjKRsxIo27rLmgXKXHItfcR7/djRTCo4N7h5qY/H8nvlKpiiMYbBfzrcbQ0ZGLk/pGFA/ey2OCs+y7YZiB7tlfHgiuh9lsSRd7V+NSsBZEHQhZeYtro3LeU94adAI9P8FdosIyEI4BjWxH2kWzAr4tWSEP8+Xdx+TuT+gLR3GfN/E8bJbQ4uivtDyg+/4wiNWCNyJM/N2ahP7K/N9ajuSuFwbXBO5zmkiI10Q9dZVfNoz8r41zcZ74m+yiCtduSMx71xSfpSFVIhx9sx/Fq9w9yTu6hsKBllWxGUtLgQPKu79XnoxdrgrwBrpcyD5l62Lrx12nVgZcTutDkkgZsjLL04YgbpO9RazSWVU7qFNX57yUtLALZiUFxCdFrB4BFjv/JVKcu/MrOC/3bJL8vClAhaAaQmB7sQuEQvM189LnQvSG9dbJy9vZaVNps9+iMbf9ak6udo23B1yZ/Br+q0I8GlGvq5FU9/fadgJd6yId1pBPVIp1tABjtzqh+LbC5lpMF/rCrQ3Xkh3Mg1jNWaDmXLuj8hvYnXmCnS37COEHCy2OlXT2eXmPRWJHdIBVofQsWr0FuKZhulA+v0a9bHQ2SXvQeGCWWvcVZgC7C0WxsURl8PoZcWi7aq6gDKf7R2/AawXCrKHBenWdlbfXBo+Dhe+ilZCqegIGbSIsVs74agrPE8/k/isib5azo2OjNnSev54NzEVwwCDAlTd5mUlI3buRnvmk9bbiQVo45x1VwjXE3M9W8yY8Re+G6PmwgfGycHya4iK1vVy7p/8UFOVZGZHARQC0Fx7Ly0Rgkk7/JM5LRFUXGAO6uhkvQLJCWZMQZ0ICv96rYeOo9OFOi9o1QOnfG3F9b7L3xne1Qk3f56iWMI8wON6OYRwq+GE1P/eIrHmP8EUN4wqzF7TlQthly/rmzuqoJotj9jYC/NZa02TjkOBE9tqJ0tsh2dQByNSBX1h3Q71QZJe43GfXpH4fG40Eyotvg4giIotFkYiy8BVv4ieGKk/cyinKRrorrqCeBrm6IPG2Wa5dfgC+4A87OcYToMkSSEIOabV1sHLnJcx2hYmBolEZ3JWXvwPHCL0eAZDzvYKOyix0Nh28qgzFysb/hAstwPWEJ0B4HCBTVzzQjAVXnjc1mKsOVGo1qZkyOJVM7jfwjkjQGD4bRUYNhReJHhzEStomZ45KTq4AlrMz2KcpX7hmWiIKH3TqaHMLcTBzh+w/83bjL0bnNjx29erPUIQqSdwYBVFSYHpOmCilugVh9TKiHFPxCMMPgBV4Vt4r/hxG38zsYRk4o1QJx6y6uBpF6xwt0gTMGQP36TT1uRJkhdGaeDu7fticuAN8TbpIMRvQzT4Qq8ip+TA7v3SeMUhOliMUdT+wt2uPjYrprNRz9tdpkb7HvPUhmRZF7XoLrDtWEXQdgw+oVJ5FBIGBzAZIOZkgsMpEf1/qmZK7fDyqLqWRWCnLnQlFIeaadsHnTn9WHr05j0y83KW/19XL//jViowR81IwqeZjlMnMSF+bMtd8HFm7fEunlGo8CNLMO0uSpiv37jdIjhxAfG1nRYYHbH3jgcA0O64hzPtP13WwHU3ZIcc3sFbiQDq+XfWYkAt5qHfQAb6PkwaJgWUG/sMQ61+tQmZPexqE1TY2JB7le4p2HyZTE1/BXyGF8yNBPzCybLKPtOPn8KgLojERpLfw1Ji/NjrVpATFO5TFxNW59fgNij/iKCjvFr/wR2d158qn2NYLyj5RRC1909v7UJNwdtNFt51n6JNyhiaXcD7ELM/+u/r6CeNLiR80UmHrRpNG6IkJViQcWIpVD9u85SXtmriV8lKqNDo08AbygefxddGPzHR2Xf8tbo6Zhnn54YHTCtgpKsVQ6nEv6Ufh/KMm/YDYi/o8e5czIOPYKF418kLf6QrpQ9Z26yVfX6FhKgCUrB3X/sdy4Z70kteUQCuKXiPRe0WZPr8tocmy/27lddk87hUfBy7g2fyhGe/sjmADiffOh9eE1sGDe2Rp3rBvnOUEp8W0Zk4SgeHJfg6FZXYD6+euSUBhB1E396UmOewpgEymf62bkhmL/DRX5+rt7iSDT0nwxrRSo7w/MKCc3He3lxF3SjWzVrAWJo5RiZS7s8t7xYPqWXMDrsD/CBmmXHgroArmSzLnGA8Tj2lS2DLsl3XwarRa1gQYZFg7Z+mzM85Zrdryg5743zCPh83jnVQ54o5BbLJg4dqVHrWJc2lgfFxhXvt2Z2wYPH1+ja5yX0udBfUSj66bNyciZpfl50eJ34troZvEitlzS/TPD2LvtjjwtMzJn72bNbgN1fVEpYZDbxMSxlPfOaMKe0jBD+oU83aqkydfHSgzA2zgUOIri9o51Iq0ZKW90p06JArlSM3PVkkHGnCqr8rjfaGY9r6CYyuw0wy1UqRMSukzQ3TFJkIIyoJzsQaiA0IJXibaf5+fLjF1uoVtgw01fk3fDCBfWzMCJ0BdzTnbn56NIDctTbgKnU8aEuFCt4T9f1GBuOeg+dmuYynMoAMX38iWW54Ri0s9x10dX8KdLR0ikZpGnUmu3lkcFJGjcWb0V+uSg5EsLoEZKi3FAmjlo3aI47AKx7BW6a1aPrK5InRHKJir+4poCiCC+lIN1IdOnJ6uTJ2JAUJqmBUTGkzIiaZug3UOnMRB8iZqVR0MZ4TO08BPUaM+otBOKbBT5NGP8d5zse4EvcWF8tcq72VFYIj4hO/AweNNT1VfAmzXz3JwABofLY8aFcJQJl9TzPq531OJD/OhUKb97txGV8CGJi2CaSqLUVPnRDTUIbfCLbUDzSjo4Mt/qebuEcuXSpuei2OwvPGwUbFekn/mEdzgFcyXIvzSx2kPMnDlsdAtaB7ShrK9oIwsBPka9HkWLjSr/Svq80Lr8Mr3n6+wlWl7V3axXtnLrjgEtP52KfL4kYKkdkCgCJu7HhqvT3F//9PRSv2L0FAcz2WbS/RD4gBumji6PtCEVyQYD3YvzR9mWsxcBOCX5HKdus3W2WHear975Xa6wPBuqxqFcuW+CBa6f7XHhfR1RsQz4bYuaDrdRcCE4eyFO4YRdWWzT0KT8wogjKe277fuWOg17nMffLNIMJ4SA/yiKvcMy6L/SdJ2TjgEGMC/4g6pOnG6Jj4ZOhXdcOkZYHyMaBagXzrWSqXyhpXhtT6ySOTyOZpTx9neR5JXGxfSMjWOJtMwZUdG6Hmr1XdkEwpW4XNjiLLG0R5c/EHv3PTiuqZ8/1VHu/aKRvPPFkGmLAw3+VLVf7EYbUiHe3PGNy0U59lXw9LHv1SR1krn5m7bMnybV7bIB93ku5i4EgOr3j4cNadOe9MyFLmw7M9O2PtWxtQr/OK7oM911Vbul57NW8j4zX6cQC3J3/AcFqLkFz9i/3R1o7+uHeua1T8+n8I63mv1wEwS7RkMlnnB/vek5f1GzNuu5FqCUK681RwGHQjz2vdic86iGHjXjvb7/gAJIKMM0BV8zKvOxxFEqO7DlWvmiSSi+u3IaDK/98WcvI5E0cTwIxjaw+Laq6K8A/OTH5FLQiY7Y9tSROOuJWyzb8rTZzfuE5Y69CFMNMbRMZnilfTLY98y+KBbi4BIBDh4Iwv4R37KCQ5kQsHxqDw4ig620eaVmd66To5PMS00QlIrAboiwx1+HD4mkF1pYSp4x8N66ya0XpGGQ5y2TWdFa8EGUvsFNQoWsRK0W/gWNOro7s9TuUj9CN4Ub1q6xEgatFXOBNkE7UxCmPqSNSMpRD+NrS3ExCn1tixEXa6wL7w0NM7u+Mqg7/8hvqMEIxZpm1K45g1KwHkvsxBv4ezCmNwsa+WgljnIPRaEjIsBnyG3Q+3VSso0PGVVAzE73Ecp2H2PD/cXaa1UEhOyBU/3EdWD85u5/45C0YJWQjHxVJTbC1hWy/CeTjhXGMNq3a9yZIlhbyukiZNroN9pTqbmORhTtYI7ytK1+t0qK5Q2m1gzOEFCwgj+kwq62fB3njq4Rj+ejB5ulJt8Q9kKe9g7AkwsNuZ3a1nKpZUSsYOl/7EQwaU/5GkS2NhxwPtc5SmOh4DwKM5Fd3z4tkgu/3nPyJwHM9vA6dPMARpvMTtFQkFnq7S/O/Eg15YVPXvxkOeAMXWrQjgI16WxJXlhdaOr4J8m9tsVbD5gTbfsF1hCdhTYVcgGeLIOhGCKVKIyVyvUofrZ+38LwyObYSEiE5GTHtHVeLlmxJjYvc5YFX6HzC7GKfahGJYwrM0yStd6nn72I3BVzSRby60vcphh131/Sw+z5+QBptwmuiknRHiyTfW753M5GljrQXrGXsocNRblrDupHsVNl/mKhMggG3UdeBQYFZAR0TVkB6JmLfW+7a9L94QVfEl25v2rNJgdH94Noz3HplhqwOlqwB0mTzBImH/TSyWNvJEnl0korDTBrdEiL8SWEWEjPmYw5MfS3JNeIPRhjyfx0q9BDoHKRSqqhyFZ3eVjTk7PcT0fned2rs5Sga2n8CzC5mn5h8HuZ6gfANInhFjoyh3f7Xvaoqr/5jTgUqb8Lf9+w2DNDlU+/R8RIKClSQrAoongCj/kFFdZWN4FCIG56ePeTkCoejx4O5V4Ec0GYOya1prwyauqKxEW3umjBJRJfk/onXO4rtSCm/yjPAe6Q1diBpLp7Yzo5VOO/gPposN3sb2tAMZu/0CvKQqi1AxAc1CK5MMcwYmdiv6c73UvZrmnkHMN1R2YtWQUFhGKa8BATe3teHqFxmzT9ZWO+3aHY0ry0/ouENTbL1nYjPT/xUtK6NoLIySCWBIbYEzaZihhivdhhxpXKNNthbu5LajqjL59LqxQUcQmzSFjDPvHpmu0qDXDxghNdlhGsabwSwgSxVTV12syA9oOJ87+0nw0dl1fs6A1mkihU4FqNvMG/dVvdjkI4TOrirrS3cMV5cVRsvi1mJwjEPy2rQloCh+3vN7xqVznSgRqkChfH6zCqhzVA9VjwZxm7dlo9KI6nhqFkSt4Fspp4psnTDtOEdwyGube03stewsNWNPkTvKoqapM0rfwh/K93sGJDuGHPWMQoSVK6nhfqOxRCCEfdT1Ea1hIgYdOObetXdtjuCQBjUtS8pC0TkBjLgylBfHiurmd2ilJDXYy1G87l05aVgJTlNwjWZh1R/dxldwqLe2N8PJm1o3Z2OXxCYSz+bqzy961DuuPeKFSg1hyETVUvQify3bOpqgCA6o68fuWRad6qaaZVEQHuSvhLX9zy8EYbb1ECbUlhgvb97xV8QSJqMQw8qTkPfKlPom4CvKBnEGuZj0v4AizBC4rtuOsOIWJAZF4IBXSAsODpSbBMzdGfaXx2KNV2zwp34vDjwIraD8iLDxR1jQ84hRS/7X9jSc4/vQTVyKGkxSKW7+69IJZVVqy9A5TJktu3c3+5pzuh/+EMkJBB1VK4NTZ5bffKEA54z5yD80aAUtN6c7P2zzJAhaCjh5EowiB/z33g0cwXsxEpltE3Dd5fTO2TqeQvErbVFyXRa26PL4zFbMMGBf955CTfWng20kM6e0oVMIXDec1WhtSE0qymLTWl9o6i2Q3ihJwUOKBSYvLW8x6mLeb2RIqGzHAc8ctw85cG0BO72Twn/WL0uD3NGE1gI1gyszzTTxCKPbrNV0ofzQVNoko/MMMdEDWwbiZUAn7BdDT/Wc8YegVkGXMlyKLDQ9THkyNloQhh7A1Sd5D3Smw4S0WLsQWHdxe0m5CnGkgDokkGt0mXHxP1uZKShKQ1qR/MpZIL7IFPMPaSiKPGwalzF3aMOeJ1+c2Mda+jOhXwXopstEzm5mDd5UNgjrLxEMu3/gfvnhHEC3ABTsmaL/cg2fJ1SLy3LeAq/3CQi3YsVhiLXnpKgtDezvxkoYLiyA/9E6QLD1r2umvfCNd2yLoTDR98Gj9ucBpyRXvgtq5VJAm2xXdSGYkNbNcps9jKbhXvQxZ8pkd8Lv2tzhnVoRhZc09Y5krQpTIB+RsVuJlwan5Mn7jPLJh+R2BRy62URc/Bu04X5d6P8EFXCl95BDjpWxQcrNnas9u9U90cpwZVi+vvn5+2PThgbvqEbGZFBTa75mhMK25jL0YFBPzJ/rX2hqaJiQEu9EIszMs/qTyPY+u2V/g7jNbAlLai77gm2Dx4WAvA1b4Iu73AO29M4oSbTx/PrCucbn6RcPdmaq2isxpzQoHOKBR0mHhxrKUVpPTiDlwYL9C0UJzcnnHq8r4GEtRoYEJDEzc7FlNdzIn+aNpB8QuRJX2/H9sTQbf5MBS7mb33jhqv/uppKkMGX9eKMxNNtrhkjueW+j209ibIH1EOUW8kDtniuO9n1UV3FnBsW8DR2a6hT6qgWhoJ3rg2pfF3VgcsCwhjpoMWv9FbpbBK+2ODNb6D9IPgR4c27YTQftEx2rq7f8Tm4Za/bzvna0pOoR+yUCdB7eh4At2h1xs0QL34ZgThiAmLhzFH9qwqfQehlZP0GGpXbEzxq307AzzBbIsK27I6Y2DgFYVC6nkesr2eLbDyN+YYwOXOi1xGTTMEsuzHJJNK0SIGbxWb3tLxKmk8m45FwulZGXtsoZCscKBRzNn+qvfmg7rdnO6tNAIDMFaREkciPq1NICiJrNHHlTL1DdQFSeIAvy4MXlkKypsfA0kuUabqhSpZaahS1Z0ThyZB37OblkXZOK+3YPM4ADNlWBpZwIZxlW7wvuAfujonyNpJWI/9TtSGIXOeDHFywbyhDTcIlunS/d9AA5YivAE2nTeWlX3JlbaLN3H0B8bEpqNS+UFpBno4G0D0yL/XSNfSme+7RqwDfzalaVbPlmDYO31xQV4S0F6Ie7SUs+j2fZm6zPzHczx7r+7BGlkXcnqqBPOl+arNfLyDZkom37DfpdoD3nzuIGKJntDysuAAp+gb/mEoHp/3CE2kRkaGRZKdrzreEIAgKBKOP+OWSDY9m3jaZla6+u/dvJnuRedzC67jRCs/9KthcfI8Rq/OqY7pkg6paU3Jht+wjtEfhDJ9ft6eZBu8taiFipJbhmDA+ccyJ1FllAaivNV2UBHZdEe1peTthrQlJAuCoK2qOz5xZ3Qvx/lHlaWiL6SJT/b+r39p8LMALngK7Ma1/3twuz9kC06+PdOnbjsnxV2/52BkmwQCIOHet3Tvj+DwFZEnCMsp148xp1vANmydAUxOCxn0f3GJ0chJu7BoY5twsSNJYS4wyPEg4H+rWfBv1rhQwDI6k6bc2uzoSgrbq3mSWdIJFq5JUXvKugmS1V287yaHncccKtWjxnPfBaHmAJX9HCmBNGCiAnMfqI29LZvYJ8Nx2bL4oDwP/QN3xk7RKHkxpbKiskLk5aVZzo0ST5e6cyuLlkFSm/wnp5dkd84Oqcw7bl35OOCntbJGRCGJrkmao1MsH1Umw+9j8Q2kFW0qlJCCPbPbd6yY0EWFLNTZYhaaOGOQ8O11bVIUdn4RqRNhQNGboOeeT8fWdyg6nhGDlAWUeRAkQ8LHISwRsjIx1yzeblg7YWtMIfyTBc32Aw+XqfQjKMwy1japh7eRohT8nxNzHlp9GHEvxl51+6SIaeijvVIPf0p5buaCmn8uIudbwfUcBMAGiRROPAp8s5IlXqcG+fVkz8xm5zxmuulX9mA3Y6CloYwxCFgccfg0R5Mv3kApM4Z49/+tpxzFU6dkjsTyQMqIOv80HjV8ggjDpulfSfpVfqTF75XZWMx5OtfgFtCyuCOG2TYTB1WsRI0U+6V255AVdP6p/yiKhjKPXzPqe5wFbX6EsZNOx8SwzSi6arJuDPr7M8woYQD4o3e629124R+7+17yxOm+7zNADApGt3+x8SQZfOEa0Gdzdl3r5BAt7UQ3mCZ7Pk0MaDMs+p00VKQUE5PBy1IH461oZACWkoEM/QOvMPG6yusXA8w5hoqRBgFnm/0sYQWq4RyAEbe9/tvaFEs44irpaPwpshYCJ95PLY92pcMX4P1Zy0ZY2e97sUD3BTZEgq4KCIUsMyHnOVsx+i80Su8t2my8BFU+09WycuSh3zoHzJlqm0kt73AXmlditBYbbQSZ+U82ALD6VqQL9rrIRQgRSZmJU+3bby8bK11SMIW+IOPaklisTBLOMdJv8Hw6lTiFNpiNcZ27SjWcE9sRADcvDi1WzzPspO/OSUeRCntAKRRkIiFmhT7hLiUz6e403SQOk/kHsu58KTtD8cZggAq0McwnNZPtE+BB+ZTPLe/sJ43kHJr/AErXFd3hfFSt600DCqJZR+24TkjVueFMV1rDVQ/BsnmmNvQQ4FzMT8Blj7juFF7Q4lBgCLX1OA/e7Etr+DERASwrehRmAC8yinZVz2VhbsgM2LPPXeO0Jvb4zoWSpTUNB2qU193xcEQYl3F3Lqu2tDRseTZMLU+ct/095cqHPW2ugPhepNzzMcTZ9oWGao4cP4smiePKb0Igos7KVXX5VuPUqss7pva3RpCKeP39QYCoQHOEgxtBHVLlEuHBVEwaKr1mGtsw257HqPE4TArISh7wFYKlBdcdVv/i/WmiKvkm+EEXa1mS4OraWX44mlbXovvVmhYCm6PQUYdWu/EPyXJHOnwkF9tiW++693nZs6DdxzFsZN9Cb6BKr5XQi2XeWQVaxaUFo5hOZ/8I9czDxfS69rb0FiEHbOOdAv2wvyGVXP9I70ljfOLxDvf37nnZ3ggm/wmlIsQbyqoB1IkR/L30jqSmqWbQLaMwlWs7SX3/n1c7BQjxsuTD8+dDMvXzf9Q11lFo2FAlFlUiuAnpv76Y/R8GqsDY4iXGpZ0AmXUgU3zgVRlT5hnJmzDm4eiCBXACFF0z1YSLT6GK9JK1yQlQilQiWCQ9BbyJkpARk/6ApoiB4/1jn1nUubt0hTKEf0qRJZXkBtSYKS7QpA+AgjO3N5HfCDeMGyIFu/xgbSbr/QtZBhOj4E0MzW+ly2xHOlmX5k5CzEO8oMIj8drbhUPj04vRlEwRRsZa0vuhVNrQal/2sW+bD5Iu7whMWzhK5krJweu3aMqE5ItMVPai+g0sM+wHuFp8VOjW7eA9JB09QanF36IpoDDFn9kR55Yfm0MgSuTtL7J9Clb9AiEU4sgyVWWNoRRnf0hOOF139yev6BZbKSg2k4K5jdpuA4ZPh+HTdnX3DioDf0y/FiafY6jgjl/pIIhtLxbiWkxYqOpriBSHyttWafsAfgbPcjIakDmw6+UDIaD/jDWvRadnJUOK/rFytMgEvk3AGjegxSjvPym+HKlYFbPkhceBTcnSuv9HQ7WG8Z4AmcGzu05yi8CCMh1Q7G1jv9ydlV659A+Nj60GMyW9IhT9m715kWnjpwtTzr03D8Kj4bazY7LV/Mkb3hgE8Q1DZ62hQhsSZU4s3tnm3AVorP+inNpwrVmUWfJtrhlkMigR+ul1VAZzCCBRRiURJ5ojak7q7bBswJ4DaXwtU0XEPnGfp/wZBHKNOCXswRPqNfeoUF8wcOnZdgCOUrQd0hCK7LJgBSJ6KQdebIZo7YfsOSRYqcFk1fWriRaleuXp5Lvbn+mFNvb7xQhM8v7mTZfndMNzOhnbWzRCWcwld2eubw1qCQtcFSyiiuYD83V7cSwfQV54LDBMSBCPr3c45wFH0cW7jzQsMCc2iBFTDn71iM7SHnYUsnVzNTpu2t6U9nMBr6JlsgjNmgJZSkSklpIMBJeiI91ey8dANDlUQYq9nJrvV37kHgRuPeQfAeDKKRVuOHwEbcJpvp/7KJUhEZV6q6vvgoh38m48C8HQXgNs+gupPpUfCL0rcE/7DhvFBz5lWaPS92QHG1+Boe0Wk132qDoHzRDcxPRo2rGmomIXRUwBZSxEw6rb+jC0srBQSKE4+XteUY7IHfZjDoP6Eyjmm9FwQfsNESoUdqD3a0knlrowDFzkHmyYJKE1xyYN9EzjZ41X3McNicCnwsn/6ruuh8zAnap8iQ1xEFksIch+prpEkDMY4xGZXHg/uAHb5w93KsjAhA2BOV4VNlL/qBBQ6I4d+GfR7suJkVLiuqGTDtlV8nJfqlZWGrsHj9uKe+5W1oK7kf5/ehAxAgy6wGo0aX4ul/lCf9RtgbR48m5SAoiJgeQvxH6mnHZBJDQa6RCELKqZOWAplsofGSU9cBpSmR3o5hS7vUeD1SVfafGNmWUnT+cPspOAtQawDxBQ0vGz3ToCNbdDMfNdKEatrQhZWyBdbjyCYgfG5VAZS5zK+0aZIfjdAhD9pYbJKjh4TNv3pCTSn8NgZkgULca/DNiZSIAK9C78ocK9+wwTQlLeb7dDTdUk+JwN+3WLIogpcN3opOgCeJOEg/QLiaUJEFsOcOeEpFgEuTJvfbDWnGHWzG9VL9v8v1BKDQDfYnoJKSGwB6tSfIQ7IfBdIAsKxELHM/gcwpSDTlmmBBwH4Vd8Z8D4WbS9XlBikAzHWyH3ft1xrnu61DA++imW0rwu8eaTvFxAUX81MP6CwEYs5C16G5pof1iE6htxU9QcCIsS3r3CpPGAR5nV3dxmuhWRhfX0uP8zjaHje6PdR5iT6ZEVmF5ftxJWQc5v0Mwrf9sZpNoSfl8o+v0CNITsh/c3X1eYg3hOI6raoSfyLUduzhjIMEjEsqF3cauSW7T3xZdNA1IM0qD3gQ638bBJ2QHtoYmtrtV1s7HVV1tLuswCOo0Rdj2tHa/awmitIFUmmZ+Vtjfj08HchjXZ5QYIAbsDsaqEbBH7L1asfZ3FpSkbaNlRQK+fOAzqjMs+VuPE5zdtZgPzORomsvmJEhAJqru5QqP2QMl3o3CvXNM5Rrc0oDysmoc7oxCMunrZo6HpkMtC5rY8Xxa/F4lbQ0EUzijXHZkNsBsJ3ciR0km1BkUWahlnl1PKRTEWNNK2yPcoOFro3FR9WPDHRfZY2gmx2haOmDe1e1MY3K7hciZwX/H34c8xXe8SKuRTsH80Wf5Z7OzDQ4K8VPpC7aqT2Jh0YehjvvYz6dGrlvWFP3YTjDS5jmdBxQUbz+RnCM8Eri58r8D1HfAomQc2ihdXUHySESE3wmLhRw0I72+xfBIW+OjerafaxAodzqy9/2LC+hp2Uydqj0CM7692aPPUoB6f4Rg3id4ZBXwmxE7VS0k4pR6jWIniri/lo4umuDW0evi9fDiq+YuwHBGHq8dI9yBA8xmHeBSEFGnKt6p8CpuMlFp/YwZcapbPt1JHsssWhs5N6QRPqLRhGFEO0CqSPrFDpmKTSisDrxHoGNvhRInrfos7uSIKn+cxZam5qRjvvUiER7Uv6RwZMG805dWEwlEkl7HQK7ZsBIpkngPHPyWR8v3tJHwea8Ifzhd6+JABJIUkHbiXHIpQDEIQ0uNtdzwAE5CzuDU7OoWAnMYe9SkziVDj4Qbf7OxBPRc+rYy++s2h8AxUKIVwygwA0MaqqSkVIxCWqZ4m/gaM87jshgoJJUrcBry0hOFEta2QGYsmEjAvM0iri4aJHcFrdz1LQUYFyKlNwPVd+bGUF59XVLaXHkGGdMyugjulbtkmqHMvfWnxMHkTWTE0mQBLg1abmzlPnoKsoL9wyk1lFStlCrYY6Xd8YbUwerZafddfMkV8Uzd4Cj6M7p/9yPuXIpP5Sgps5yLL0qrOyVP2Kiv2aLEbuVAlgeF5wWsVlu6yBIbkrXruDR+BXTflM8GeeId52CvtpzfDFj3CemSLmvisQk4V8CvcIqSU3XHizPB1qKO0tbhVB0A6e95RS/gMDWK9EkBltARVDfY2CtCXTfaaA1jaElDehYePT5PSYGcWixcVYsqYQwlzYBhUNTYt9G1DSkhTnrxU/+3psXv7EPg9r18gI8YrYZJmya3KboC7CzV7wfR9jspqsYyfKik6zoOKzWNqwmW7RPvXA0JOumiAoeW1HSVSgdnCjid+oERBHl7HHgzb2K6Q1YR1GwBfI+UKybusrsC9CiKmvm1c1slSeL4WVIRciuBbeIryENH8aRWZ5GQcaAoQwi7pn00hO2Jx1AVsumsYJuBRDNYh4GZRMiPyIaOmzNp0rpTgj38oB7k+J1ZBne9mZGHYw+YcIeUbrxkMFvhzCDOmCgpYQlk78wWL+T5xLYyMtReitvSEBjncFQH5MhrXsCVH7d7kK8Kw+lHSIMcQYmlaiZe+DAtZgNCVEaLrz0U72f4rOcoJ2+r+ttRcCVucyvWzWHvbOpPXKPLnVIr+QKsYLOozKH8KyJN8ibrrAYkjzzPGH0kEVOh8PdHa1CLH7NiAWT/b8JJsDNtwlT/ZvjXjLnf8jFDYWBes47d83L3TtroEVOFvdnu+4GscnaxwYhSEqrOa60NsakMfBJFmO9pquEICmu4/EfYmpj1QNmkQXV4Lir+yX4SfnK6qfu3C7zKSrq8IapPT3OoytkkvsWH4xy+0OQQLh9FnVuKQEDMQ+fhpjEc8BefaPyvUF11yq7kU+pyj0aTgOJt+8hCEqhq7PZXkG9QDtQS7ssA9newyFvZXuELcwbZzPLVWSOuSpo1aHvrR9/S9F8Frlbuws9Mmeu/hSlPUunlw5vIweOzt3BBDUHkc22Kyb3NeZneIRElsM0LeEMkqFkVaaPs4JOO2TgMv0Ht1kK+QOBx3irCtL0627Vin7uaPInH7Q5vozMPEWTbtiWWpqe6hEDosuKqgbOVm4EArzowi7qAtPHIiXPNBWSTo9WF4y7VcAtuKK119GM0k0cdSdDSE0DwFdlm2J0U1PM2A1DXu3N5pp6mJD6H8CrtpUvCaIMeiwGR87Eglidhub4OIWxv58571szRS1bujjWofRSGgvr1oH/pkI4xdyD2PigcleWlYEbqXc7ci+9l7WT9OZONPoWiQeqBTIJUBpcj7eYR9xLyZ3Cb/DEi9kh/AO347N62zBA9NBliivzXBFQtZCw+MlE/q4MP8UPd8Ll/Zn2fptIAObpcP1Rx9PNwCqT95Y+vUaQHAue+6l5SstG0KNqUUm8xt2fYj4Hxj0a4oqY4rvUQ7mcKSmRBAkyNSknYlgqDBhBHpZx/4nIdUiHdh0xsS7CyPpqq1L7pybCvdnla3u7In83fOItZ68JQaDeN0rdAZbxMe4ymuGzlrtX/ccZ1FsHXTe1g8AjGgWaMtaL6JFvF9Gr28k6Ac659/mVGh45EbNPrpC0K1jJik4ea+ypM67yPsfHFP7GQYVXQLiEKqzZWQIaeSXrmcdsLDqA6DzxhLOS9YNPhtJ/vvJ28hcgGSndeLvVUGeKilPkVE8U4A+TU49ZdzGJtLqrE95kuLk5MEaisTo4eQu40H9Yw4qyk5hj91QBJOn9vxlhsNMOV251AlWSRaFljEKb7AfdZyWP870soBRhc9MRuoftI/5XfJBsrvKRQahvbs89de5s2cnuNVU74YsJ2NSQBdy84vcWnreoPGKkpDpXFzs7HXgxgCwqahs0xwFsfhSYfDS6dmPGY7dYW0pQeiLeOEow+V8Pr19WoUn0V0Ka2VBm5qkKaveGD53vfP8WbYI3X8GYdqdVQJg+/5gKYQaK9rMbvr7QTw648a1sdaPCh2uzWpJcLtFQ0nvhZs6XgoGDK4IxUbBKwSyhSmfzQVmk9Ed+w7Hpl96Ddv+/uAHvmNeJK499mbFeDc7MWdR+cx7npw5rTtVi5E9iLPN/o9Q3P9dI7fFsfSEfdnfaMt2+qx11zURFVESYzPGRQL8bp/NE+CUj9piPjM5mW4Y5HR1VPl1hoP+6qnNAu6FudgFTjxHUaxfCX0lBER3fb/+fBGubOcAbaXuOSigiCl+RiNPCPksp3DVKMe2Ik79dkvGxeh4zMM59AlFn/fXL+9z0s00MQbRE7snwhLrg67ZAp2MwA/XUvftAfm1mI2EhqFcI5R5Y6KrjmaL2WifjnRyZt5rn+Q6iVarKJJnEmFFW6LdQcDZcNPDdcX1yJz7fDTT2o37HTvMYmYnpOazwzH5Wx1gMcmDWzLZrvXLDvz2uS8nhoYnALCrqwhTsjQg0XsbG6TY4WvC+T2yCv7LSDUW5PE1Ditl47SbW7N8DVC54zmbU51Pgj+DGmAJsbctAvDq4dm9DRDkLvouNO/oX/7MSR2VA43Eg/ibl7pG6H8XX6DbfKM9aE9e/7UtgGSzsUbu0H9NOtUWg/OceZKcwApedzLBiNyZCutVCXF/J0MfTTax291FZy06sRVYmbW4rgPHGUURftjwm/d7nk2XoxYq1b5FsgmNPKuKqNtNwo6i3dD2jFdbuIwXdv2CqA0mVOfLlpaCreKHcJUPN6d59P+1eEuVMq8O8MzglMM9aB43h40713oAHME1/3pu/7ToZX3+vgvGK5+a2+JgW956PcGD0KF8Jjts9DOlLwalsHK5A/sT95rnNPBysw1THNYnW32rQjiomv6lBYtCDGRuQ++Hzab7ZpQMy+t4egLvXnzsx+lRi4QyMfzkpWDh0Ox7V3R4Z4NoXmPv9lgbqG2UmKR3yzzqwtyQniKCeCn0UbkaSN+W2nce1qqlZYtVj3233w6jRr8Mvb5R5D5vZqEsIq2VfqRCCX0IHHbI+KK/gtVTBmXkz1H+NW+vG050IL0Sn8u/PTgTT7X25XApiLUBvD8fwTe/hQlYgl2BasbHIPZqTlh1SglR95oFo4TqL0lLg+zIxCQCozpgw6NQAzS5FgnBpS2VONYNvmp2WdmhvHJJM28kbg+ZZA1md3fyuEvdjtWpqOh3nHjjSh1/QlpYOpmDYhNCSUNjPrDe3hr1jAzDHEuy6oOq3n/HNXllSSKP8vEVEQszk6kX/KPze+fP9sN3FwoMIMJhjUuDImRgtqYZ6gZbyFiflkaWWdv1Mr3xjpUhcGR4NRFe7kJqrX4gOeveBBdchCKXJctrw7F0+sYh30kAWDO4kDOHJ5TStt9J0J15WFq9Q+pGC7LzrAL5X+Ez0ZdIutrHVaQB/l2PH5KCxSQchowHc3S8mlNnGgUO4R5IEOcnQX0/JA2bX1xMAgO80c9N3VEhMK1dXQ4mfII/1eAvm7eRxZqq/vQHgP2msz2+ayuk+fJxmjGUbywtPRTpj/SllDz6IMqOhyL/N3VgwZCZjm5HRZVtUKywPWEjb7sx0wT5LFpfUBFNL5Fywiext2wJmjzM027cdW9Xi9z9a+P6otwFcOHHh7N42BkJXP/0W8wzlW3Q3v16JtzbxbFMs6Bl06g98Cl0RD2AEIaxSSwuqT9/TcyfLyFsoysVyfaVYlnrsINObBAABSQ+7Q5dxEk7X4fLh22BFb/RDAYyi49GQh3Fe5rGMJ6uqDMmnOw+2fs1++cciIHLIQhVkVaNlpTyQHDz2F2w5AFg/pwi2mRy4LvsohAcoDcoSaoppF3O427Fv7wvvIQxbJ4j84zdKVnwExvyR3XpXM0v5YiXbBy8u/xkEdwh82u3zh28CNASmiKoR4vwaYahSVTN8obKETxXpY4BCuq1slyKkYcHSxC9BHQRMnovtV3IEsiDDlm6jkt/AGrQGRhJeU0rb+v+/Oj45lC0Osv5HyKODSK2r5HxCfZ9fk+l2u/eqVxEd2sGMQL/4b02pOgGq1yMTpmGjNd074JbpJwkcNa07Pgv0nX4Wn7RpVvsGuspY+kE9h6uqgcrAvHyoo9P7YE8FHJsMVdheX2oERUKaWZH2LPt89PxNNiIiC7Cb7pNs7UoMvXM/yUr7X3yEKAyLfy60kkXorrhgmk5lmpVXeLPP4pD6Gh8ocshRB/aLjZs8LWnIhvzyOPTqGhe8Qgwt8JgWFJEl74oifmY8xihkyh3/jcS/a+5KWzsmc49xIZVTWuTRTmxjDPEC0nKmRMkI/7xOQFfUQKt3oxdejlsArkslxpN06Q/Lm+oxOajljCuygFH4qP3eQdmeINyvvnKwIAyMYrce3XUM1CWfu42VBlJbaihDXFYuWjIpfhMvBaR7FJLqfylAcOrBMT0Ow7sjI5gemheU0zRjLgB6JUZ+bgcit6rjn5E0rQ+s3akc75rqw9ASSyJHV1q6v107cOZlpQM3lgaB2Bm1YonE6dUQLSuCsyRqh20H15nOxqg3dG5/jO/GJfgLf/8Wb/nI934XPRUMEOUvU1mHXffpDGGQ+W/dRgkFnULJ2jtno6qTGWi6zTMpWIZR5vVXx+BvKNJ8q7savgqWEyHzhQxYdUbcNWFQwGXqHvvuuowAIiU2vWsBEEHfUCQAE9+pTmeJMOFGzWfcwWw8JkkFioYlFgciw6ULmuEcRfLvGqpHTRPp3VILw9q0CqbwbujjH8DZJHkAEy8Yt+wQEXqgFAjO6YHoCPlqUUP1ASV8jsY4uOKaky2k1HvXebJ1kz0esG0S+7ynXql618xfPxd8vOoRmMHaAUKO9nFJoCIxyVhhwwO3SDTyR6X5/4/VfBGtnC0iXwAsswXzFw//HV1Ksnx27UjAI6UH3S2VcxPIfb+eM/1nX18Q3qkBro47I6m0E89oUA5DGOgDf2amXlfuaC5lsNIxj3UlfhUOaRKC8flGTeujhy4dtdQ+iMJRVN06QRPsdbY5hWABnwfT/V5n+yUUF0NCzxDaS72JrdtGjqiMdsQuObM8J0xn0dN0c4sl9M0kiTcMXhFfZUauhE5s8M22lMI4wIJKVcV1d4NhbvCd8BeokkmTBnwspA+PJmwpPzk/ZGNHz/ZjVrCovJgEniWsr+/rcL/64JPFhl1U5f1GeBJJl1QyS4L+nAdBYgvjJWw8EYxy0mB+UrrC8la7Bg3yHsU57Up/cVKQK5W7HyGlXoaRHeQnjB0EUzt5gq5Z+ug8VNpzPMpX1dQ6uGCdHdJmQfPdRXSuY6pVcyDPHFbMX8BlbGzDUT0pUV9Uqzq/2WxvnBN0v1ie4qkOp/FHZCgyFITAVJJSVh9IFxghQrYPH8zuXyrakUzRdokQg2/YXfEjqoIQZLLr12Rqb/6OctyKwMVclUJsaW5C7fU9/ofEyAukb+8PMhtiGBCexpVm+umbZGK3gc/vq3j9ZnOzQDh4UhMKMEgv371IVPNoP8a1Vd5HovGb+ZrpjlNrQ1eMZO7Gbw4ejzFTVLxV8s7L3dcFG6lTRTm8i7LjAZVzv+FWGpMkqq1Zm4EjJZl89D9/WqDV+vCYFYe+VfSwC2EcfNuiquazB2wCzNTKZa0NPYeJ/pBoYHGixGFHaXlfBjdd1gnAU9zsEuInxOjXWxEBDW6T0fd+7T4KJB3FUmKJe2/vSBuu7MqG/tr2lkqeoEi381+0Em1KC9qA8j5YSrLAa7zxbKVlwIvPMLtZwnx0d6xv6FRAG2+zIBlZYuuJOC6bZV2mkN66NquX85UMwzBa9wtZENZR9+nnFCZqsS+rKOArmOpDyXKE1rHCzoKixZJHB8a9m2IOLyYE7Dq5ifZA2l3csUdvSCknZgRNQmgHIWwG7ocpDze/V4tofO76CIL6gLeKHgd+k9GwiKkXwBsT+o/Fi5hyx3pfEU1WwnoZbbKttDzTPb5G5AWy4UGE7o7/uApWMagykQBjtviKUogpGK5sQNoVE8PGIqKDzBwVdfAMK3vIGEMQGEhA+V8JAeh5mCpAO9kE9ESaCGM6DkEa4Yg5rA/e9a9q4w1GLn927f82EPiNs8oGas9zrs3sixNAvrVE8LGobhA6gFJGoozbZJZq9x18AdfdZZDZX7XhncL5drFc+MvZlDNcE1GgztWJ+vR5DXcm1fZ0WtyZz34P4iMnvM/GpA1Dj2RTxlnhjFzodAdqFMxf7yxWWL+1HJQaZa7+xfQpVfxWh0cSxh/H8I9mGUf8EZ8w3oqr/rAZ6Pyi9OPksnFCu9HLmn36l0qDPkpzzAcqYu35rL+n+dln9EkTkzRBEWC9lkC2tAJ54J1B+iTYqyFkq8BWTk5GZcIkBNsACN2GLgQlWBcE0EnIXQT/O4/mwHiW3DkEAN0baWKCGUTEiJSv5pRcBBoRalS7g5jHE00qPE6ZLr8xht4TrRThNwWQzCzW3Knh4JeXiZaw57YQ3FI5Mg6BDKs1DvGpjFNBDqVKUUaivNG6c4xCB+9rpJ4omMs+7nbfn3Kb4Hb+W0HPWZXhcC/nYZukmEztwccFG/vOprJ/AoanP7x26MAFXMa0Jd5JZuLOyvEdytvI3s7tj3FPLgICi9ltDBDFsxDGayTIrtDLRCOkTF0R4SwPQ2IkA+gBICa4UbD91r0uYNAZznTzkA45s0z6A8qHvBLtyALtDmF5q4BVtAOdVqtHTckNR7bmU90pGvJPn37Bkh9KgdIvPwgwJH+FDnt5ijkB77Ohwng6bJGFe6q0MnPA9r8cJu5QmXfqTjPFXjXnxjLGf03scPs02TNbox/yxRW/UwrKLUtVS22Iqq4Lv/7A1mELEHqsQeurcIYoV7qubcMosXBlVXF4jws8USCT7fdqmdCEMVjeKgivx178vGoZtRrO/qV8xN/WPvWH8qtNUdrP8ngf3BE/AZmmXQrzn9TXxc7H5KGpESbaLl5TfbP7/Ns+edLYf53eoBvPgkpy1adjltobKqPYhFV4XTSxgpn4poDkwBgpRoDh5l8mBQ2tF4l/QzSWrZkwPLz3NtaHtJpasBzcMpXxUkPRmztzCfL0XzndsHfcKRoITFNY/FxZ6aYXgvZ/68XPogYYQszOi19fiG+dDnyYw+MynBk/qyI9arRI3SP4FrArD67aUlQdqRA8k5+s8V0343V/qYdCiAIJW8fi3uOrugFuEIE8ETwECtcuSpbTCdBWN+GWGN6R69l01rc/elAflBuNdveZwfEF/YQi6TeoI7rJ291JttMKAe58bawxKJ0kSVhPQ4Q3T49PlPgD9jTmUaX1V3omjVzYviBcO+bOUo8ATEHohWWpIkARDxmja39y/DRb7c2Yxs9xLFcYzPRPNYrZThQuHayAvf9hpcMxIDzUu3CCO3PG1D8PagCJapZ7Wumcj6WuodbLd4dDtQ7xDX1P/h+jdvtA6AOn92DIkIwDpSQ+UeihQ7ty+6UC4p9LyFD5p3AAABI+T3tMqy1ifUjjshvVQXFUWCZZTIUSKqtvT9nepUx8uJJh0vqAZIicdW7BG0o8qYGsomxo2AWIVRgBsQzA5Ddzl+QflznXhLoiNI3uxSGT/3HHT48pq3LXHGop9F13tfAwhYf/fCuWYH5YKhLiOagPVXopFSNTAjs2QqyywbD+oGPW6RMspZ1Hc+KnLy0DoglW2+tezj7IzwS3mCybPw/nIzq/am39boRDFQLvC9B0KcPOss7gUk9Xzb2BHUdmePPb5ZBxkzpWDL7QuxTzzNiQ1xlvcELvDkOBVF5kiCOiGKkET1DM0JyMdLxPG7BgPkk+g5Wt95w8wfmT0VdQfyfLQGZxh1O0dpQJ5aNtGq42OFqJUVZ/z726S8lmPfht7KnU3O3WbtlZpINhywZiPZg8907hvtVWOn0sYuTXmAJi7CjMOjohPDrNJ+4z9MVghIVduEFmvP6Jc8sL4JcNpFfVxqxEZeclB7yXxdpOa4ef4Pq08ZIz3eRXAVOaIHkbj/jaBVBYw6IFvzAKs1FWmBWAa5eSU7yqIRLF2skttaLaITZnORdxuTAiYuMIPfLojSXOCA6We/9p9nAlRb+1U+4JWV4oJ9alaltJeJIwXFcX2SU9t3Oz2p/CpjPnd1GohmvnGEGsQa0uQ1CNAu2AtlgQPfzYK78EMaM+RgcNUakETr790Oa6gUAlNsHBOSTSkHiRniK3xUgtZnkuBFLS2g9TJyxDrj8NxQp9ZekF4+VEefkhs2aKEZxwhRRZwFpKJEhOgV0qEacdRXVXMgUBdG0L2M+jb2uxJuv5GieWz0YUtKK+1fseS762lKvMxlbPvhT/blKL82CEoqdgLuUkj4SoDRPBKANYyR4HfhzN7P6dswBa9ja+yhm5yKZAP3Clw8zQFJ3PrmOBp0RfukKjQdPeRA0rRzpZjPGoHiRegVZ5FfjIELvoKHn4IWEvBmqoCyGJPMO/0swgZJfiwjFvJrxIoJfhuMeeY9E6mFEm4r4LltX8tPbaW3GjqA1IaCiIpWkL6QLDlq2zCZc4KwXG/Bc9xZ16pDecExGyTDzbHbZXOVcf8ZkCw+Iw7JoAN8xYx+q7rYQV80/tmHEzFyeBci0I5LC552WP0cRkYlS+GnUkraRbveH/r0VxIWj8EO5mNi6Nd4WyNDLlenTlNth4NTh3aS/4L5nrPv51B0a/otYXWFh3FOCFPEx5P/+X1fXSvrOTp8yv/hj0Ru1YbOtwlNcB/776M+e/thTsOlKl9An+h/Ia3WCBivKaXCnyi1l150PFCutJL07FGfq08n5gzBHQQX59e82gdaoMYgBfPk/KY4SsCsVc6RG8Vv3g8dSGJxvBVHKI6D9kDLz1yZ1fF2UQeC71nrwncTMY0jDJfGFqTUY6AAy9+9G5WO2l0g7yWrKRRrP+Xjc1jQ+ED1Vhz91U4O+gzbV4ctGREBA8ru7+1k5ALTNG2sH+9yEHymKqRENpEI642TGgzJpyb2/YP/LCja+xFnLWBjHIfHzbGyu5ybxzaVFQ1w/Vc9obl48YKfMOeCr2AJRdgXlbhg2GddsKsHeZoU8g5goa4RIwqgAwQ5LQIlbAUL8N6rzo+vYkrIFjty2wvKChf2BgD+EesMia7yc71q+7+iXMI6QmJxIvQWd9hRT2/nldK3wCEx3J9NWPyVIve/UnDzeLyC1XxrdJ0YFvLMl7KqrDggzBrXy1hBdeCQ5Zc5NJ0i1zdVY/Cvfkp1M9uGbOG74r9kTOuZgWeFoQ/ZKcJD4wKF/r6JOFxAckh4jy3uyon1tVoWHIFclkUdMNKV9f0Ri9yMuNdLEcH+VZn7RTASXqfx9rF9uWbym3zPb2w7o82l1WLct4u9kQ9DTZ5ts2yMPPlFi5QuaL1xj4I/H7RL+kCS6WqXhtZztVf3vEiwJTCyStQs8wQRW8GP2q+IqSHVsP3xJnPmRVvu1fZjJeN5mNJXVQKxOo9JguQn18ln6xF4XHd/CQpHd3ZKyMNAVjWKF/M8i+wT72VHdqxt+ISbMKKiw+dh2q5HfJzlY/MB5lA596cEWUJ9JcP/2z4WEUHTpCF0QF28XPN3kNQkYv+0o13n9lhKGPh4kZ2kGNg+2f7rWqeui5bpVYQBeDLs9B7FoSJR4bvyQ83ChI0502JvA28+cxR+HrslnKXm86asb8qbemG8/zkc8eKgxst11O4jkb/tuKFHfXCxaxnNNrOR7cr4XYUYWzqKbJdkRRl3o4DYjMJl6VB+xRSQ2E+Yk49p0cavEAidf7rxcH5znqUEWnTTycrCwGgJvdGpDIOegkObwYTGwWtp4W+rMKh47OPI8pVyHRJ7NlYTz2X7vfrsynMVO4tjN1339ZfjjMZ0cz9CKh9QN4jEh/JITmzLiaGjjZTalakgEUmofM/uMnyTmbrx9P6ddyODAzvG2esKwwNshuca1AWrQrP2nbHZ52F0cgpWy26OKmPf0ftgbIE4AaWiGAkagV2uKTMI8Pp3nNlEvUtd3u0qiK7N0I3YwyG0QdHZYD8dLkziZjwu+YQkXs/3AaLzuQ/1QfVkt8XoSiLpdArJk+VbaInnedXuFvYf40LIiQ7NWoyRl6Bti/Sz1x15MdcCUbDgZ2qrscSnZ+2eaOGY8S7tFzN8mgeLicGkAxAGJ5C5ZHx61tewZNVK9U9gWagdxQ1fXDuFSrmQsAvv3Y0vYaMLhj1sMhj0HiTsrCnASz/VncCOe0SBWFxCjQqxU0rniThQ4fsShdPMBa2fF9s5VP8ZhKB+Agc3laOEZQTnb+SBpC5pifDpK3SgH3um8GmPjgXDK0iHMxIR9nE5xyBtIhCrq2wQBc0WZ8GAzsJXVvVjrQSMy7pVQClqW0+n7m+OKZfSpmy6Nn/0peQK7UfzLI9qk84ydqlKQ+L6xf65J+vfNRWCYK/5X+bkJcojb3Id4mOZ2+ReQmhtvBgtId2H2Lc83ti/OXSs7qA9XNioWmVgxIZ68xwdogXcn+MuWPRNq9tdaNRy+T2t2ly4w05umlWqUtFGc8SAarQrBwrqBXuN6sYnA/EDi6+AUbbcQzeaFQL46YoDrbTFjg4nO85a+a297oQZ9yWQ/5uHGjOYbEhF4iInsf6oIoGXhFqmyYODluorpL+S52fK7tGN05USHagpxg5BRByiNhTBtPEbptrhEyI0Z+NkOBnpiy0FYGbFWWP3hD5nSfazXlT2GChw8haJx5lmIJ6HPI2JND7Zjq9FaTWueKA1HAF8NDUtjN5ZsIrFKKWVch5c22Y6by0wxuKqUterXKGg0DmmWLSPc9wFJAcDOHcBvIYeTJ42ccixawIsAZ2bZ3uRp+qx9s1L/1Hp2GQQRwnRb0vhVs+8ctwIvsyR3Fhe7QCnwyfu7NFvEmXpOyA7vmeUeaAdu4lhGSnqC0DT5slZTmuvgQh56aBkJRVyjg2Mc4z7y5hi05rZyuCneHH7Ehv8TOPOr9I/mzCoIRJ/j7+S8dcQZ+IkFx1ZIn58IYnYO0911fg6CPZ9WcsTbIgiGcym8MFOv3q3LhoHO9a8jcLSefBgbBIrmHqD2TjjuN2yyU2I+5gZ2DxtqWWRda7pI9cOtRicMCaZF8wOrT/xB1o8LOMaF/3w9Fyq1KKsmqEvBCcHVX05o3lnisW3AkBw5+4c+e4aCTf5pw+dWpedmbmQPBO/x8FiAx2Dn/JBneSMV5rI9iI2CWQmqXz8DFnnix2+0AYVQelX97aizvLzEHLldJYeCMrIm3Hni1t7otwBFLr+RqHuF4BST/D7Il3tZT0wNtu2srERxB0lYPq2PzQT8CmtaZkqh5c449G4wCUuS+CB0FWj7S4IiZnQ88hc584YCFbMXBvxqD+f6zF3eYdIp9/r0uK2DoteIPpUyesUT7jlnUdegR5r8fR0hye2YOCqA6Wr+6EwUgfBJbjMRH1oaaOgfbekSKWtBFBPR1QetRBB5TAaJSIIoZ0LhdQ2Ff9E9UmGDzt5BU3znnF1uQ2V2BuSB3nppA/50iwNnf+nTdUQjsKj6UunQCzwb+qfQeGJ/mmSGCSVmGpy4fqlsqGHWEWn8+Vv+ui1wn38SgZmUTicVDV9ulck8Xd/t/p1c6jHbjMAYmAelYvGTg8+ORFnCG/m3dtcVxL98qCrYfHgoN0wHJlZVSuYrSQUPguomFYR+kPjxR+wIfe+qBo5Hca/iy30E/sswQSifLvaWKZPv0wTkUu0Yi51FFQOSl6DBGXpH/QBxmV8JYADMvT8A57t4YtUJ2QUyJ1cqwrZpttbdHRNZ4JXHnBgt8HpUSloykIQ2tK0aPZd2jsGwuOSvs57KPt9sdMmX9xJJsCHsPHnFUUYxTl7RkDzL5/xGb/l91Fh4+DsNaUwNAl7C0uF/AVYKPtJxb2Lg84thAu9uhNsRm2WS+FIkDtBf+OwrmPNJCLaMyChstD7Tyf8iBrUkPgI3sEzfIW07tApamvwSt82455Iks3u3YDaq8iaMtICuxAd7SvF+0JMJZRGm2ZjQeLUsgQvec6iO758wcU0QCu1FxQjNc2PIwBU0DVbnfOBnPkABd552X2V+Ah1NzPIyuXRxyZTqnuMwM5rFjxZ+77jvFG0VPkZkwE4FRqCt7IQcYObLzCjNZ0SachdBOHH6HhLZ/1mhHKW3b7Vv9ApS+9ZgB5hmvh8qz9Kv+2RUmqPGEdqjTuy/lGtokpJbz/MUoIzFHBkDTxvuJ7DmhIhxCIb40U3jXbcobw3DwIqjK+eg5p4YLfmqtU7Z8ewhwmUAW8A583bQFoWw9iZybyqBAGLAwDQ86ngP1CU/R7qKDenN7b3H9Gp2LQ/A77m3x3fxk9KlkSAVT2M4sCOMcOX1spIEO+JH1mDDRalQx+AQ7ZySoEzJRRF1bmYJuXJnKqPJ8r7du1oPzJK4sik5Tn7cmlE8yKUeZgC2NYDsKg9YOwo9snTcZsDt+0PvwWzabF8XNg1BmPFS83Fbdt/HZt/Xqp0JT1ZBIJ+VnBjhw4BKsoIH4xZi8Yg6Rwr5jwM+FatcW024ihqSwlEQw3pgVkQw5gR1RLX5RZO9ciRzOEIIEd21vvRVrJ0Qa6x4UYfjFv9u36Yqo/fvHmeJShIXE4BTk2TNK6qPnET0fnnnE33XXzHtQTYZGqz7YAjIEoFZJuKgTdyv7CsT9wwa+OtMGIupjNOSUu3H2RgyLN3LArSP+11iZi8g+DgGJV+dMUgJnvvdKmisl6cQ6yv3fw2y4dhxHMrGZa7ovrJorKi/89MZvZ8PBcQmice15U3QQkcwVMBmfh6VTEbFOHY0osYSJZlfCq1H4uEju096UND6LLEqx3uk7BzNXTWVHlCAnpK30lkpXdFeVs5ckEe07EkKNnjWgdaOzgiQnYc3MaFZd4hHamaWHGh+4l4IEpajKz71fCRU3CIXF8VJ3LyB2w3jKaR8rSGVMAnPXAKnI9EMN47jtJ2LCM2RdabLHnfX0u0cXOvKuANDQVtsMDl1fzWIfb9zPR1GQJTrvfIeQYC8h6GVx3FFl5GZu3i2lSB8KzHx8JJ3jewhA/sCVmvQ1KVFxp2sd2eH4RxNiXTl6eSvMr0OndD2ROFYgBFu7yHRc3MhpqzsUZnpTjSdgce6Ga7XkdoswhCAOAe9SRmDK2PK/VEM2Ckpl/YlYb/JtRl02jxSW4d3SJKloKXO9oer9B7wH6zNvRivNeHXKK2m9jENFsr0W8jnB4rRSiNRlFRSVGSN+Ns4XCXmtdjGr5zKYA4DssxyusJGOMuF5E5qko4sJCxb2hYnwT+wZH2rUszFaCTRsxiYh6EvGS0Acwi0Sp1rNTRIySZDBIWPaIRwlDIXyiiphRv71V1dIBdWqkN3wEzVl2XDYzdt4PYQdJ6DHBs78eOBk3tWUFTYhsizxHyKafsVl+dhEFbcCMQF85JMX5rzDpx84G0ES1KHe96YJiHVP+F5t0ixFtES2mrRApI5qJVkMAOJLqTVpxQ8BC6qwsvasxsW8m4mcShPRWCnKbfFV8MSqs87cchMCXcxMbS3coK+KyxO96vfc8bJBnca95orOuF+vW+wN3+vqgtAkjVNUlYC4BnGL4/H6kFK7mXJCBnlpEDzTpl0M5vCxoRXvCIalPsF9MzUW0oJSzov9lfjIHG1TraEJKjTh9ATDWbAKYS0Dz3layeB3pWv7vOO3cI6mxSalU8mcTqAlVCfkYIBzbjfUIYH56eoK0mvyV3L0vEzvobHkc4LOqJYJ4CgEgrgIXFeys42GoxoKT4KfS0u5aiIXR4ghLGfbITm7U5ngAh8d/cRX1oFDmx/VHAaANOscDgHJOxgOGhiuhcCLBUkGAW1spQXYglvL6W3zEoP2U0iMqXypF2AglReUVDdpkPF1Pq0r42uWrstTAhnHmre0kM+AUAT0dX8czKcZ/nBkn3+5s9swe+OX5457SX58w5sDdhMe+eqdO9iUmiyRm6vRRZzVEz6t10enZRqoB46keGZW7B9/5y1xOVP61d56ZKWacyPng5LatyOYRF3i0Q1iW80L1FKqpumW9h+V0UqRJqh2EOMg3KYR8pkvRH5MV1yXeaPMqPeXHamSTsCF9msMAV0CZOR/zqZym8Zuk8V64oDb7SqPHgoS5hoH9PtNEI4rhYhfuwdwAYwhgfCLujJQ/7lNc7fBp5srAwKTNrIefxpwdwJ/SSlJneG6Al0ypLtnhgxzd/zjak5D8FHR9NqMWp8b/2LXwxNzCZ+0D2z0KuF/mJPeYW3e93QVRhhZzgvuGyp7ivr31Lk+pSh6WuSZXNx7+72GyeoWxVXdR2eANhJQdAgHxwBRpPtJzWmzR802pYmgX1OGV/04GjybpyXitAZarEQplWGgmOcv842N6xMHEKmo/hdPexD7F+b7QtaltLGwInlOFtPLU3GBw7fcBnZT29KFQfiO1HKeOz6xrNVpHmdnJjWHKZ4vAqzJTe9hI3m2RbFoH3fcOnWOcJR1+8A0nFpiFusYXU4Nq08xHV/w3QzV9J021NINpqO9JbSc1rF9yccGYP/Xj1tFrWKUGCOWnR8CZSIiLSWYfkvsTempG4SXajTziNRjXuwGGQ7oIaKfp+hw5wSu+qrOv16fJ9czKAnlkfMlgujvOzwUUuD0QJFZgMuOmM5BUPc578ZRWU7Sulm5OjBopLr6XAaluRvNvAdJTvXT3/SprqPt6mOx/NhlsxRR522V0zF05ING3PbtAhOyA9BS3S7KN4qJ4/HMgbX4SH1cad9+pxgK3qmizJFIfWE6MRohSX8t8/7PjcLyGFqpTckGAJExI4lDm2JLUdz9whbB0AyBAXPiDwpn6CxIm6Lh5bqdSBSfutPlX4tZUrymWoQttMXskrswYW0ZGH90D0egBiuymM1HO96fmXAKO3pHAT2QluvukRGeZWltoKgmiQPHOIG/W19SOrrgK4HDUaS59Qx3KIjA8c7x/nrqiT2TOaUCCusLateySEwXBu2BOVUwNvnnJqmS9U6KEJliEKq9RxIIxDWYGeEMSevlP3IMd49RWow6YtwBBFEuC0NNDXWecVPs+R5cJ1wceBrxud2TSVWqeeTDFxw3zymdrpIXSA+/qPdLeA1HkzB7X/YyYXObPzG+gvs+V0qcbGlwgzmxwjn5GF4gWfJWznCSBO7qYWry5SMsy1A29ot6URIZuxQo/GWQ4v0F5QFeb49usZjw7EUyBG06CHKQoXy2T09N1gtCDpzwu+c46t76EiM1khjublcyh2O919ieOsXqJd7Lbeis+HQcvj0ruH92DbVwWfrEuwu0zdW+dcCdH5D66Rx48iyGoL4UGUqg12YgAfuCKbzWNW3qwunOgZukrKlO0neJlTaNFGjbMuMZggb87bJ4NPPc/IH4lDq+6ROwV+wSI7Es8mzKpup1Sm8kGbZPmP+CYUOtgkne9VOh3MMs0mahxsh9FVIynpHz7Bu5OMH45BpNmPXvkKGDirXx/aMo+tVlrmvcKxAyOc0DMUJZP+AMsPo73xiQu+P0Ob/5paXz2wm23bLauvkQ0M+LqPV4MuLDwgxDfK28kTtBPpOAMJ7Se+JQiPQ0AfP9GJPsMmG2iFwk59btfNdiflcNiZbJ6mDIBJOrNLTgAfq/PFaK18uzIS0ad7Jnp5gpt42KZgXKc3xLAKG1JaX1LYa0Q4viAtCX5EwCpIy8szno3B110MDSe39Jj4CictI/8+Pb2hNXscf9EYtLFNvHR0OhB0sdsQ11C9iQUptSMYIymmb0+5NFJOnMNddXaIiSKuoU8xV4K4IPRBYyZmaThiwbRzhBNKUuLtIS2Qq1B6GW70YsRc9zAivLpD2lvgeTc8Wn0Mr20p7ECaf8M6HzkdaVhMOR48BgPUqBdwrrkZkg1TAebxCIyxzP+7L/wXCus6knJI0USH0aUaOo4NRypnImwRx3YLd6N8Az6PlSo/796X7oLoQQY3i/gJe4mrPC6+yt4c++m66zsyqhFd56JXEizKrZA9lU0UdlZ5jP10vFcRsr2JmB9O5lnFcDhiPgbDXRnkzPB/NhhlOElf7HYIRSY6yhdf/mBnUMc4GlJRxQBsFCPQPEjeFZm7SVYZnuv/PvukOOEDJCG4zuD9o52PoKeXHb7/Is/qRTeI3NHgOHmWhD2WuoZp08IirPH8ykWZnIFsrfEVJp1ca6D7CpJm9KIKLRwWetJ5ueGxa1UOFhVtlvZUEpcNtFPqHey1onFegi8UaB9Us+UDMSMGPrIQR2ootKj9xUFwTsdt6CD+A8S+iQkJhXC3fNlbgXmVMKDDBACNiY/I4PsoR9Qp/DFaf7thBQZKdKmxiiEpXCQ3zh6pZCswyNxOpool6cXF9BQjB0xByvvuLUcqt7JAtlgAkXwhfQqIAYCJJcMZ8DQo4FC6x7iN8+Le1YEpKxDO9FtRS7LfBUDMD5LA2aSMW5gDfiU0kmAyB8KMdG0r1H7pQbHBiuOA3StMGc3Yel3/FyayIoMZ54ngbtKwQ6un8M+8SoVn0JpaOG+g1xnBV9Fxj+d5oVkqzQavZ6bcLg+/7EC6hzUIvjMbzHotjzZXqLDDQzNTAAJUP8Zxx1oT3OoCjEIvwf6A//+pST1ZzNNiRP0K/clHzodh/HwWJ5feEo09Kul23vqGvFAnQJpZbDG/9VaZMS6qRl99/xyvs7fLNUmIR6K2hEOK1VpkTlqhFNzVnLi+R7E8mXOey9UV9aGLTBOCUr2wgFhVxvE5ChJsY4+jgiablbiwlV20X2hEe6HFdd7LaqIhYfCtW4V8MfmFdaCXWVVje+/mESHGr4CKeDVgsCWMDuZE7vzb1UgmDIWb+1XEM3hnwrQcG0EmirFj5UFE4Z1fJC8fhtrRVd9lkdMHtGZTZbVdzjrQjeofXgjLgNjqVTnevX/mpMYwd2iMalLZO6R82JX4iE7t6kpiatjddg7uibMCOlazPY8XdVUa686LDdussIoq/GfTLmrA5OBYn3GchLNiwt8Uf7zXQCylG5y2dsNQr0202Vp/zJElD1MsmX8ZmbEwLnXjqmzV5q8CQ9gk5nUNSQnImBJhbxuLvVyQo4rRK5pfmXuDggC2n0f9TFEODSMVv0iEvDHyLAwOtkuwN2C6BMvcAwCXRbhRbBF4LxhM4PXmzGU6IJMpjmSJbVI2VakoFv23kpkWqmEhPZSXIBt3hCOmUVQDV52+wiBhQAsQ4LTb/SXdZhNGriWugbdOLA+QU8PPapi2dqOZg84qZMPySb6AQvv5nNK9jRdGJlBUAMoL3pImxgJPj0B94kXSZUBdpUUk+ORzJnSc4voGfh4bzLf5qAv2fwK1+cL36J/n7LxWaFftLO7TYq3bMujA4yGdWUrEGLfb/0n6qCn66Erx4UpT4WujdoO3+9ZZG87+OuhMNqOyn3OkGzb8PNPD40Lht+XfSTeBUVBrnBJvFbapvSYGDdseP+cpDP+udNP5hXc6yzRmidTS2J2IU70Ck7TIx0mWUHSAYloURbMVPSraydq+CI1+iWK9rrjqcgJsGjd7Yw6HDm3qZ/wiY9PX9gJMggrXcoOhw82+KTP30ITekRkbktRmTo1Y7VKcuOX1t8MCxHqFHJO4OElTHQuaj1EMzfa0591hQ2Vuw2Wesg7XCHN6CFKZk4fikp3LSQCwR6LrbZfLJTg4VNL4cauVU14xGInXp4DYzYE0Uq1i2P+eBG0PlJ29Rj6YVapHJPlquTcQpbdYOaFopD+nGh9EJ0ZJ1B+e1FsMp8Zqq2nBecYsK5etHZ6ydjGColSbkBKNJbPS+gtZfVTGPRgaj7X+bSdsuGbZvm/BfS9aruLvBsgOB6/ay8qqBynlG5TGfwgjooVSzhoeGlNTPjrF7Mm/wqnHaQUNJvPBgDTv3E2QHUNqP8+HszK529XMWvp+AUnDlq6Ly1uzCdMUENWFI25Pmjclfg/OVA2PIbvI+8XqN1oty/suIQO+BGhlvAbFw++Dhac0u+YAd/qtLDaK/tFEYhY4Uyx1fUtco0qRFQ4jjhR7PrgT3ImLQKYakiF2qQhm9+5USw6BVcKjUl09AlZs0+g5FkEXxKrXg2dwCUNZaHccuwu9VnXoOW2Fm+NpGT6HAMYZfFZzH4/rjSXdcFfCMf6NqRMHv/nbr4RkhXd3A4u0Ab4xG1zLgHmqBZDywgMj4gFE7Rd+acQsgBIdg0xpBkbgj7YzHb+X92qttm+Y6d3hR/3/XV3lx9TbmeV4seNQU1yJKjtcY07GmVrDBQaih2hh2DfA57bvLwcyCFqGmJS96E+W5Mgq/z1tB6Got7q6In7cRwJtG6ZmCrDJgUVR6I+tKXY/5e13HhquMumbKA86pCV4G/5JGJ9OXMBG/IVBTyV6GDB7q053Eh2gv+zKCdXOJw/X6KmaBK6UVsfJmVu2xmgkTcYSdHCQIgbyML1CgADhELeRTFalLc0aLNr6ebzw8vb5dUZwOonkj2ROrZNgR3Y//kh7GMwRc6eWgv/lGOtFe9Aah+OhRJrJh/CawNri/ksr0sACz6WAmTzYnDCLZswNHvu2vzMBPD+bu86uMXrwOQ70GM7jAxuUTrqnkmjJgsaMxffl9RWZP4PxPZ0w222H8nXl4fL/QHAe6u/UdJR9Xa57i0Gv6ej4cUBocNoxYqNxXvcxxhLjQG/A2MHRWZ69NfvR/zKmTDJN85DFHMg227/uNHQ3g7Pxz3Th3taTm2QmbQbeFwux/c+RUwlTALKbHJtWuf3N5f1v8nP1rrMVHtmJb82gET2FyvCFFf/Vu0xlnibr54FE3mkgbRtmdtj+Hjl2ir9jJmy5rQStQC0YuA3Dq/ncHsFgfL/WIzO4/8GpKwMAW7RzABQcWsmGu6+ul54sKduZL/7zO7KqO+b8rN+eWobGFuhAhRiDfmEtAcXSYWajCcOcSl9YWmMP0BfdSMqjs7dBEUNyEQ/jOJVEe9M7AoC+3kBBJ7IYtz+AdaYddfhlSA8IHqLAt8mEentFfFJDpbeEmPxayFjt3isPW5pEjGLE/j5+6k7Iz54ryqvpNVKQPoco+lLgmHRKxWi9Lr3dpEEhbz0AUI8bNQSSa8sZMqAKWIqupXFmg+yzZJh+B6s1bGYldknYrKXsOcAhwaoWw+nlAvkoEPfhUPTCmIMGzZTO6uM9G0BQMdceR3YQHpFvD6UHUCP8/cb5agWjddbFqqsk4DYzSJvSw66FSZwQ0XOXlv533rLWMkifCLxwUsfIaHkPWYMgqLFTblhNLfpNtn87VzYK8/Mz7J6/WZHBFMymsuU0ZvCeGe4DIFoeL6+B9x0xvcnELDFT61S7+6n0NHkmvO/eXe1OajhasWv3RUZj1F5r9fq5ZRu5UNaOU2DsMhqRxPooMRzpqdNoQrbr9twFM6gfcR0aYr6BYxCihUphibpaYvw39MKR+K6ABenr2jLULd0Oxz8m2e95+PULUEcwfLKv3wYTkeVEke/oix8GB2QGKcjFtQgQi+UUIVYnO6SXSTvbk0dsvasiols0wHwJO/DA7IcHtRkE7fwcZCU22cMxCrHHZGZexVwRkHqgobbNwWm6XesCZNo8+4k0IjrQeuWEcee47FznhkT2dJuFh8d3qSXewgTRIB9U9QCNkvg3gwHLD2kWAeq08mPuIqlP4GJ13pWG4/rxoF2r95ManRr3g6cIasxKyAZ2g5vD2VFyjqHjz/7v9uoxv7QNF2NxaXKT0ijAl399uRrT3lzaXlJtEVZNSMEv/7jTEnBKOvsUzr0epskmLiR1/jqShkQxPBsCFQXQ1jxJsYYukIkcAnl2Qi8mDKmyiUCHDpHdUHvHUoMMIAR1OFsbG3PA2PYuXZukZUQ87HqAN0JdisLcysWpVMJFI4N5das2xwBY1NssGlUnMw2c/1AuRVZjQ4QevoIBRz6hRKm2SknqbnNO/mwMbvsHg0pwM0u4c4E2ctJWOs38TxOrLCeQR3DsrtUOfJehPKT3+ZJotFz/ECg/Ib3lLSldUjaZK4TfeqdAjS3GkI6MrRotIxHx1e/OgmQVBaqgczjdHbhtSCJg+AjNrwWaQ1jTJvFbvl9i5BWibv0Gfok4ogLHl36696d/Xifd+yt0m28bTeqTq1UL+gDFgM6/nQEH1zuILA4ZDhewg8uHtGXehbuSkGdFYZnL2EiHLCtjai/xaMATZlXNYQ8i0MwBo3YT6ME/flhxNcQWrFfwHUdV8ec0shAOoPfrMfvBZAg+/UaQ48A2rO5KHSlApF469ppkl3d04GX82IGQQ5En23Xj/BnlVSElLZHWC0vao/tf+BFdyhCptmD5ChwIJVFQjLUs1yjdkT964pV9thTGpHxDr4DeD5+gRfNUgjmQqkYaFWQwEnaagegA2I4e4U22VjTCAzqQcqzBqmOpBHUcSnfSju9q9Z5W+y1aE3A7oNV2dUidNqSSCK02SAHXBFbialaKS/9Y0xIympC7NnvAZ54buQZCbooRVhu2BCo9yAAHagPB4i8ovh8l4qnw2B1/zEzBPspeqPl3fR3dhktkglW3CHNmKLePeQlXdpESWCUTjvVxa9t6FRn9UCXG1sY3Dr8dyWfbKH8TPSncD3oLBFw1elFD0uG77BfGYcwJies351omWCzk5F4Oy1yUtk8q+Ysx59OATkfyh1hr7w+AeVmsOeNjz9lR4AFwF2d7i8C7T1+Zzbk0n1Iq3LmZ6tiIqOV2xA1CFpeBx9zCNlVxyx3EiUB/WCGRGo3QZPCZIv/4QcAkPiYAhrSngYkRQI1draZRalzUaSkpZDyTD45Fzl4pr9k48vJ0obHH9B9ChUrVlFCUJXofJYLYLtoWqrAd/ehRb/BDvQFYuoh3Hv2xxvWtDbaQayajwrcmVegWVBqvVYrhNLEL9Zhng8PJUm6NAa7I5mGPiT19XYlX8eDt1+3zaYjrz4s28ry4FzF9idNJ28bI0eoLo4NfJN6u1Vh1H6KbU+4+wC0IOT0m3i6B2wASmbXUC3LOKuahUdllqB0/InzJVjnu0lhyvUSrPkQelyiSJ8UitPXIXvjq4kqcpjnXee8zIyRXGZJcI3BWNrd++V70McyQvhzS1gxjcyb/Cy1tF8ATYwhigLEp3lX75aGezizzbqyKt/JwArV8rS4qawTgxAMDtjPHLBtrBgMmqfdsR4FpMNk1TOvY1m6o3SDqVCSxdMDxwNsgAFiduI9LmoKJ/VlC9+YTZCSG143yfKpRiNy46xdep9nElqG5upZCtA7H81+EYtQpdMhs+qcoReWuN4yd6ezoaJRYa8fT1AtP5sWkfdt73eULInYP+GRD5XtFcWUVP550rkJ+i8wVpB72UESkbUJea/A4YJxVhE/nmmDqBlIk2cNftsjB4V36ZpunA5qodpnJCtUQMszQumhOfNCinG8Jxg26LJUU4LZp3w9A5sLXy9iStqBZ7I3QbwErhgpypxAkWceAy2BxRqhw7s57PtwoiT87m0jnNIzb2tM+C04OVjlVz+Va+pBwikXfrY1cZZh/6PESIvpx9HIuWLyVJUsUwwhZYzpOMuVl37xKAnKBEJMQovwsFeMr41sUM2/Vaahd8thiI36lD5chcEkhDxw8N1QWHG1djftqKqt6HS0O8bWZnXWw2XQsZkJvmRAt8Bgsrc5Wyq1gSTSMS4K5fk0oZkG5qmb4aNz2L0YAvKzEatd6ziAKEGOHUbasvcd29/jiuHbFOgxgJbU0NTuCIZ64/pWtrZGLBSVg0OfJ5ZoOA0OnhLOhO+b/AUrhvrqpPfxbUAT72JSzt0hpND4SAry2ta54+NeXQeyk4J+n80KCln7h0MLcicTIh5V4qEdcq/BafsB7TwDXmbv7yFQhX0xyT6EhdH6K9wuw++3nAVgqZOcj/m0FZepAH8mBjLRvLyVMySHW11Uf1YV4K43PiaKHWQsKTXWg6CaoStEr7hCr4kJ19Vu1sR8+WZNJbIIhVpPtIABTXody/UIERunoNxwFm/fB6brnQs83SSBPMjqmkxhPYTKb6gdC7f+EO1Cjkxw/JjuW4VWWUAQihnmezgP9wL/dsWeY93kjcuW0hiv7jWST/rxJ4iizz1VtJYtuB8sKZoH5jlD9g/278cciO4thOcb7Xr9Tvg+wsybRm7AHaeKonELL8PxTJCpwYTkWnXpzJy4Qw3NJefi2z5lLbinAujP5NgsN5LiSgxa/Et5U3NBu1HtFQE7GHJWUk38K+o1qAX8+Gwsx0KBqwry9zOdc5NLVBBu3lsiI1tTbpq2QcV+Sp5xwOvTN8xyyQsuJLT80NMASSCencG9OUy9h9OAv2SzB5vqwek9XzN1Ria2iGANYVVqXg5jDaZANRfdfX3w2Aa6bj525tJZ13FoSMhwxlrCtllohHZlCCFJwhHyjjRd2qQI0hMOnO17LutlWDKgPhruzHu8gz64QmrL9vPRNVKrm0x1McUTRZZ+12bMy9Oups8WjPodoCBX1+1NxX21zvP9HzXxBPUpbC4OdvoO6ULujU6NuEmyGjRBIKy1Msf/JkWNAkkxjOIAotc1H2xOFE7ihpodqmesFTZcildPTv1DGxRpvjzkI5zlyOMUEWtcEVU0cYyJwjNSXzVOwXjg5/7ehd6QEUiWg8/W9NpfHzs9RIDyKdDtaL4zOpf4i/s7Lpk2Y/fHv/I6M+8Fki8420YK/ejNjPGbTcu3/l44f8y+d0qB2w7qPq8CUgFXS0i3/Hg0nncmNSiAI6HMx8+IdD+nf2d4u31SvQ/PnJoY/BFW+2u2+amb8f06xAHxSu/jBsZaRzv78jw8N9fZNLyvUZ3wVYeHvEzcZ5ooI9cyT3egZSMIAQMdaGxT14CwdCT5jIe0jCSUQgNoXCENf9875HBeEArc5bT7n9abOEml8YGopTZuogyUxMlVyu4zXHdHD9EgxV83Jhyr/FSuolCvh1wlw5ndv1dTmdP83jJFptzyqoae2E7Tdj3LvGWqQ2Qu/pYiwmU3Fs1TgxECoedpO3K9+eQXw3O6xEVczespr++PFrplla+mKd4IK+g85eKheOxz9e4tK3E5VlcT6XNaqu8XnDjXrjoTeId8LmMq2JW8d7AcORczdHx1JG+KvKha2XBKafVwTzWhJj8EM6jKPgt0lg5fzO+wGRUAB0iSodFDCEA2B7YYXTH43/YAIMQ9srnia2MpkTMGrb4lPMnjDpvrwv8x7Kp0HEiVpoWLiJx+VmK/sHLUIeG0OJouAMXdA2zJnpPYPr24w+vsfOg+yp7aJH1r56h3i5MwWvluuZusu/ds9EF1f2tqbf4oswHLt12ZeFiSK81SPN5NHsk9kySfivun5o6+51Hf4Phwo+BQGK2XAzj6nC5IJCpeGhGjJ/94YG4vzdssnGagenvscVZHEQhDl9JPx8tCZl+ecZjwWx9ietXKbMwY45f1jGCLrU8zUDO5jUowQAk7WbusXoHw27dqaJZ1eGJ3oIZTuRAG3o5cPLWzY0loLdKBFQxnS0+fthHSE2WNO2jYDvxtmyLs3Mp3HSyRkLb0a2E3oyYfdTVsb8Z/LQp6KqAj+yi4FC85nWS2ez6AA7ePkJVhQsuwwG8T5/AWCqjTt5mmxGcbJfORypBkf3ag7pxF1gNCEmsddV4h85xeivWCYu2iTYS6U/ZmZ7XEVscxiTomlup89t/bSpR14aVj/QTHIm9CiDQH0VPZuQSUJiy6gS5300kWoQrPAh5ZPFwxZLYF7wowtCjO2ldUDqGrhiyR9EcqACrtitIYiNvBivPyOvdfvGJmVtpSAVtYOtrayb1ZJMTMDJU1F6enEhzx92PTG+Elb3cR34wuT/67NScFLT7yQcexhWHzOCPVr+gJFBFjiV1M8ofSStz21eG8FpN8lLx4uNJrBSjYA9hSdPdcUeFpxpAvPZD5oX+nt1ly/NlJqwKQVqz47pkbaCDt+V3/ZpnTTlnvMBARgjakvwSOjXIXVMlMQiKLnl/Bc5EwlFBu7240Mo0OPNJltI0Bm8FtXFdtZifrRq1y1yYYguRmWmbdsCsdlCiAGwlz2nrSjWsocujBUEsauF3/1MWWMnSLpe+c/OLYJsDD89+QUNgV98MQP8VETzJRy+wZ5adOPC4rngtkpLCfyqM/zzIc3kJaEQtUZ5jqSQh3rHI0X5GCCQZIQrdl5UIA+FVBAtS38r/afE1tbnllazU1jzYE2ISFtQKE7r3WbFYH3m7f5ruNN4mKMSE+bwrHeHzVRVYiYhez6kUwdIfMMJ1XAPQKHyTL2Sv1r2SPj31J1/81n0okVRqCNPF6RctltHzJsDAgh8Z/mZnG6hgHYUw0h2kTkGDF7wEnepaFHE4xxIxNRWDnd3yAGg6xjmhjPf7pntDhLy8eiS+EBbNpgN7sqbqc2quZYDEQE+Ed9d4l2hyLbjBvIz8yjbWwddEDTecYSCsPkrLs6D7dQ7zLAJ0YJVJzy3RyaZQ9+Q1XQlgrluZ8VNFFqNpuox7aHteF1Tk8mwpEsG2k6DMnKJCqePapETEe/sM+ZzAuUQvtlsiM9WgSY4YkL1F7PnIlMWiIqpdOg5W7pZIkTHZbortlOjLAsj34krDBniII8OvnD3Ms2RjAE3cI7zs65zoRXNBcx3hAXpOmPe0WGOt9UjyI5reDNcA4AyA+xwNJkMH8z2l86+Ou72i2H7cfLmTSFD32XuvcWFq8Eahfnoz4ebv9X8HCw92xjTDjai/oL1+iJ21Tc4FTEoh/uvCKT5M8zFPg+aTe2JUr9hQlvUIsvTUJiMwCQDozqsGpgd+DrH6TXieCTgFJSG3xieYvyHnr31tkAh01BAD50GZKC4t2jm85pCYvcGRYqbNBMEzYGA7tK+ng2D+8WrePv45KkydIx4jm5IMu0THHchq6qPz/uWiO1kcJP5AGLQbj4weQXaEBNc1mayWeVA2JfZMvobptU33NrKcQYlNt/KsJDb2gO43vV8ikbxu/O+1wt/CrIcNnQTw7utAbj5kqUNYx/5O/hXdBHkMI4jWZKh0psu4CLfBLAHJn4A7h4JD3GxJy+rtMa8HeUcpOobXkSNVEwIHNJ9dt6F5/ZZKlO+H3/1PqfcPDGfB4a8Bu7Ez2TeMhgsmVERRr7IhrT6DasKTXyHCjsWtrJ3TJkWrKXbiJAfXvLGgkctXHIq2nw/3sVSgAGs8ZnYZHucmewA5SWbyQ2gchR91Vy6qUXTE1vRxZlXYUZ46ypm3RSm+2aa36zhY5LQVZsgyrc9OZ6rI4PnBCErkhrjIluj23M9FYekLHPwqUT2qQQMhNuj+8iO26fNZODOzVLjlFUH/B1Eg1OT0uJBHrLvfWdAE++xeyAHZ84/bAqqxYbps0g/W8zQwLlKUyptqzQyrVSEFECl3KuDTUsT3MRxqc9001fiUcmCg03vqGlzaXrsaPn7XRHi9GY4MME48QBPl91wBwEN5O813btmkyKi671r0JY0fI/A2XWkaPMBul5yTHdYdMbYQ4cbkUR5m0id/4ebVN9g6nPbEWpEJuebRqStAFeh1VD/mf7q8laXvZjdVXSKR5ZIWbBW4HrRHipl29wJxGqgWi16hw8zpwYzas6TGTMywBFpW+n3QTrua+87YgPxjd8J4UxP3DMpY9XbBvSKO2TpU4NeONWMOjvSxFmHiT8gVzoTMOiIgAoN7ikLjFUJlue5kSxzT3UDJ2CCbmfJ0pXSFBryLNB2k22vsNCRZ8JL38f1xuksJTcAM0tGPFLZw2GEe6HHlCiirNWLaemFtkzV4w/m4dwghFwccEy1iLq80HWLvyZUO1JVRmsi/iUo6z/T1Q4cPsJT0tNflSNfUHZVLVlwZB6Zz5ttKSLHAK327RfGt5DcKLAVBs1MHW3Pkvi1tnAnDc7NbVOFU9/xe8EuCIVMws3ZP9YM24lwLg2HhkT8LBgl0vcpKH0t5xExYx0Z+3DzBiVjtzBscVIIPiXMOOtnBV5y6YkdcomOK1NSBr+oi2HZnP2V0R0H9lu+DraNcyU6skOI7RWbL1i+I7GeQmXoe+u3hcUYBOhXpkFf/5haDkg/afkZD9oHcjhPj7NSXA7tv4pqVmh0HHG3pqsoLo0KbZVOs6rhVqBqMfw5YPCA89hDfuegPSXWKEup/gAygGRyJ8SvhoK6hpasY65qsYx30W8hSziLhqY/Vd5tjauhKOT7eWJxg1bgwmAHMsz+LJMv6X6OIqRwn60NMgc5u/6W63/bNNSrcSCxJOhQKSiRyy0xla8Ch6kzcq772C9CuHNV3YGmhYPW2Zg1vPiMzEFBbqQ3Wv8QLscTYua5tgY3tSFYfu6om+2/h1zfERGM4AU3fhyWPj8S4QjaFfU3h0JDQ7dLYOfrLhaTDz+598kSBF6MEpSf+iCK00MWgkgLwt/YG8GYEmG3/JbxQBSkIwvMsdMO56orRgNgAFG/QPIzfzYcWvsGx/nJA/X5RsIS7JylqF5xsGO3m8gEtyBD9YxHMH/GFEirfnZDWArQ+E/4UM4spzzLHPb4qalEDO60wV2TvmD3dDWb996ZHrNpMkHyLvQ6+WxAqEx9x/n3CR7KmbyMCnynoxjydyEpVDPpJAC/PZOcz6ay9vg+9rAt2fVXhQDcjmvWjUqgG61XJ6RkiXiaXMrbGBSaOpy0Tl/UtIyafCQ8U1p6H1vE7OD0ppWFycLVRURqSnHmMFVwYgAFNBv6/zxyJF+Zp4rPYUkrZ9jMWoYs3F1PX/4Z9J0V4l12cM//Wnf2R2rWQe8thq+zGFPOe6FhOHPFIbF8BhTTyD2nfdnhDM9SNKNrzQodS66B0/nAFi3AAAAK3zaIiF1liUJQAu1qsEWSkigbGMlV7mP0kEJDbnNcVXHvIeuWrSc9GGkoP58IU8UMy00jcAXYN50AbWV4Rlpxnf98xxEb6SbBujBgJG8WhkrXfroZfDgyKXbSidMlZdObFhgOwEKhrNgQSB62zj/PjXjrbnR7cPczjRPADoLfJ25k1TUkvKHwSqsFRHDKPu4QdHOZomjQLc49KkTfLvNhgnAqmuJN/dE9YNU2/suOobw0eDLxafygk4NJP5HKGhjZp2zvRTLuTaiDvgBCPjy5xyrgIrV9a7IEgLqzD4qlBcK1B/DYk565VdoW4R/KMmXi6u/M5FTvHzuu2lROUD8RpS35YwPbD4iQT5NCTZqzn96beZGQiZeqDQ78LzZPa2kK+CvXuo9Hxirjygi6vlFw0nE2EE+eE9Sz+3Xt8qyYIFx/7uD3kPF4/jpbXV76337Vw94czVG+m3+tD+9k2wVEj4SDDjLrj2yapqoxChM9t1Q1n2o7nVBWV4oswWRguiE7ZXKgmp74z7yJDSfF8qxm2gAthrxjfYtqR7nLztw1WOfvrDRiCsu3NoQ2xGVzSqrgMU82KI5SwISsyESpVKfZ/0uQe8XbPV94RmkB+Rd//2EItOvLhj6WuL3dpgKKVFRrDj/YdgUlMZ3DLRW5iiH/lbrUgXxMBi7zhPnnSw+W5RrDtZFMXFiZM2rFA3knQpVtxeOx6+jK6JwFXJ8WJ+zMH80zGPED68eIIpxrXxAlCFRdvV6q+GtRHCzWCK0zVbJ6sNAkb3aDQQkw86EpxvOxHoXgrjfMJV2p5OnVikz5IlQzl7LsQhvKUScHNt6IFD4Oow08Q7UHsLL5lfSTzN8iTRT6oxvHhiEoXssR1mRSi1DSWJruDn8cG9Zq+IdB9bkCmtRoDDKvEGPftUvrOvhohmmhkxB/f1JDd6N0b6uTFj7W81+s+YXTscuoH78HFfX3xSdJwGPb3ox6aD2bmP6NM8ti8O8dx6FRxcsOpj5uiIsZUHC+IQx2bJOdo5ML7It2Lmqf1EaHDYAH5fnRhYUHOtFYIWUhH/BZcctfq5FTaHHNiCnMEL3OZf2lVw8UQKvzFciv/tIhTSSukWxv8+u6yPrnKMBPJg1k9SW8BQXJvCTclrjwRzqmejYpRlXe7Rtf6bKEPvSfCGrOhNf0D97GPOZrcDNksd7O2y0ua3QTEkWr1WXIu7s0Cl7HTJQBlqr+l6An7dbJ9mwdm8vaADFzBVmZMStDCgvU9FgQnmtNlPfTybtF30xdykmW6RzCkOSGWcyC0UAHdX4Z9DFRDjup890oHHEgzEAegpFpT3Q9TjOCMkgTjBWL+KhlDNJIkfMYmnk+XnX6OYAGwlkRJFX461cN9p0xUIYyceUqMAKmiYpdu3aUhbxg/JlVFMsqcXtHQ2EoRm5CQg5klCUMOX1vaRBwWYIRgb59U0LbX/3LDn/uApHcONo20R4Rhv9jvOIIRc2fzqaZ6CwYrxa06Lb5o3q0xr1u5iime0tTzovG5Qjtu4zkeczU51tNDXpW5AeXXxpwK/2DOFexAV+q2R/K6L+rcdoExqgxSAeR3mWwDx3EUaimqtAjhV6F8L5aJuWENBfOq8Q9NHz9vSgm4AKJkQZUShdQFcqujhfNQLx6GLgHlEugAALKWyMU6AM2mtEC4C7D1m0AIECi8sOPN2thjgccKtCTCrrOxrVhxKtchyFcIM+ZzdyFM8A1WpWLnVaTDBcNpDBadkQ6HQbB2ZWATKOYQ/9dGWGuLIaKX5yAGWgG8WAAAAAAA=";
		const MP_CONTACT_ZH = {
			nav: "交流群",
			title: "交流群",
			intro: "遇到问题、想反馈 bug，或者想聊聊怎么用，都欢迎进群。群里也会同步新版本和已知问题。",
			groupLabel: "群号",
			copy: "复制群号",
			copied: "已复制",
			copyFailed: "复制失败",
			qrAlt: "交流群二维码",
			howTitle: "怎么加群",
			how1: "用另一台设备上的手机 QQ「扫一扫」扫上面的二维码。",
			how2: "或者打开 QQ → 右上角「+」→ 加好友/群 → 搜群号，粘贴上面的群号。",
			how3: "群号是纯数字，长按选不中（本插件禁用了全局选中），所以用右边的按钮复制。",
			notesTitle: "反馈问题的小建议",
			note1: "说清楚「哪一步、期望什么、实际什么」，比只说「用不了」好定位得多。",
			note2: "设置页里「运行日志」能导出会话记录，贴上来最有用。",
			note3: "顺带说下机型和系统版本（例如「小米 13 / MIUI 14」），很多问题只出在特定机型上。",
		};
		const MP_CONTACT_EN = {
			nav: "Community group",
			title: "Community group",
			intro: "Found a bug, or want to talk about how to use it? Join the group. New releases and known issues are posted there too.",
			groupLabel: "Group ID",
			copy: "Copy ID",
			copied: "Copied",
			copyFailed: "Copy failed",
			qrAlt: "Community group QR code",
			howTitle: "How to join",
			how1: "Scan the QR code above with QQ's scanner on another device.",
			how2: "Or open QQ → \"+\" at the top right → Add friend/group → search the group ID, then paste the ID above.",
			how3: "The ID is digits only and cannot be selected (this plugin disables global text selection), so use the copy button.",
			notesTitle: "Tips for reporting issues",
			note1: "Describe \"which step, what you expected, what actually happened\" — far easier to locate than \"it doesn't work\".",
			note2: "The \"Session log\" section in Settings can export the conversation; attaching it helps most.",
			note3: "Mention your device and OS version (e.g. \"Xiaomi 13 / MIUI 14\") — many issues are model-specific.",
		};

		/**
		 * 「交流群」分节组件。
		 *
		 * 纯静态信息（无 bridge、无原生状态），唯一的有状态部分是复制按钮的
		 * 短暂反馈。布局沿用「特权权限 / 悬浮气泡」那套卡片风格。
		 *
		 * @param {object} props - 槽位注入面（只用 "t"）。
		 * @returns {object} React 元素。
		 */
		function mpContactSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_CONTACT_ZH[key] ?? key;
			const [copyState, setCopyState] = React.useState("idle");
			const timerRef = React.useRef(0);
			const mounted = React.useRef(true);

			React.useEffect(() => { mounted.current = true; return () => { mounted.current = false; if (timerRef.current) window.clearTimeout(timerRef.current); }; }, []);

			const doCopy = () => {
				mpCopyText(MP_CONTACT_GROUP_NO).then((ok) => {
					if (!mounted.current) return;
					setCopyState(ok ? "done" : "failed");
					if (timerRef.current) window.clearTimeout(timerRef.current);
					// 2 秒后回到常态：文案在「复制群号 / 已复制」间切换，按钮宽度固定，
					// 不会让整行来回跳（与权限分节的复制按钮同一处理）。
					timerRef.current = window.setTimeout(() => setCopyState("idle"), 2000);
				});
			};

			const copyLabel = copyState === "done" ? t("copied")
				: copyState === "failed" ? t("copyFailed")
				: t("copy");

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.qrWrap },
						React.createElement("img", {
							style: S.qrImg,
							src: MP_CONTACT_QR,
							alt: t("qrAlt"),
							// 二维码是装饰性内容，不让长按弹出「保存图片」菜单：
							// 保存下来也没用（用户要的是加群，不是存图）。
							draggable: false,
						}),
					),
					React.createElement("div", { style: S.row },
						React.createElement("span", { style: S.rowLabel }, t("groupLabel")),
						React.createElement("span", { style: S.mpGroupNo }, MP_CONTACT_GROUP_NO),
					),
					React.createElement("div", { style: S.cmdRow },
						React.createElement("button", {
							type: "button",
							style: S.cmdBtn,
							onClick: doCopy,
						}, copyLabel),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ol", { style: S.steps },
							[t("how1"), t("how2"), t("how3")].map((line, i) =>
								React.createElement("li", { style: S.step, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「交流群」注册进设置页一级导航。
		 *
		 * 与前两个分节同一套接缝（"settings.section"），所以不需要碰导航 DOM。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterContactSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过交流群分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_CONTACT_NS);
					scope.effect(
						() => scope.locale.register(MP_CONTACT_NS, { zh: MP_CONTACT_ZH, en: MP_CONTACT_EN }),
						"dsh-android-mobile: 交流群字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-contact",
						// 紧跟在「悬浮气泡」（26）之后 —— 反馈入口排在设置页最后，
						// 与「关于 / 帮助」的惯例一致。
						order: 27,
						label: () => t("nav"),
						locale: MP_CONTACT_NS,
					}, mpContactSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册交流群分节失败:", e && (e.stack || e.message || e));
			}
		}

		/* ══ 「运行日志与诊断」分节 ══

		   需求（用户原话）：做一个"极其强壮、极其详细"的日志系统，两个入口 ——
		   一个在设置界面，一个在崩溃界面（加载界面）；崩溃与加载界面"不管任何
		   时候都要有这个日志接口"。

		   本分节就是**设置界面那个入口**。它自己不显示日志内容，只做三件事：

		     ① 让用户知道日志系统存在（默认没人会去翻文件系统）
		     ② 给出摘要（多少条、几条错误、几份崩溃现场、几份待上传）——
		        用户看到"3 条错误、2 份崩溃现场"才知道值得点进去
		     ③ 一键拉起**原生**日志界面

		   ⚠️ **为什么不把日志界面做成页面插件**：因为它要能在「页面根本打不开」
		     的时候用。插件没激活、前端 bundle 挂了、引擎没起来 —— 这三种情况
		     恰好都是最需要看日志的时候，而那时页面插件自己也活不了。
		     所以真正的界面在原生侧（"LogViewerActivity"），本分节只是个入口。

		   ⚠️ 另一个入口在**启动/崩溃浮层**上（"activity_main.xml" 的
		     "@+id/overlayLogs"），它常驻显示、不依赖任何 Web 层。两条路合起来
		     覆盖「界面能开」与「界面开不了」两种情况。 */

		const MP_LOGS_NS = "dsh-android-mobile.logs";
		const MP_LOGS_ZH = {
			nav: "运行日志与诊断",
			title: "运行日志与诊断",
			intro: "应用、引擎、网页三方面的日志都会记下来。出问题时点进去看，或一键打包发给开发者 —— 包里带着设备信息、崩溃现场与自动结论。",
			statusTitle: "当前状态",
			statusHasError: "有错误",
			statusHasCrash: "有崩溃现场",
			statusOk: "正常",
			statusUnknown: "读取中",
			rowTotal: "日志条数",
			rowWarn: "警告",
			rowError: "错误",
			rowCrash: "崩溃现场",
			rowPending: "待上传",
			rowLastExit: "上次退出",
			open: "打开日志界面",
			refresh: "刷新",
			showDetails: "查看详情",
			hideDetails: "收起详情",
			nextHasCrash: "上次留下了崩溃现场，建议点「打开日志界面」导出诊断包发给开发者。",
			nextHasError: "日志里有错误记录，可以点进去看看。",
			nextOk: "一切正常。遇到问题随时回来这里。",
			noBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法打开日志界面。",
			uploadOn: "已配置日志服务器（可在日志界面一键上传）",
			uploadOff: "尚未配置日志服务器（只能导出到本地）",
			howTitle: "怎么用",
			how1: "点「打开日志界面」—— 那是原生界面，**引擎没起来时也能用**。",
			how2: "界面里可以按级别（警告/错误）和来源（应用/引擎/网页/崩溃）筛选，也能搜关键字。",
			how3: "「导出诊断包」生成一个 zip：含设备信息、三份日志、崩溃现场和一份自动结论（diagnosis.json）。",
			how4: "「上传给开发者」直接发出去；失败会留在本地，下次启动自动重试。",
			notesTitle: "几点说明",
			note1: "日志会脱敏：token、api key、密钥一类的内容写盘前就被替换掉了。",
			note2: "日志不记录会话正文，只记元信息（哪个会话、什么时间、什么动作）。",
			note3: "启动/崩溃界面上也有一个「查看日志」按钮 —— 应用打不开时从那里进。",
			note4: "「清空」会删掉已落盘的日志与崩溃现场，不可恢复。",
		};
		const MP_LOGS_EN = {
			nav: "Logs & diagnostics",
			title: "Logs & diagnostics",
			intro: "Logs from the app, the engine and the web UI are all recorded. When something breaks, open the log viewer or bundle everything into one file for the developer — it includes device info, crash scenes and an automatic diagnosis.",
			statusTitle: "Current status",
			statusHasError: "Errors present",
			statusHasCrash: "Crash scene present",
			statusOk: "OK",
			statusUnknown: "Reading",
			rowTotal: "Log entries",
			rowWarn: "Warnings",
			rowError: "Errors",
			rowCrash: "Crash scenes",
			rowPending: "Pending upload",
			rowLastExit: "Last exit",
			open: "Open log viewer",
			refresh: "Refresh",
			showDetails: "Show details",
			hideDetails: "Hide details",
			nextHasCrash: "A crash scene was recorded. Open the log viewer and export a diagnostic bundle to send to the developer.",
			nextHasError: "There are errors in the log — open the viewer to inspect them.",
			nextOk: "All clear. Come back here whenever something misbehaves.",
			noBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot open the log viewer.",
			uploadOn: "Log server configured (one-tap upload in the viewer)",
			uploadOff: "No log server configured yet (export to a local file instead)",
			howTitle: "How to use it",
			how1: "Tap \"Open log viewer\" — that is a native screen and works even when the engine never started.",
			how2: "Filter by level (warnings/errors) and by source (app/engine/web/crash); search by keyword.",
			how3: "\"Export bundle\" produces a zip with device info, three logs, crash scenes and an automatic diagnosis (diagnosis.json).",
			how4: "\"Upload\" sends it straight away; if it fails the bundle stays locally and is retried on next launch.",
			notesTitle: "Notes",
			note1: "Logs are redacted: tokens, API keys and secrets are replaced before anything is written to disk.",
			note2: "Message bodies are not logged — only metadata (which session, when, what action).",
			note3: "The launch/crash overlay also has a \"View logs\" button, for when the app itself won't open.",
			note4: "\"Clear\" deletes on-disk logs and crash scenes permanently.",
		};

		/**
		 * 「运行日志与诊断」分节组件。
		 *
		 * 布局沿用「特权权限」「悬浮气泡」那套**结论优先**的结构：
		 * head 卡给状态徽章 + 一条「下一步」+ 直达按钮，明细折进 "<details>"。
		 *
		 * ⚠️ 摘要数据来自 "DSHAndroid.logSummary()"（同步返回 JSON 字符串）。
		 *   它刻意只读内存缓冲 + 一个小 JSON，**不读磁盘日志** —— 设置页每次
		 *   打开都会调它，读几 MB 的文件算行数会明显卡顿。
		 *
		 * @param {object} props - 槽位注入面（只用 "t"）。
		 * @returns {object} React 元素。
		 */
		function mpLogsSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_LOGS_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			const [showDetails, setShowDetails] = React.useState(false);

			/**
			 * 从原生读一次摘要。
			 *
			 * ⚠️ 桥**可能不存在**（非 App 环境、或桥被清理），先查再调 ——
			 *   直接调会抛 "TypeError: Cannot read properties of undefined"。
			 *
			 * ⚠️ **必须补读一次**（"again=true" 的那趟）：gecko 版的 "logSummary()"
			 *   读的是**快照缓存**（native messaging 只能异步，见 content.js），
			 *   第一次拿到的可能是旧值；那边在读取时顺带请求原生重推快照，
			 *   所以隔一拍再读就是新值。system 版是同步真值，补读无副作用。
			 */
			const refreshRef = React.useRef(null);
			const refreshTimer = React.useRef(0);
			const refresh = React.useCallback((again) => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.logSummary !== "function") {
					setState({ phase: "ready", noBridge: true });
					return;
				}
				try {
					const raw = bridge.logSummary();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setState({
						phase: "ready",
						noBridge: false,
						total: o.total | 0,
						warn: o.warn | 0,
						error: o.error | 0,
						crash: o.crash | 0,
						pending: o.pending | 0,
						lastExit: typeof o.lastExit === "string" ? o.lastExit : "",
						uploadConfigured: !!o.uploadConfigured,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取日志摘要失败:", e && e.message);
					setState({ phase: "ready", noBridge: true });
				}
				// ⚠️ 用 `!== true` 而不是 `!again`：本函数也挂在事件监听上，
				//   那时第一个参数是 event 对象（truthy），会把补读吃掉。
				if (again !== true) {
					window.clearTimeout(refreshTimer.current);
					refreshTimer.current = window.setTimeout(() => { if (refreshRef.current) refreshRef.current(true); }, 300);
				}
			}, []);
			refreshRef.current = refresh;

			React.useEffect(() => { refreshRef.current = refresh; refresh(); return () => { refreshRef.current = null; window.clearTimeout(refreshTimer.current); }; }, [refresh]);

			/**
			 * 从日志界面回来后刷新。
			 *
			 * ⚠️ 用户点「清空」或「上传」是在**另一个 Activity** 里做的，本页拿不到
			 *   任何回调。只能监听 "visibilitychange" —— 不加的话用户清空完回来
			 *   看到的还是旧数字，会以为没生效（悬浮气泡分节踩过同一个坑）。
			 */
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) refresh(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [refresh]);

			const open = () => {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.openLogs === "function") {
					try { bridge.openLogs(); } catch (e) {
						console.warn("[dsh-android-mobile] 打开日志界面失败:", e && e.message);
					}
				}
			};

			if (state.phase === "loading") {
				return React.createElement("div", { style: S.section },
					React.createElement("h2", { style: S.heading }, t("title")),
					React.createElement("p", { style: S.intro }, t("statusUnknown")),
				);
			}

			const noBridge = state.noBridge;
			const crash = state.crash || 0;
			const error = state.error || 0;
			// 严重度按「有没有崩溃现场」> 「有没有错误」排 —— 崩溃现场是硬证据，
			// 错误可能只是第三方路由的一次 429。
			const tone = crash > 0 ? MP_PRIV_TONE.off : (error > 0 ? MP_PRIV_TONE.warn : MP_PRIV_TONE.on);
			const badgeText = crash > 0 ? t("statusHasCrash") : (error > 0 ? t("statusHasError") : t("statusOk"));

			let nextText = t("nextOk");
			if (noBridge) nextText = t("noBridge");
			else if (crash > 0) nextText = t("nextHasCrash");
			else if (error > 0) nextText = t("nextHasError");

			const dot = (ok) => React.createElement("span", {
				style: { ...S.dot, background: ok ? MP_PRIV_TONE.on.color : MP_PRIV_TONE.off.color },
			});
			const row = (label, ok, value) => React.createElement("div", { style: S.row, key: label },
				dot(ok),
				React.createElement("span", { style: S.rowLabel }, label),
				React.createElement("span", { style: S.rowValue }, value),
			);

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.cardHead },
						React.createElement("h3", { style: S.cardTitle }, t("statusTitle")),
						React.createElement("span", {
							style: { ...S.badge, color: tone.color, background: tone.bg },
						}, badgeText),
					),
					React.createElement("div", { style: S.next },
						React.createElement("span", { style: S.nextText }, nextText),
						!noBridge ? React.createElement("div", { style: S.actions },
							React.createElement("button", { style: S.btnPrimary, onClick: open }, t("open")),
						) : null,
					),
					React.createElement("div", { style: S.actions },
						React.createElement("button", { style: S.btn, onClick: refresh }, t("refresh")),
						React.createElement("button", {
							style: S.btnQuiet,
							onClick: () => setShowDetails((v) => !v),
						}, showDetails ? t("hideDetails") : t("showDetails")),
					),
					showDetails ? React.createElement("div", { style: S.details },
						row(t("rowTotal"), (state.total || 0) > 0, String(state.total || 0)),
						row(t("rowWarn"), (state.warn || 0) === 0, String(state.warn || 0)),
						row(t("rowError"), (state.error || 0) === 0, String(state.error || 0)),
						row(t("rowCrash"), crash === 0, String(crash)),
						row(t("rowPending"), (state.pending || 0) === 0, String(state.pending || 0)),
						row(t("rowLastExit"), true, state.lastExit || "—"),
						row(t("rowTotal"), state.uploadConfigured, state.uploadConfigured ? t("uploadOn") : t("uploadOff")),
					) : null,
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("how1"), t("how2"), t("how3"), t("how4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3"), t("note4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「运行日志与诊断」注册进设置页一级导航。
		 *
		 * 与特权权限/悬浮气泡同一套接缝（"settings.section"），不需要碰导航 DOM。
		 *
		 * ⚠️ "order" 取 28：排在「悬浮气泡」（26）与「交流群」（27）之后，
		 *   也就是设置列表最末。日志是**排障入口**，不该占据日常设置的显眼位置，
		 *   但必须存在。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterLogsSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过日志分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_LOGS_NS);
					scope.effect(
						() => scope.locale.register(MP_LOGS_NS, { zh: MP_LOGS_ZH, en: MP_LOGS_EN }),
						"dsh-android-mobile: 日志字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-logs",
						order: 28,
						label: () => t("nav"),
						locale: MP_LOGS_NS,
					}, mpLogsSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册日志分节失败:", e && (e.stack || e.message || e));
			}
		}


		function apply(ctx) {
			mpRegisterPrivilegedSection(ctx);
			mpRegisterBubbleSection(ctx);
			mpRegisterLogsSection(ctx);
			mpRegisterContactSection(ctx);
		}
		const exports = { apply, inject: [] };

		return exports;
	}
});
