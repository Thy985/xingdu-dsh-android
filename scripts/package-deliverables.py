#!/usr/bin/env python3
"""Package the Android project and its already-built, runtime-inclusive development APK."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
dist.mkdir(exist_ok=True)
version = re.search(r"versionName\s+'([^']+)'", (root / 'app/build.gradle').read_text()).group(1)
apk = dist / f'Xingdu-DSH-{version}-arm64.apk'
source = dist / f'Xingdu-DSH-{version}-source.zip'
built = root / 'app/build/outputs/apk/debug/app-debug.apk'
metadata = json.loads((built.parent / 'output-metadata.json').read_text())
assert metadata['elements'][0]['versionName'] == version, 'Build version differs from source; rebuild first'
assert built.is_file() and built.stat().st_size > 100_000_000, 'Refusing to distribute an empty shell APK'
with zipfile.ZipFile(built) as candidate:
    manifest = (root / 'app/src/main/assets/runtime-manifest.json').read_bytes()
    assert candidate.read('assets/runtime-manifest.json') == manifest, 'Runtime manifest mismatch'
    runtime_digest = hashlib.sha256()
    with candidate.open('assets/runtime.zip') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            runtime_digest.update(chunk)
    assert runtime_digest.hexdigest() == json.loads(manifest)['sha256'], 'Packaged runtime hash mismatch'
    for relative in ['mobile/mobile.js', 'mobile/mobile.css']:
        assert candidate.read('assets/' + relative) == (root / 'app/src/main/assets' / relative).read_bytes(), relative
    for relative in ['package.json', 'lib/index.js', 'lib/client.js']:
        assert candidate.read('assets/addons/mobile-shell/' + relative) == (root / 'mobile-shell' / relative).read_bytes(), relative
shutil.copyfile(built, apk)
excluded = {'.git', '.gradle', 'build', 'runtime-build', 'dist', '__pycache__'}
with zipfile.ZipFile(source, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if any(part in excluded for part in relative.parts):
            continue
        if path.name == 'local.properties' or path.suffix in {'.pyc', '.jks', '.keystore', '.tmp'}:
            continue
        if path.is_file():
            archive.write(path, (Path(root.name) / relative).as_posix(),
                          compress_type=zipfile.ZIP_STORED if path.suffix == '.zip' else zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(source) as archive:
    assert archive.testzip() is None
    names = set(archive.namelist())
    for required in ['README.md', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar',
                     'app/src/main/assets/runtime.zip', 'app/src/main/assets/runtime-manifest.json',
                     'app/src/main/java/io/github/zhanxing/dshandroid/DshService.java',
                     'mobile-shell/src/client.js', 'mobile-shell/src/mobile.css',
                     'app/src/main/assets/addons/mobile-shell/lib/client.js']:
        assert f'{root.name}/{required}' in names, required
with (dist / 'SHA256SUMS.txt').open('w') as checksums:
    for path in (apk, source):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        checksums.write(f'{digest}  {path.name}\n')
        print(f'{path}\t{path.stat().st_size} bytes\tSHA256 {digest}')
print(f'Complete project directory: {root}')
