/**
 * 极简 QR 码编码器（零依赖，纯 ESM）。
 *
 * 用途：把一段短文本（典型是 6 位配对码，如 "A7K2M9"）编成 QR 码矩阵 / SVG，
 * 供手机端「扫电脑屏幕上的码」这条配对链路使用。
 *
 * ## 刻意不做的事，以及为什么
 *
 * - **只支持 byte 模式**：配对码是任意大小写字母数字，byte 模式一种就够。
 *   numeric / alphanumeric 模式省下的那点容量，对 ≤60 字符的输入没有意义，
 *   却要额外维护两套位打包逻辑。
 * - **只支持纠错等级 L**：配对码只在一臂距离内被扫，遮挡风险低；L 容量最大，
 *   反过来能让版本号尽量小（模块少 = 手机屏上更好扫）。
 * - **只支持版本 1–5**：容量上限 106 字节，覆盖 60 字符的目标绰绰有余。
 *   更关键的是**版本 1–5 的等级 L 都是单块**（ISO/IEC 18004 表 9），
 *   于是完全不需要写块交织（interleaving）—— 数据码字后面直接接纠错码字。
 *   这是本实现能压到 400 行以内的主要原因。
 * - **不写版本信息**：只有版本 ≥7 才需要在矩阵里放 18 位版本信息。
 *
 * ## 正确性
 *
 * 已与 npm 上成熟的 `qrcode` 包逐位比对（含掩码与格式信息），见仓库外的一次性
 * 验证脚本。要重新验证：拿 `QRCode.create(text, { errorCorrectionLevel: 'L' })`
 * 的 `modules.data`（扁平 Uint8Array，行优先，1 = 深色）与 `qrMatrix()` 对照。
 */

// ─────────────────────────── GF(256) ───────────────────────────

/**
 * 本原多项式 0x11D 的指数/对数表。
 *
 * 为什么要预计算：Reed–Solomon 的编码是纯 GF(256) 乘法循环，逐次现算
 * 「左移 + 条件异或」会让每个码字多出 8 次分支；查表后一次乘法就是两次数组索引。
 * 表长取 512 是为了让 `LOG[a] + LOG[b]` 最大 508 不越界，省掉一次取模。
 */
const GF_EXP = new Uint8Array(512);
const GF_LOG = new Uint8Array(256);
{
	let x = 1;
	for (let i = 0; i < 255; i++) {
		GF_EXP[i] = x;
		GF_LOG[x] = i;
		x <<= 1;
		if (x & 0x100) x ^= 0x11d; // 溢出即对本原多项式取模
	}
	for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
}

function gfMul(a, b) {
	if (a === 0 || b === 0) return 0;
	return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

/**
 * 生成多项式 g(x) = ∏(x − α^i)，i = 0..degree−1。
 * 返回「高次在前」的系数数组，长度 degree+1，且 g[0] === 1。
 */
function rsGenerator(degree) {
	let poly = [1];
	for (let i = 0; i < degree; i++) {
		const next = new Array(poly.length + 1).fill(0);
		for (let j = 0; j < poly.length; j++) {
			next[j] ^= poly[j]; // 乘 x：高次在前表示下索引不变
			next[j + 1] ^= gfMul(poly[j], GF_EXP[i]); // 乘 α^i：次数不变，索引 +1
		}
		poly = next;
	}
	return poly;
}

/** 系统码 RS：数据码字后接 degree 个纠错码字（多项式长除法取余）。 */
function rsEncode(data, degree) {
	const gen = rsGenerator(degree);
	const buf = new Uint8Array(data.length + degree);
	buf.set(data);
	for (let i = 0; i < data.length; i++) {
		const coef = buf[i];
		if (coef === 0) continue;
		for (let j = 0; j < gen.length; j++) buf[i + j] ^= gfMul(gen[j], coef);
	}
	return buf.subarray(data.length);
}

// ─────────────────────── 版本 / 容量表 ───────────────────────

/**
 * 版本 1–5、等级 L 的规格。`dataCodewords` 与 `ecCodewords` 取自
 * ISO/IEC 18004 表 9；`align` 是**对齐图案中心的候选坐标**（表 E.1）。
 *
 * 等级 L 在这五个版本里都是「1 块」，所以规格里没有块数列 —— 见文件头注释。
 */
const VERSIONS = [
	null,
	{ dataCodewords: 19, ecCodewords: 7, align: [] },
	{ dataCodewords: 34, ecCodewords: 10, align: [6, 18] },
	{ dataCodewords: 55, ecCodewords: 15, align: [6, 22] },
	{ dataCodewords: 80, ecCodewords: 20, align: [6, 26] },
	{ dataCodewords: 108, ecCodewords: 26, align: [6, 30] },
];

const MAX_VERSION = VERSIONS.length - 1;

/**
 * byte 模式下版本 1–9 的字符计数指示符是 8 位，模式指示符 4 位，
 * 合计 12 位 = 1.5 字节。向上取整到 2 字节，所以可用字节数就是
 * `dataCodewords - 2`。
 */
function maxBytesFor(version) {
	return VERSIONS[version].dataCodewords - 2;
}

// ───────────────────────── 数据编码 ─────────────────────────

/** 位缓冲：只做「追加 length 位」这一件事，够用且不会有位移溢出问题。 */
function makeBitBuffer() {
	const bits = [];
	return {
		bits,
		put(value, length) {
			for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
		},
	};
}

/**
 * 组装数据码字：模式指示符 + 字符计数 + 数据 + 终止符 + 字节对齐 + 填充码字。
 *
 * 填充码字固定交替 0xEC / 0x11（ISO/IEC 18004 8.4.9）—— 这两个值不是随便挑的，
 * 它们能拉长同色游程，让码面纹理更均匀。
 */
function buildDataCodewords(bytes, version) {
	const spec = VERSIONS[version];
	const bb = makeBitBuffer();

	bb.put(0b0100, 4); // 模式指示符：byte
	bb.put(bytes.length, 8); // 字符计数：版本 1–9 为 8 位
	for (let i = 0; i < bytes.length; i++) bb.put(bytes[i], 8);

	const capacityBits = spec.dataCodewords * 8;

	// 终止符最多 4 个 0，且不能超出容量
	for (let i = 0; i < 4 && bb.bits.length < capacityBits; i++) bb.bits.push(0);
	// 补 0 到字节边界（终止符被容量截断时，这一步也顺带把最后一个字节补满）
	while (bb.bits.length % 8 !== 0) bb.bits.push(0);

	const codewords = [];
	for (let i = 0; i < bb.bits.length; i += 8) {
		let v = 0;
		for (let j = 0; j < 8; j++) v = (v << 1) | bb.bits[i + j];
		codewords.push(v);
	}

	const PAD = [0xec, 0x11];
	for (let k = 0; codewords.length < spec.dataCodewords; k++) codewords.push(PAD[k % 2]);

	return codewords;
}

// ───────────────────────── 掩码 ─────────────────────────

/** 8 种掩码条件函数（ISO/IEC 18004 表 10），返回 true 表示该位置要翻转。 */
const MASK_FUNCS = [
	(r, c) => (r + c) % 2 === 0,
	(r) => r % 2 === 0,
	(r, c) => c % 3 === 0,
	(r, c) => (r + c) % 3 === 0,
	(r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0,
	(r, c) => ((r * c) % 2) + ((r * c) % 3) === 0,
	(r, c) => (((r * c) % 2) + ((r * c) % 3)) % 2 === 0,
	(r, c) => (((r + c) % 2) + ((r * c) % 3)) % 2 === 0,
];

// ───────────────────── 罚分（选最优掩码） ─────────────────────

const PENALTY_N1 = 3;
const PENALTY_N2 = 3;
const PENALTY_N3 = 40;
const PENALTY_N4 = 10;

/** 规则 3 要找的两个 11 位模式：1011101 前后各带 4 个浅色模块。 */
const N3_PATTERNS = [
	[1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0],
	[0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1],
];

/**
 * 标准 4 条罚分规则，分数越低越好。
 *
 * 注意这里对规则 3 用的是**标准**的 11 位模式（含两侧各 4 个浅色模块），
 * 而不是某些实现里简化的 7 位 `1011101` —— 后者会把「本来紧邻深色模块、
 * 视觉上并不构成定位图案假象」的位置也罚分，选出的掩码会偏。
 */
function computePenalty(modules, size) {
	let score = 0;

	// 规则 1：行 / 列里长度 ≥5 的同色连续段，罚 3 + (长度 − 5)
	for (let r = 0; r < size; r++) {
		let run = 1;
		for (let c = 1; c < size; c++) {
			if (modules[r][c] === modules[r][c - 1]) run++;
			else {
				if (run >= 5) score += PENALTY_N1 + run - 5;
				run = 1;
			}
		}
		if (run >= 5) score += PENALTY_N1 + run - 5;
	}
	for (let c = 0; c < size; c++) {
		let run = 1;
		for (let r = 1; r < size; r++) {
			if (modules[r][c] === modules[r - 1][c]) run++;
			else {
				if (run >= 5) score += PENALTY_N1 + run - 5;
				run = 1;
			}
		}
		if (run >= 5) score += PENALTY_N1 + run - 5;
	}

	// 规则 2：每个 2×2 同色块罚 3
	for (let r = 0; r < size - 1; r++) {
		for (let c = 0; c < size - 1; c++) {
			const v = modules[r][c];
			if (v === modules[r][c + 1] && v === modules[r + 1][c] && v === modules[r + 1][c + 1]) {
				score += PENALTY_N2;
			}
		}
	}

	// 规则 3：出现 10111010000 / 00001011101 各罚 40
	const matches = (a, b, c2, d, e, f, g, h, i, j, k, row, col) => {
		for (const p of N3_PATTERNS) {
			if (
				a === !!p[0] && b === !!p[1] && c2 === !!p[2] && d === !!p[3] && e === !!p[4] &&
				f === !!p[5] && g === !!p[6] && h === !!p[7] && i === !!p[8] && j === !!p[9] &&
				k === !!p[10]
			) {
				score += PENALTY_N3;
				return;
			}
		}
	};
	for (let r = 0; r < size; r++) {
		for (let c = 0; c + 11 <= size; c++) {
			matches(
				modules[r][c], modules[r][c + 1], modules[r][c + 2], modules[r][c + 3],
				modules[r][c + 4], modules[r][c + 5], modules[r][c + 6], modules[r][c + 7],
				modules[r][c + 8], modules[r][c + 9], modules[r][c + 10], r, c,
			);
		}
	}
	for (let c = 0; c < size; c++) {
		for (let r = 0; r + 11 <= size; r++) {
			matches(
				modules[r][c], modules[r + 1][c], modules[r + 2][c], modules[r + 3][c],
				modules[r + 4][c], modules[r + 5][c], modules[r + 6][c], modules[r + 7][c],
				modules[r + 8][c], modules[r + 9][c], modules[r + 10][c], r, c,
			);
		}
	}

	// 规则 4：深色占比每偏离 50% 满 5% 罚 10
	let dark = 0;
	for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) if (modules[r][c]) dark++;
	const total = size * size;
	score += Math.floor(Math.abs((dark * 100) / total - 50) / 5) * PENALTY_N4;

	return score;
}

// ─────────────────── 格式信息（BCH 15,5） ───────────────────

/** 数据位 5 位：高 2 位是纠错等级（L = 01），低 3 位是掩码号。 */
const EC_LEVEL_L = 0b01;
const G15 = 0b10100110111; // x^10+x^8+x^5+x^4+x^2+x+1
const G15_MASK = 0b101010000010010; // 0x5412，标准规定的固定异或值

function bitLength(v) {
	let n = 0;
	while (v !== 0) {
		n++;
		v >>>= 1;
	}
	return n;
}

/**
 * 生成 15 位格式信息。返回值的**第 0 位是最低位**，放置时按位号取。
 *
 * 用「先左移 10 位再反复异或生成多项式」做 BCH 除法，比逐位循环少一层嵌套，
 * 也更容易和标准里的多项式对上。
 */
function formatBits(mask) {
	const data = (EC_LEVEL_L << 3) | mask;
	let rem = data << 10;
	while (bitLength(rem) >= 11) rem ^= G15 << (bitLength(rem) - 11);
	return ((data << 10) | rem) ^ G15_MASK;
}

// ───────────────────────── 矩阵构建 ─────────────────────────

function makeGrid(size, fill) {
	const g = new Array(size);
	for (let i = 0; i < size; i++) g[i] = new Array(size).fill(fill);
	return g;
}

/**
 * 画一个定位图案，连同它外侧一圈浅色分隔符。
 *
 * 循环范围刻意取 −1..7（即 9×9）而不是 0..6：分隔符必须把这一圈**明确写成浅色**，
 * 否则「未占用」的语义会和「浅色」混起来，后续对齐图案的「已占用就跳过」判断就失效了。
 * 越界坐标直接跳过，三个角的图案因此各自被矩阵边界裁成 8×8。
 */
function placeProbe(modules, func, size, r0, c0) {
	for (let r = -1; r <= 7; r++) {
		for (let c = -1; c <= 7; c++) {
			const rr = r0 + r;
			const cc = c0 + c;
			if (rr < 0 || rr >= size || cc < 0 || cc >= size) continue;
			const inside = r >= 0 && r <= 6 && c >= 0 && c <= 6;
			const dark = inside &&
				(r === 0 || r === 6 || c === 0 || c === 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4));
			modules[rr][cc] = dark;
			func[rr][cc] = true;
		}
	}
}

/** 5×5 对齐图案：外圈 + 正中心深色，中间一圈浅色。 */
function placeAlign(modules, func, cr, cc) {
	for (let r = -2; r <= 2; r++) {
		for (let c = -2; c <= 2; c++) {
			modules[cr + r][cc + c] = Math.max(Math.abs(r), Math.abs(c)) !== 1;
			func[cr + r][cc + c] = true;
		}
	}
}

/** 该对齐图案中心是否落在三个定位图案的区域内（含分隔符），落进去就不画。 */
function alignOverlapsProbe(r, c, size) {
	return (r <= 7 && c <= 7) || (r <= 7 && c >= size - 8) || (r >= size - 8 && c <= 7);
}

/**
 * 标记格式信息占用的模块（第一份 + 第二份 + 那个固定的暗模块）。
 *
 * 必须在填充数据**之前**调用：数据填充靠 `func` 判断哪些位置可写，
 * 漏标的话格式信息位会被数据覆盖，扫出来的码纠错等级/掩码号全错。
 */
function markFormatArea(func, size) {
	for (let i = 0; i <= 8; i++) {
		func[8][i] = true;
		func[i][8] = true;
	}
	for (let i = 0; i < 8; i++) {
		func[size - 1 - i][8] = true;
		func[8][size - 1 - i] = true;
	}
	func[size - 8][8] = true;
}

/**
 * 按标准方向把码字位铺进矩阵，并就地套用掩码。
 *
 * 方向：从右下角起、两列一组向左推进，组内先右列后左列，组间上下交替（首组向上）。
 * 第 6 列是竖向 timing 图案，整列跳过 —— 跳法是「本组左移到第 5 列」，
 * 这样第 5、4 列仍成一组，后面继续按 −2 步进，不会打乱分组奇偶。
 *
 * 数据位用完后剩余的可写位置填 false 再套掩码，也就是最终等于掩码函数值 ——
 * 标准要求这些位是 0，掩码对它们照常生效。
 */
function mapData(modules, func, size, codewords, mask) {
	const maskFn = MASK_FUNCS[mask];
	const totalBits = codewords.length * 8;
	let bitIndex = 0;

	const nextBit = () => {
		if (bitIndex >= totalBits) return 0;
		const v = (codewords[bitIndex >> 3] >>> (7 - (bitIndex & 7))) & 1;
		bitIndex++;
		return v;
	};

	let upward = true;
	for (let col = size - 1; col > 0; col -= 2) {
		if (col === 6) col = 5;
		for (let i = 0; i < size; i++) {
			const row = upward ? size - 1 - i : i;
			for (const c of [col, col - 1]) {
				if (func[row][c]) continue;
				let dark = nextBit() === 1;
				if (maskFn(row, c)) dark = !dark;
				modules[row][c] = dark;
			}
		}
		upward = !upward;
	}
}

/**
 * 放置 15 位格式信息（两副本）与那个固定的暗模块。
 *
 * 第一副本贴着左上定位图案（列 8 竖排 + 行 8 横排），第二副本拆成
 * 右上横排（位 0–7）与左下竖排（位 8–14）。位置按 ISO/IEC 18004 图 25。
 *
 * ⚠️ 位到坐标的分配**不是**「竖排拿低位、横排拿高位」那种对称写法，两副本各自
 * 绕了角。这里最容易错的两处：
 * 1. 第一副本的位 8 落在 `(8,7)`、位 9–14 从 `(8,5)` 向左退到 `(8,0)`，
 *    也就是**横排是倒着数的**；
 * 2. 第二副本的横排拿的是**位 0–7**（列 `size-1` 向左到 `size-8`），竖排才是
 *    位 8–14（行 `size-7` 向下到 `size-1`）。写反了整张码面的格式信息会镜像，
 *    扫出来的纠错等级与掩码号全错，而矩阵看起来仍然「像个 QR 码」。
 *    固定暗模块 `(size-8, 8)` 与竖排**相邻但不重叠**，正好补上竖排缺的那一行。
 */
function placeFormat(modules, size, mask) {
	const bits = formatBits(mask);
	const bit = (i) => ((bits >>> i) & 1) === 1;

	// 副本 1：左上
	for (let i = 0; i <= 5; i++) modules[i][8] = bit(i);
	modules[7][8] = bit(6);
	modules[8][8] = bit(7);
	modules[8][7] = bit(8);
	for (let i = 9; i <= 14; i++) modules[8][14 - i] = bit(i);

	// 副本 2：右上横排（位 0–7）+ 左下竖排（位 8–14）
	for (let i = 0; i <= 7; i++) modules[8][size - 1 - i] = bit(i);
	for (let i = 8; i <= 14; i++) modules[size - 15 + i][8] = bit(i);

	modules[size - 8][8] = true; // 固定暗模块，不属于格式信息
}

/** 用指定掩码完整构建一次矩阵（功能图案 → 格式信息占位 → 数据 → 格式信息）。 */
function buildWithMask(version, codewords, mask) {
	const size = version * 4 + 17;
	const modules = makeGrid(size, false);
	const func = makeGrid(size, false);

	placeProbe(modules, func, size, 0, 0);
	placeProbe(modules, func, size, size - 7, 0);
	placeProbe(modules, func, size, 0, size - 7);

	// 对齐图案要在 timing **之前**画，且靠 func 判重 —— 见 alignOverlapsProbe 的说明。
	for (const r of VERSIONS[version].align) {
		for (const c of VERSIONS[version].align) {
			if (alignOverlapsProbe(r, c, size)) continue;
			placeAlign(modules, func, r, c);
		}
	}

	// timing：第 6 行 / 第 6 列，偶数下标深色。已被占用的位置（对齐图案）跳过。
	for (let i = 8; i < size - 8; i++) {
		if (!func[6][i]) {
			modules[6][i] = i % 2 === 0;
			func[6][i] = true;
		}
		if (!func[i][6]) {
			modules[i][6] = i % 2 === 0;
			func[i][6] = true;
		}
	}

	modules[size - 8][8] = true; // 固定暗模块
	func[size - 8][8] = true;

	markFormatArea(func, size);
	mapData(modules, func, size, codewords, mask);
	placeFormat(modules, size, mask);

	return modules;
}

// ───────────────────────── 对外 API ─────────────────────────

/**
 * 把文本编成 QR 码矩阵。
 *
 * @param {string} text 要编码的字符串（按 UTF-8 取字节）
 * @returns {boolean[][]} `true` = 深色模块；尺寸 = version*4+17；**不含静默区**
 * @throws {Error} 输入超过版本 5 等级 L 的容量（106 字节）时
 */
export function qrMatrix(text) {
	const bytes = new TextEncoder().encode(String(text));

	let version = 0;
	for (let v = 1; v <= MAX_VERSION; v++) {
		if (bytes.length <= maxBytesFor(v)) {
			version = v;
			break;
		}
	}
	if (version === 0) {
		throw new Error(
			`qr: 文本过长（UTF-8 ${bytes.length} 字节），版本 1–5 等级 L 最多 ${maxBytesFor(MAX_VERSION)} 字节`,
		);
	}

	const spec = VERSIONS[version];
	const dataCodewords = buildDataCodewords(bytes, version);
	const all = new Uint8Array(dataCodewords.length + spec.ecCodewords);
	all.set(dataCodewords);
	all.set(rsEncode(Uint8Array.from(dataCodewords), spec.ecCodewords), dataCodewords.length);

	// 等级 L 在版本 1–5 都是单块，所以这里没有交织，直接整体当码字序列。
	const size = version * 4 + 17;
	let bestMask = 0;
	let bestScore = Infinity;
	for (let mask = 0; mask < 8; mask++) {
		const score = computePenalty(buildWithMask(version, all, mask), size);
		if (score < bestScore) {
			bestScore = score;
			bestMask = mask;
		}
	}

	return buildWithMask(version, all, bestMask);
}

/**
 * 生成完整 SVG 字符串：单个 `<path>` 画全部深色模块，背景留白。
 *
 * 为什么用路径而不是 `<rect>` 阵列：一个 37×37 的 V5 码有 1369 个模块，
 * 逐模块 `<rect>` 会让字符串膨胀十几倍，而路径可以用连续的 `h` 段合并整行。
 *
 * `viewBox` 用**模块坐标**（含静默区），`width`/`height` 才是像素尺寸 ——
 * 这样 `scale` 只是显示缩放，不参与坐标计算，不会引入浮点累积误差。
 *
 * @param {string} text 要编码的字符串
 * @param {{ margin?: number, scale?: number }} [options]
 *   `margin` = 静默区模块数（默认 4，标准要求至少 4），`scale` = 每模块像素数（默认 1）
 * @returns {string} 完整 SVG
 */
export function qrSvg(text, options = {}) {
	const margin = options.margin ?? 4;
	const scale = options.scale ?? 1;
	const matrix = qrMatrix(text);
	const n = matrix.length;
	const dim = n + margin * 2;

	const parts = [];
	for (let r = 0; r < n; r++) {
		let c = 0;
		while (c < n) {
			if (!matrix[r][c]) {
				c++;
				continue;
			}
			// 把同一行里连续的深色模块合成一个水平段
			let run = 1;
			while (c + run < n && matrix[r][c + run]) run++;
			parts.push(`M${c + margin} ${r + margin}h${run}v1h-${run}z`);
			c += run;
		}
	}

	const px = dim * scale;
	return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${dim} ${dim}" width="${px}" height="${px}" shape-rendering="crispEdges">` +
		`<path d="${parts.join('')}" fill="#000"/></svg>`;
}

/**
 * 二维码的 `data:` URI，**给 `<img src>` 用**。
 *
 * ## 为什么要多这一层（外部审计 M6）
 *
 * 前端原来写的是 `dangerouslySetInnerHTML: { __html: pair.svg }` —— 那是全仓
 * **唯一**一处原始 HTML sink。审计已经核对过「当前不可注入」（`qrSvg` 只把文本
 * 编码成布尔矩阵，SVG 里只有 `M/h/v/z` 与整数，`pairCode` 本身不进模板），
 * 但输入是**网络对端**的字段：`qr.js` 哪天加个水印/注释/自定义模块样式，
 * 立刻就是同源 XSS —— 而 XSS 直接绕过所有准入检查（令牌门、fence 全部失效）。
 *
 * 换成 `<img src="data:image/svg+xml;base64,…">` 之后，SVG 变成**图片**而不进
 * DOM 解析，这条通道从结构上关掉了。代价只是多一次 base64（37×37 的码约 1 KB）。
 *
 * ⚠️ 本模块只在 **host 侧**（Node）被 `pair.js` 引入，`client.js` 不 import 它，
 * 所以这里用 `Buffer` 是安全的。
 *
 * @param {string} text 要编码的字符串
 * @param {{ margin?: number, scale?: number }} [options] 同 {@link qrSvg}
 * @returns {string} `data:image/svg+xml;base64,…`
 */
export function qrDataUri(text, options = {}) {
	return `data:image/svg+xml;base64,${Buffer.from(qrSvg(text, options), 'utf8').toString('base64')}`;
}
