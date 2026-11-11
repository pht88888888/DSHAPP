export function createAliasBridge() {
  const MP_LEGACY_HASHES = {
  				"Nqubda": [
  							"panel"
  				],
  				"VOzbGW": [
  							"active",
  							"header",
  							"mask",
  							"navCell",
  							"navIcon",
  							"navLabel",
  							"navList",
  							"navTitle",
  							"options"
  				],
  				"Y0dWHa": [
  							"detailBody",
  							"details",
  							"detailsLocation",
  							"overviewPreview",
  							"split"
  				],
  				"YDXeBa": [
  							"chevron",
  							"folder",
  							"iconButton",
  							"menuOpen",
  							"projectRow",
  							"rowActions",
  							"searchResultRow",
  							"selected",
  							"sessionRow",
  							"time"
  				],
  				"bOPqQW": [
  							"anchor",
  							"label",
  							"pill",
  							"root"
  				],
  				"bhn1Oq": [
  							"iconButton",
  							"searchButton"
  				],
  				"hHd-Xa": [
  							"collapsed",
  							"footArea",
  							"iconButton",
  							"newSession",
  							"root",
  							"toggle"
  				],
  				"nL4_yW": [
  							"moreButton"
  				],
  				"pI_x6G": [
  							"centerCol",
  							"frame",
  							"rightbarCol",
  							"sidebarCol"
  				],
  				"qSYn7G": [
  							"card",
  							"cardDetails",
  							"cardTitle",
  							"cards",
  							"details",
  							"group",
  							"section"
  				],
  				"uV2eYG": [
  							"grow",
  							"input",
  							"root"
  				],
  				"wSkVaW": [
  							"crumb",
  							"crumbCurrent",
  							"crumbs",
  							"header",
  							"headerActions",
  							"headerCorner",
  							"headerUtilities",
  							"tab",
  							"tabs",
  							"titleCluster",
  							"titleRow",
  							"viewArea"
  				],
  				"xzv4MW": [
  							"timeEnd"
  				],
  				"zGbnIq": [
  							"title"
  				]
  	};

  /** 旧类名 → 当前类名 的映射（运行时推导，官方换 hash 后自动更新）。 */
  let mpPairIndex = null;
  let mpAliasDirty = true;
  let mpAliasSig = '';   // 上次生效的映射指纹，用于去重日志与重扫

  /** 桥接自己补上去的旧类名。用来防止「自我强化」，见 [mpCollectCurrentClasses]。 */
  const mpAliasAdded = new Set();

  /**
   * 官方「仍在用」的 hash 累积集合（**只增不减**）。
   *
   * ⚠️ 为什么必须累积、不能只看当下快照（2026-10-04 第二次踩这个坑）：
   *   官方 CSS Module 是**按需注入**的 —— 组件没渲染时它的 "<style>" 压根不在
   *   "document.styleSheets" 里。若判据只看当下快照，同一个 hash 会在「出现过」
   *   与「没出现」之间来回抖动，于是**同一个页面上有的分组头中招、有的正常**
   *   （真机实测：22 个 ".O_Ebla_root" 里有 2 个被误加 "bOPqQW_root"，其余正常）。
   *   只要某个 hash **曾经**出现在官方样式表里，就说明官方在用它，永久不再认领。
   *   真正过期的 hash 永远不会出现在官方样式表里，因此不受影响。
   */
  const mpOfficialSeen = new Set();

  /**
   * 预热轮次计数：前 [MP_ALIAS_WARMUP] 轮**只累积证据、不做任何认领**。
   *
   * ⚠️ 为什么需要（2026-10-04）：官方 CSS Module 是懒注入的，页面刚加载时
   *   "document.styleSheets" 里只有少数几张表，"mpOfficialSeen" 因此接近空集。
   *   若此时就允许认领，**没过期的** hash（如 "YDXeBa"）会因为「当下没证据」
   *   而被判为过期，转去认领别的 hash —— 真机日志实测：
   *     "CSSModule 别名桥接: YDXeBa→pXSMma (2 个类名)"
   *     "CSSModule 别名桥接: YDXeBa→oz9t_a (2 个类名)"
   *   这与 "bOPqQW→O_Ebla" 是同一个病，只是发生在启动早期。
   *   预热期的长度取自 "mpInstallAliasBridge" 的 600ms 轮询节奏，约 9 秒。
   */
  const MP_ALIAS_WARMUP = 15;
  let mpAliasRound = 0;

  /**
   * 采集当前存在的全部 "{hash}_{local}" 类名，返回 "byHash"（样式表 + DOM 的并集，用于投票）。
   *
   * ⚠️ 两个来源必须区别对待（2026-10-04 踩的坑）：桥接会把旧类名**补到 DOM 元素**上，
   *   若把这些补上去的类名也算作证据，下一轮推导就会「自己证明自己」，越套越偏。
   *   样式表是官方产物、桥接从不写入，所以它是唯一可信的
   *   「官方仍在用这个 hash」判据 —— 且必须**累积**记录到 [mpOfficialSeen]。
   */
  function mpCollectCurrentClasses() {
  	const byHash = {};
  	const add = (cls, fromSheet) => {
  		// ⚠️ hash 字符集必须含 "-" 与 "_"，且可能以下划线开头
  		//   （官方实测形如 ZTP-Xa、n_2Q3W、pI_x6G、_0gBrGq）。
  		const mm = /^([A-Za-z0-9_][A-Za-z0-9_-]{2,13})_([A-Za-z][A-Za-z0-9]*)$/.exec(cls);
  		if (!mm) return;
  		/* 官方样式表里出现过 ⇒ 官方在用这个 hash。**累积**记录，见 [mpOfficialSeen]。 */
  		if (fromSheet) mpOfficialSeen.add(mm[1]);
  		// 跳过桥接自己补的类名：它们不是官方证据
  		if (!fromSheet && mpAliasAdded.has(cls)) return;
  		(byHash[mm[1]] || (byHash[mm[1]] = {}))[mm[2]] = true;
  	};
  	// ① 全部样式表（含尚未挂载组件的规则 —— 拿到完整类名域的关键）
  	for (const sheet of document.styleSheets) {
  		let rules;
  		try { rules = sheet.cssRules; } catch (e) { continue; }
  		if (!rules) continue;
  		/* ⚠️ 必须跳过**插件自己**的样式表：本插件的 CSS 大量使用旧类名
  		 *   （如 "[data-mobile] .bOPqQW_root"），若把它当作「官方仍在用」的
  		 *   证据，所有旧 hash 都会被判为未过期，桥接彻底失效。
  		 *   ⚠️ 判据必须是**全等**比较：官方 DSH 自己也给每个 CSS Module 的
  		 *   "<style>" 打 "dataset.pluginCss"（值是模块路径），
  		 *   只判 "dataset.pluginCss" 是否存在会误伤 140 个官方样式表。 */
  		const owner = sheet.ownerNode;
  		if (owner && owner.dataset &&
  			owner.dataset.pluginCss === "dsh-android-mobile/main.css") continue;
  		const walk = (rs) => {
  			for (const r of rs) {
  				if (r.cssRules) { walk(r.cssRules); continue; }
  				const sel = r.selectorText;
  				if (!sel) continue;
  				for (const mm of sel.matchAll(/\.(-?[A-Za-z0-9][A-Za-z0-9_-]*)/g)) add(mm[1], true);
  			}
  		};
  		try { walk(rules); } catch (e) { /* 单条异常不影响其余 */ }
  	}
  	// ② DOM（fromSheet=false：其中的旧类名可能是桥接自己补的，不能当证据）
  	for (const el of document.querySelectorAll('[class]')) for (const cls of el.classList) add(cls, false);
  	return { byHash };
  }

  /**
   * 推导「旧 hash → 当前 hash」。
   * 判据：统计每个旧 hash 的局部名在各新 hash 上的命中数，取最高者；
   *   · 并列 → 放弃（套错比不套更糟，例如 bOPqQW 与 hHd-Xa 都可能命中同一个）
   *   · **命中必须 ≥2** → 才采纳
   *   · 同一新 hash 只允许被一个旧 hash 认领
   *
   * ⚠️ 2026-10-03 修复：原判据有一条 "|| locals.length === 1" 的豁免
   *   （「该旧 hash 只有 1 个局部名时，1 票即可」）。这条豁免在
   *   "Nqubda: ["panel"]" 上炸了 —— "panel" 是极通用的局部名，官方
   *   右侧栏的 dockkit 面板（"P3OORG_panel"）也用它，于是桥接把
   *   "Nqubda_panel" 补到了**那个面板**上；而 §5 的审批浮层规则
   *   "[data-mobile] .Nqubda_panel" 随即把它变成 fixed 浮层，
   *   盖住整个上半屏（实测 "[24,0,336,458]"），连「添加一个 API Key」
   *   对话框都被压在下面 → 用户看到半屏白块。
   *   同类隐患还有 "zGbnIq: ["title"]"（title 同样通用）。
   *
   *   豁免取消后，单名条目一律不参与推导；需要它们的场合改为在 JS 里
   *   用「当前 hash」直接定位（见 mpCurrentHash 相关辅助）。
   */
  function mpDeriveAliasMap() {
  	const { byHash: cur } = mpCollectCurrentClasses();
  	/* 预热期内只累积 "mpOfficialSeen"（上面的采集已完成），不认领任何映射。
  	 * 此时官方样式表还没注入齐，认领必然是误伤。见 [MP_ALIAS_WARMUP]。 */
  	if (mpAliasRound <= MP_ALIAS_WARMUP) return { map: {}, pairs: [] };
  	const owners = {};
  	for (const h of Object.keys(cur)) for (const l of Object.keys(cur[h])) (owners[l] || (owners[l] = [])).push(h);
  	const map = {};
  	const used = {};
  	for (const [oldHash, locals] of Object.entries(MP_LEGACY_HASHES)) {
  		/* ⚠️ 官方样式表里**曾经**出现过这个 hash ⇒ 它没有过期，不能拿它去认领别人。
  		 *
  		 * 2026-10-04 踩的坑："bOPqQW"（composer 遥测药丸）既登记在旧表里，
  		 * 又是官方当前仍在用的 hash。原逻辑无条件把它从候选池剔除，于是它
  		 * 丢掉了自己的 4 票证据，转而去认领了恰好共享 "label" + "root" 两个
  		 * 局部名的**无关** hash "O_Ebla"（2 票，刚好够门槛）——
  		 * 而 "O_Ebla_root" 是「执行了命令」分组头，"bOPqQW_root" 带
  		 * "justify-content: center"，于是那行标题被居中、不再左对齐。
  		 *
  		 * ⚠️ 判据必须用**累积**集合 "mpOfficialSeen" 而非当下快照：
  		 *   官方 CSS Module 按需注入，未渲染组件的规则此刻不在表里。
  		 *   用快照会让同一 hash 在「出现过 / 没出现」之间抖动，表现为
  		 *   **同一个页面上有的分组头中招、有的正常**（真机实测 22 个
  		 *   ".O_Ebla_root" 里只有 2 个被误加）。累积后结论稳定。
  		 *
  		 * ⚠️ 判据必须是「样式表 **或** DOM」两者取或，且 DOM 侧必须排除桥接自己补的类名：
  		 *   官方类名可能**先出现在 DOM、样式表随后才懒注入**。只看样式表会让
  		 *   没过期的 hash 在注入空窗期被判成过期 —— 真机日志实测：
  		 *     "CSSModule 别名桥接: YDXeBa→oz9t_a (2 个类名)"
  		 *   "YDXeBa" 是 workspace 的当前 hash（官方样式表里 36 个局部名齐全），
  		 *   却因为那一刻样式表还没到而被误判。这与 "bOPqQW→O_Ebla" 同病。 */
  		if (mpOfficialSeen.has(oldHash) || cur[oldHash]) continue;
  		const votes = {};
  		for (const l of locals) for (const h of (owners[l] || [])) votes[h] = (votes[h] || 0) + 1;
  		const ranked = Object.entries(votes).sort((a, b) => b[1] - a[1]);
  		if (ranked.length === 0) continue;
  		const [bestHash, hit] = ranked[0];
  		if (ranked.filter(([, v]) => v === hit).length > 1) continue;
  		/* 只认证据充分的映射：至少 2 个局部名同时命中同一个新 hash */
  		if (hit < 2) continue;
  		/* ⚠️ 光有 2 票远远不够 —— 必须要求**局部名集合高度重合**。
  		 *
  		 * 2026-10-04 真机日志实测的误伤："YDXeBa→oz9t_a (2 个类名)"。
  		 * "YDXeBa" 是 workspace 的**当前** hash（官方样式表里 36 个局部名齐全），
  		 * 只是它的组件（侧栏）那一刻还没渲染、样式表也还没懒注入，于是
  		 * 「当下无证据」被判成了「已过期」。它转去认领 "oz9t_a"，只因两者
  		 * 偶然共享 "time" + "chevron" 两个**通用**局部名，把 "YDXeBa_time"
  		 * 补到了折叠轮次的时间戳上。
  		 *
  		 * 真正的「过期」是**同一个组件换了 hash**，局部名集合基本不变
  		 * （"bOPqQW" 换成新 hash 时 anchor/label/pill/root 仍全在）。
  		 * 所以用**重合率**作判据：命中数要占双方较小集合的 60% 以上。
  		 *   · "YDXeBa"(10) vs "oz9t_a"(10)，命中 2 → 20% ⇒ 拒绝
  		 *   · 真换 hash 时局部名几乎全在 ⇒ 接近 100% ⇒ 采纳
  		 */
  		const otherSize = Object.keys(cur[bestHash] || {}).length;
  		const denom = Math.min(locals.length, otherSize);
  		if (denom === 0 || hit / denom < 0.6) continue;
  		/* 该新 hash 若自身也在旧表里登记过（即它也是官方当前 hash），
  		 * 说明它属于另一个组件，不该被认领。 */
  		if (MP_LEGACY_HASHES[bestHash]) continue;
  		if (used[bestHash]) continue;
  		used[bestHash] = true;
  		map[oldHash] = bestHash;
  	}
  	const pairs = [];
  	for (const [oldHash, newHash] of Object.entries(map)) {
  		for (const l of MP_LEGACY_HASHES[oldHash]) {
  			if (cur[newHash] && cur[newHash][l]) pairs.push([`${newHash}_${l}`, `${oldHash}_${l}`]);
  		}
  	}
  	return { map, pairs };
  }

  function mpRefreshAliasMap(force) {
  	if (!force && !mpAliasDirty) return;
  	const { map, pairs } = mpDeriveAliasMap();
  	mpAliasDirty = false;
  	const sig = pairs.map((p) => p.join('=')).sort().join(',');
  	if (sig === mpAliasSig) return;   // 映射未变 → 不重复打日志
  	mpAliasSig = sig;
  	mpPairIndex = new Map(pairs);
  	mpPruneStaleAliases();            // 撤掉上一轮误加、现已不再成立的类名
  	const desc = Object.entries(map).map(([o, n]) => `${o}→${n}`).join(' ');
  	if (desc) console.log('[mobile-polish] CSSModule 别名桥接:', desc, `(${pairs.length} 个类名)`);
  }

  /**
   * 撤掉「已补上去、但当前映射里不再成立」的旧类名。
   *
   * ⚠️ 为什么必须有（2026-10-04）：推导判据修正后，之前误加的类名**不会自己消失** ——
   *   它们留在 DOM 上，而 ".bOPqQW_root" 那条官方规则（"justify-content: center"）
   *   照旧生效，用户看到的错位不会随代码修复而恢复，除非重启页面。
   *   真机实测：修好判据后，已被误加的 2 个 ".O_Ebla_root" 仍然是居中状态。
   */
  function mpPruneStaleAliases() {
  	if (!mpAliasAdded.size) return;
  	const live = new Set(mpPairIndex ? mpPairIndex.values() : []);
  	const stale = [...mpAliasAdded].filter((c) => !live.has(c));
  	if (!stale.length) return;
  	for (const cls of stale) mpAliasAdded.delete(cls);
  	for (const el of document.querySelectorAll('[class]')) {
  		for (const cls of stale) if (el.classList.contains(cls)) el.classList.remove(cls);
  	}
  }

  /** 给单个元素补齐旧类名（幂等）。 */
  function mpAliasElement(el) {
  	if (!mpPairIndex || !mpPairIndex.size) return;
  	if (el.nodeType !== 1 || !el.classList) return;
  	for (const cls of el.classList) {
  		const alias = mpPairIndex.get(cls);
  		if (alias !== undefined && !el.classList.contains(alias)) {
  			el.classList.add(alias);
  			// 记下来：这是桥接自己补的，下一轮推导不能拿它当官方证据
  			mpAliasAdded.add(alias);
  		}
  	}
  }

  function mpAliasTree(root) {
  	if (!mpPairIndex || !mpPairIndex.size) return;
  	mpAliasElement(root);
  	if (root.querySelectorAll) for (const el of root.querySelectorAll('[class]')) mpAliasElement(el);
  }

  let mpAliasObserver = null;
  let retryTimer = 0;
  let active = false;
  function mpInstallAliasBridge() {
    if (active) return;
    active = true;
  	mpRefreshAliasMap(true);
  	// 官方样式表随组件懒加载，早期推导可能不全 → 持续重推；
  	// mpRefreshAliasMap 内部按指纹去重，映射没变时不会重复打日志。
  	let rounds = 0;
  	const retry = () => {
  		if (!active) return;
  		rounds++;
  		mpAliasRound++;                 // 预热期计数，见 [MP_ALIAS_WARMUP]
  		const before = mpAliasSig;
  		mpAliasDirty = true;
  		mpRefreshAliasMap(false);
  		if (mpAliasSig !== before) mpAliasTree(document.body);   // 映射变了才全树重扫
  		// 前 40 轮（约 24s）密集；之后放缓到 2s，长期兜底
  		if (rounds < 40) retryTimer = setTimeout(retry, 600);
  		else if (rounds < 200) retryTimer = setTimeout(retry, 2000);
  	};
  	retryTimer = setTimeout(retry, 300);
  	mpAliasTree(document.body);
  	if (mpAliasObserver) return;
  	mpAliasObserver = new MutationObserver((records) => {
  		mpRefreshAliasMap(false);
  		for (const r of records) {
  			if (r.type === 'childList') {
  				for (const n of r.addedNodes) if (n.nodeType === 1) mpAliasTree(n);
  			} else if (r.type === 'attributes') {
  				mpAliasElement(r.target);
  			}
  		}
  	});
  	mpAliasObserver.observe(document.documentElement, {
  		childList: true, subtree: true, attributes: true, attributeFilter: ['class'],
  	});
  }

  function mpTeardownAliasBridge() {
    active = false;
    clearTimeout(retryTimer);
    retryTimer = 0;
  	if (mpAliasObserver) { mpAliasObserver.disconnect(); mpAliasObserver = null; }
  	// ⚠️ 不能按 split('_')[0] 取 hash：'pI_x6G' 本身就含下划线。
  	// 用「旧类名全集」精确判断，只删我们加上的那些。
  	const ours = new Set();
  	for (const [oldHash, locals] of Object.entries(MP_LEGACY_HASHES)) for (const l of locals) ours.add(`${oldHash}_${l}`);
  	for (const el of document.querySelectorAll('[class]')) {
  		for (const cls of [...el.classList]) if (ours.has(cls)) el.classList.remove(cls);
  	}
  	mpPairIndex = null;
  	mpAliasDirty = true;
  	mpAliasSig = '';   // 重置指纹：否则重装时指纹相同会跳过重新赋值 mpPairIndex
  }

  return { mpInstallAliasBridge, mpTeardownAliasBridge };
}
