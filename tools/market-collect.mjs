#!/usr/bin/env node
/**
 * DSH Android 插件市场 —— 目录采集器（构建期 CLI）。
 *
 * 产出 `lib/seed.json`：随插件包打进 APK 的**离线种子**。真机上宿主半
 * （`lib/index.js`）优先实时扫 npm，网络不可达时回落到这份种子。
 *
 * 采集逻辑全部在 `plugins/android/components/dsh-android-market/lib/collect.mjs` 里，
 * 宿主半用的是**同一个模块** —— 所以种子与实时结果结构完全一致，
 * 客户端半不需要写两套解析。
 *
 * ## 为什么市场要同时有「实时」和「种子」两条路
 *
 *   · 只有实时 —— 断网/代理不通时用户看到空列表，而插件本身明明装好了。
 *   · 只有种子 —— 刚发布的插件要等 App 发新版才能出现在市场里，那就不叫市场了。
 *
 * 两条都留，实时优先，种子兜底。
 *
 * ## 用法
 *
 *   node tools/market-collect.mjs                     # 写进插件包的 lib/seed.json
 *   node tools/market-collect.mjs --out D:\x.json     # 指定输出
 *   node tools/market-collect.mjs --stdout            # 只打印，不写文件（看数用）
 *   node tools/market-collect.mjs --registry https://registry.npmjs.org
 *
 * 退出码：0 = 成功（哪怕 0 个插件），1 = 网络/解析失败。
 */

import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { collectCatalog } from '../plugins/android/components/dsh-android-market/lib/collect.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');

/** 种子默认落点：与 `lib/index.js` 同目录，它按 `./seed.json` 相对解析。 */
const DEFAULT_OUT = path.join(ROOT, 'plugins/android/components/dsh-android-market/lib/seed.json');

/** 解析命令行参数。 */
function parseArgs(argv) {
	const out = { out: null, stdout: false, registry: undefined };
	for (let i = 0; i < argv.length; i++) {
		const a = argv[i];
		if (a === '--stdout') out.stdout = true;
		else if (a === '--out') out.out = argv[++i];
		else if (a === '--registry') out.registry = argv[++i].replace(/\/+$/, '');
		else if (a === '--help' || a === '-h') {
			console.log(
				'用法: node tools/market-collect.mjs [--out <path>] [--stdout] [--registry <url>]',
			);
			process.exit(0);
		} else {
			console.error(`未知参数: ${a}`);
			process.exit(1);
		}
	}
	return out;
}

/** 主流程。 */
async function main() {
	const args = parseArgs(process.argv.slice(2));

	console.error(`扫描 registry: ${args.registry ?? 'https://registry.npmjs.org'}（默认）`);
	console.error('  · dshapp-plugin（社区）');
	console.error('  · dshapp-plugin-featured（精选）');

	const payload = await collectCatalog({
		registry: args.registry,
		onNote: (message) => console.error(`⚠️  ${message}`),
		onProgress: (done, total) => process.stderr.write(`\r  ${done}/${total}`),
	});
	process.stderr.write('\n');

	if (payload.failed && payload.failed.length > 0) {
		console.error(`⚠️  ${payload.failed.length} 个包的 manifest 拉取失败，已记入 failed 字段`);
	}

	const text = JSON.stringify(payload, null, 2) + '\n';

	if (args.stdout) {
		process.stdout.write(text);
	} else {
		const out = args.out ?? DEFAULT_OUT;
		await writeFile(out, text, 'utf8');
		console.error(`已写入 ${out}`);
	}

	console.error(
		`完成: 精选 ${payload.counts.featured} 个，社区 ${payload.counts.community} 个` +
			(payload.truncated ? '（⚠️ 目录可能被截断）' : ''),
	);
}

main().catch((err) => {
	console.error(`采集失败: ${err.message}`);
	process.exit(1);
});
