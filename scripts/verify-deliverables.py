#!/usr/bin/env python3
"""Verify source/APK deliverables after a completed build and package operation."""
from pathlib import Path
import hashlib
import json
import re
import zipfile

root = Path(__file__).resolve().parents[1]
version = re.search(r"versionName\s+'([^']+)'", (root / 'app/build.gradle').read_text()).group(1)
dist = root / 'dist'
apk = dist / f'Xingdu-DSH-{version}-arm64.apk'
source = dist / f'Xingdu-DSH-{version}-source.zip'
required = [
    'README.md', 'app/build.gradle', 'app/src/main/AndroidManifest.xml',
    'app/src/main/java/io/github/zhanxing/dshandroid/SafTransferController.java',
    'app/src/main/java/io/github/zhanxing/dshandroid/WorkspaceTransfers.java',
    'app/src/main/java/io/github/zhanxing/dshandroid/MainActivity.java',
    'app/src/test/java/io/github/zhanxing/dshandroid/WorkspaceTransfersTest.java',
    'app/src/androidTest/java/io/github/zhanxing/dshandroid/SafTransferTest.java',
    'app/src/androidTest/java/io/github/zhanxing/dshandroid/SafFixtureProvider.java',
    'app/src/androidTest/AndroidManifest.xml', 'scripts/verify-deliverables.py',
    'tests/validation/0.1.7/summary.json',
    'app/src/main/java/io/github/zhanxing/dshandroid/RemoteActivity.java',
    'app/src/main/java/io/github/zhanxing/dshandroid/RemoteUrlPolicy.java',
    'branding/xingdu-icon.png',
    'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
    'app/src/test/java/io/github/zhanxing/dshandroid/RemoteUrlPolicyTest.java',
    'app/src/main/res/xml/network_security_config.xml',
]
with zipfile.ZipFile(source) as archive:
    for relative in required:
        assert archive.read(f'{root.name}/{relative}') == (root / relative).read_bytes(), relative
    assert not any(Path(n).name == 'local.properties' or Path(n).suffix in {'.jks', '.keystore'}
                   for n in archive.namelist()), 'Private local configuration or signing key bundled'
    assert archive.testzip() is None, 'Corrupt source ZIP'
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, 'Corrupt APK'
    dex = b''.join(archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n))
    assert b'SafFixtureProvider' not in dex, 'Test provider leaked into product APK'
    assert b'SafTransferController' in dex, 'Native transfer controller missing from product APK'
    assert b'RemoteActivity' in dex and b'RemoteUrlPolicy' in dex, 'Remote mode missing from product APK'
    assert b'exportSnapshot' in dex, 'Original-folder export missing from product APK'
    assert any(n.startswith('res/mipmap') and 'ic_launcher' in n for n in archive.namelist()), 'Launcher icon missing'
assert apk.read_bytes() == (root / 'app/build/outputs/apk/debug/app-debug.apk').read_bytes(), 'APK differs from latest build'
outputs = []
for path in [apk, source, dist / 'SHA256SUMS.txt']:
    assert path.is_file() and path.stat().st_size > 0
    outputs.append({'path': str(path), 'bytes': path.stat().st_size,
                    'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
for line in (dist / 'SHA256SUMS.txt').read_text().splitlines():
    digest, filename = line.split(None, 1)
    assert hashlib.sha256((dist / filename.strip()).read_bytes()).hexdigest() == digest
print(json.dumps(outputs, ensure_ascii=False, indent=2))
print('PASS: current source matches archive; product matches latest build; ZIP integrity and SHA-256 checks passed; test provider absent from product dex.')
