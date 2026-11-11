import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// The child performs explicit input validation before any test. No fallback or skip.
const result = spawnSync(process.execPath, ['--test', fileURLToPath(new URL('./market.mjs', import.meta.url))], {
  env: { ...process.env, DSH_MARKET_REAL_ENGINE: '1' },
  stdio: 'inherit',
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
