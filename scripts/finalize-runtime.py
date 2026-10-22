#!/usr/bin/env python3
"""Finalize an extracted runtime; no network or user credentials are used."""
import argparse
import hashlib
import json
import pathlib
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('root', type=pathlib.Path)
parser.add_argument('output', type=pathlib.Path)
args = parser.parse_args()
root = args.root.resolve()
nm = root / 'lib/node_modules/@deepseek-ai'

flock = nm / 'node-addon-system/lib/flock.js'
flock.write_text('''// Android/Bionic loader; retains upstream syscall error and descriptor ownership semantics.
import { createRequire } from 'node:module';
import { getSystemErrorName } from 'node:util';
const require = createRequire(import.meta.url);
let binding;
export async function tryLockExclusive(fd) {
  const errno = await new Promise((resolve) => {
    (binding ??= require('./system.node')).tryLock(fd, resolve);
  });
  if (errno === 0) return;
  const code = getSystemErrorName(-errno);
  throw Object.assign(new Error(`${code}: flock failed`), { code, errno, syscall: 'flock' });
}
''')

session = nm / 'dsh-session-persistence-jsonl/lib/index.js'
s = session.read_text()
old = 'import { rename as link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";'
if old in s:
    s = s.replace(old, '''import { link as androidNativeLink, copyFile as androidCopyFile, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";
import { constants as androidFsConstants } from "node:fs";
// Android forbids hard links. Preserve source and reject existing destinations.
// COPYFILE_EXCL is not atomic publication: crash-during-copy remains a documented limitation.
async function link(source, target) {
  try { await androidNativeLink(source, target); }
  catch (error) {
    if (!["EACCES", "EPERM", "ENOTSUP", "EXDEV", "EMLINK", "ENOSYS"].includes(error?.code)) throw error;
    await androidCopyFile(source, target, androidFsConstants.COPYFILE_EXCL);
  }
}''')
    session.write_text(s)
else:
    assert 'COPYFILE_EXCL' in s and 'rename as link' not in s, 'Unexpected session persistence layout'

fs = nm / 'dsh-fs-local/lib/index.js'
s = fs.read_text()
old = 'await rename(tempPath, absolutePath);\n\t\t\t} else {\n\t\t\t\tawait linkFile(tempPath, absolutePath);'
if old in s:
    s = s.replace('import { chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from "node:fs/promises";', 'import { copyFile as androidCopyFile, chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from "node:fs/promises";\nimport { constants as androidFsConstants } from "node:fs";')
    s = s.replace('// no-overwrite-create semantics with an lstat guard + atomic rename.', '// no-overwrite-create semantics with COPYFILE_EXCL (not atomic publication).')
    s = s.replace(old, 'await androidCopyFile(tempPath, absolutePath, androidFsConstants.COPYFILE_EXCL);\n} else {\nawait linkFile(tempPath, absolutePath);')
    fs.write_text(s)
else:
    assert 'androidCopyFile(tempPath, absolutePath' in s, 'Unexpected guarded create layout'

for name in ('su', 'sudo', 'doas', 'tsu'):
    assert not (root / 'bin' / name).exists(), f'Privilege helper found: {name}'
assert json.loads((nm / 'dsh/package.json').read_text())['version'] == '0.2.1-rc.2'
args.output.parent.mkdir(parents=True, exist_ok=True)
temp = args.output.with_suffix('.zip.tmp')
with zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for path in sorted(root.rglob('*')):
        assert not path.is_symlink(), f'Symlink must be resolved before packaging: {path}'
        if path.is_file():
            z.write(path, path.relative_to(root).as_posix())
temp.replace(args.output)
h = hashlib.sha256(args.output.read_bytes()).hexdigest()
manifest = {
    'version': '0.1.0-dev', 'dshVersion': '0.2.1-rc.2', 'abi': 'arm64-v8a',
    'runtimeZip': args.output.name, 'sha256': h, 'node': '24.18.0',
    'build': 'Termux aarch64 Bionic; Android NDK 27.2.12479018; API 26',
    'flock': 'Real asynchronous upstream flock.c N-API addon; errno/syscall contract retained',
    'pty': 'Native dsh_pty_exec helper',
    'publicationLimit': 'COPYFILE_EXCL preserves source and no-clobber, but is not atomic crash-safe publication'
}
args.output.with_name('runtime-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'output': str(args.output), 'bytes': args.output.stat().st_size, 'sha256': h}, ensure_ascii=False))
