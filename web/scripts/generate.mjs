import { cpSync, existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const python =
  process.env.PYTHON ??
  (existsSync(root + '.venv/bin/python') ? root + '.venv/bin/python' : 'python3');
for (const [command, args] of [
  ['make', ['-C', root, 'generate-web-api']],
  [python, [root + 'web/scripts/generate-catalog.py']],
]) {
  const result = spawnSync(command, args, { stdio: 'inherit' });
  if (result.status !== 0) process.exit(result.status ?? 1);
}
cpSync(root + 'generated/web-api', root + 'web/src/lib/generated/api', { recursive: true });
