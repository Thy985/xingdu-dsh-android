// Run on Android with PREFIX=<installed runtime> node --expose-internals runtime-smoke.mjs.
// This uses only temporary local files/processes; no API key or model request is needed.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, open, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const prefix = process.env.PREFIX;
assert(prefix, 'PREFIX is required');
assert.equal(process.platform, 'android', 'Must run on Android/Bionic, not a Linux mock');
const require = createRequire(join(prefix, 'lib/node_modules/runtime-smoke.cjs'));
const folder = await mkdtemp(join(tmpdir(), 'dsh-android-smoke-'));
const result = { platform: process.platform, arch: process.arch, node: process.version, checks: [] };
const check = (name) => { result.checks.push(name); console.log(`PASS ${name}`); };

try {
  const flock = await import(pathToFileURL(join(prefix, 'lib/node_modules/@deepseek-ai/node-addon-system/lib/flock.js')));
  const filename = join(folder, 'lease');
  const first = await open(filename, 'w+');
  const second = await open(filename, 'r+');
  try {
    await flock.tryLockExclusive(first.fd);
    await assert.rejects(flock.tryLockExclusive(second.fd), (error) => ['EAGAIN', 'EWOULDBLOCK'].includes(error.code));
    check('native-flock-contention-rejected');
    await assert.rejects(flock.tryLockExclusive(-1), (error) => error.code === 'EBADF');
    check('native-flock-invalid-descriptor-rejected');
  } finally {
    await first.close();
  }
  try {
    await flock.tryLockExclusive(second.fd);
    check('native-flock-released-on-close');
  } finally {
    await second.close();
  }

  const bash = spawnSync(join(prefix, 'bin/bash'), ['--noprofile', '--norc', '-c', 'printf DSH_ANDROID_BASH_OK'], {
    encoding: 'utf8', timeout: 10000, cwd: folder,
  });
  assert.equal(bash.status, 0, bash.stderr);
  assert.equal(bash.stdout, 'DSH_ANDROID_BASH_OK');
  check('bionic-bash-subprocess');

  await writeFile(join(folder, 'sample.txt'), 'DSH_ANDROID_RG_OK\n', { mode: 0o600 });
  const rg = spawnSync(join(prefix, 'bin/rg'), ['DSH_ANDROID_RG_OK', join(folder, 'sample.txt')], {
    encoding: 'utf8', timeout: 10000,
  });
  assert.equal(rg.status, 0, rg.stderr);
  assert.match(rg.stdout, /DSH_ANDROID_RG_OK/);
  check('bionic-ripgrep-subprocess');

  const pty = require('node-pty');
  await new Promise((resolve, reject) => {
    const terminal = pty.spawn(join(prefix, 'bin/bash'), ['--noprofile', '--norc', '-c', 'printf DSH_ANDROID_PTY_OK'], {
      name: 'xterm-256color', cols: 80, rows: 24, cwd: folder, env: process.env,
    });
    let output = '';
    const timeout = setTimeout(() => { terminal.kill(); reject(new Error('PTY timed out')); }, 15000);
    terminal.onData((data) => { output += data; });
    terminal.onExit((event) => {
      clearTimeout(timeout);
      try {
        assert.equal(event.exitCode, 0);
        assert.match(output, /DSH_ANDROID_PTY_OK/);
        resolve();
      } catch (error) { reject(error); }
    });
  });
  check('native-pty-roundtrip');

  const cli = JSON.parse(await readFile(join(prefix, 'lib/node_modules/@deepseek-ai/dsh/package.json'), 'utf8'));
  assert.equal(cli.version, '0.2.1-rc.2');
  check('official-dsh-version-pinned');
  console.log(JSON.stringify({ success: true, ...result }));
} finally {
  await rm(folder, { recursive: true, force: true });
}
