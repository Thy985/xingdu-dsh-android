#!/usr/bin/env python3
"""Static verifier for the Android runtime ZIP; never executes runtime binaries.

Layouts (see RuntimeLayoutPolicy.java / build-runtime.sh --split-jni):
  data  (legacy): binaries inside the zip, exec'd from app data.
  split (0.2.0+): zip carries data only; the five native components live in a
  jniLibs directory (pass --jni-dir) and land in nativeLibraryDir.

--layout auto (default) reads the sibling runtime-manifest.json `layout`
field — the same contract source the on-device installer uses — and falls
back to content detection only when that manifest is absent. A guessed
layout is always reported.
"""
from __future__ import annotations
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path
EXPECTED={'aarch64':183,'arm64':183,'x86_64':62,'x64':62}
SYSTEM={'libc.so','libdl.so','libm.so','liblog.so','libz.so','libandroid.so','libjnigraphics.so','libgcc.so','libstdc++.so'}
ELF_MAGIC=b'\x7fELF'
SPLIT_REQUIRED=(
  'lib/node_modules/@deepseek-ai/node-addon-system/lib/flock.js',
  'lib/node_modules/@deepseek-ai/node-addon-system/lib/system.node',
  'lib/libandroid-spawn.so',
  'lib/node_modules/@deepseek-ai/dsh/lib/bin.js',
  'etc/tls/cert.pem',
  'usr/bin/.keep',
)
PRIVILEGE_HELPERS=('bin/su','bin/sudo','bin/doas','bin/tsu')
JNI_REQUIRED=('libnode.so','libbash.so','librg.so','libdsh_pty_exec.so','libdsh_system_node.so')
SPLIT_EXEC_BASE=('bin/node','bin/bash','bin/rg','bin/dsh_pty_exec')

def fail(msg): print('FAIL:',msg); return 1
def readelf(args):
    try: return subprocess.check_output(['readelf',*args],text=True,stderr=subprocess.STDOUT)
    except (OSError,subprocess.CalledProcessError): return ''
def elf_info(p):
    b=p.read_bytes()[:64]
    if len(b)<20 or b[:4]!=ELF_MAGIC:return None
    cls=b[4]; machine=int.from_bytes(b[18:20],'little')
    needed=set(re.findall(r'Shared library: \[(.+?)\]',readelf(['-d',str(p)])))
    return cls,machine,needed

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('zip',type=Path); ap.add_argument('--arch',default='aarch64',choices=EXPECTED); ap.add_argument('--sha256'); ap.add_argument('--report')
    ap.add_argument('--layout',choices=('data','split','auto'),default='auto')
    ap.add_argument('--jni-dir',type=Path,help='split layout: directory holding the lib*.so components')
    a=ap.parse_args(); z=a.zip
    if not z.is_file(): return fail(f'missing ZIP {z}')
    digest=hashlib.sha256(z.read_bytes()).hexdigest(); print('ZIP SHA256:',digest)
    if a.sha256 and digest!=a.sha256: return fail(f'ZIP SHA256 mismatch expected {a.sha256}')
    errors=[]; warnings=[]; entries={}
    try:
      with zipfile.ZipFile(z) as f:
        bad=f.testzip()
        if bad: errors.append(f'corrupt member {bad}')
        for i in f.infolist():
          n=i.filename
          if n.startswith('/') or '..' in Path(n).parts: errors.append(f'unsafe path {n}')
          if n in entries: errors.append(f'duplicate path {n}')
          entries[n]=i
          mode=(i.external_attr>>16)&0xffff
          if mode and (mode & 0o170000)==0o120000:
            target=f.read(i).decode('utf-8','surrogateescape')
            if not target or target.startswith('/') or '..' in Path(target).parts: errors.append(f'unsafe symlink {n} -> {target}')
        names=set(entries)
        # ---- layout decision: manifest-driven, same source as the installer ----
        manifest_path=z.parent/'runtime-manifest.json'
        manifest_layout=None
        if manifest_path.is_file():
          try: manifest_layout=(json.loads(manifest_path.read_text(encoding='utf-8')).get('layout') or 'data').lower()
          except (OSError,json.JSONDecodeError) as e: errors.append(f'runtime-manifest.json unreadable: {e}')
          if manifest_layout not in ('data','split'): errors.append(f'manifest layout 未知: {manifest_layout}'); manifest_layout=None
        content_layout='data' if 'bin/node' in names else 'split'
        if a.layout=='auto': layout=manifest_layout or content_layout
        else:
          layout=a.layout
          if manifest_layout and manifest_layout!=layout: errors.append(f'--layout {layout} 与 runtime-manifest.json layout={manifest_layout} 不一致')
        if manifest_layout is None: warnings.append(f'layout 由内容推测为 {content_layout}（缺 runtime-manifest.json）；split 契约以 manifest 为准')
        print(f'layout: {layout} (manifest={manifest_layout}, content={content_layout})')
        for forbidden in PRIVILEGE_HELPERS:
          if forbidden in names: errors.append(f'forbidden privilege helper {forbidden}')
        if layout=='data':
          for required in ('bin/node','bin/bash','bin/rg'):
            if required not in names: errors.append(f'missing {required}')
        else:
          if not a.jni_dir or not a.jni_dir.is_dir(): return fail('split layout requires --jni-dir with the lib*.so components')
          for required in SPLIT_REQUIRED:
            if required not in names: errors.append(f'missing {required}')
          node_arch={'aarch64':'arm64','x86_64':'x64','arm64':'arm64','x64':'x64'}[a.arch]
          forbidden_exec=(*SPLIT_EXEC_BASE, f'lib/node_modules/@vscode/ripgrep-android-{node_arch}/bin/rg')
          for forbidden in forbidden_exec:
            if forbidden in names: errors.append(f'split zip must not carry exec target {forbidden}')
          # Any arch-variant ripgrep copy is equally forbidden in split layout.
          for n in names:
            if re.fullmatch(r'lib/node_modules/@vscode/ripgrep-android-[^/]+/bin/rg', n) and n not in forbidden_exec:
              errors.append(f'split zip must not carry exec target {n}')
          native_manifest_path=z.parent/'runtime-native-manifest.json'
          if not native_manifest_path.is_file(): errors.append('split 布局缺少 sibling runtime-native-manifest.json')
        with tempfile.TemporaryDirectory() as td:
          root=Path(td); f.extractall(root)
          elfs=[]
          for p in root.rglob('*'):
            if p.is_file() and p.read_bytes()[:4]==ELF_MAGIC:
              rel=str(p.relative_to(root))
              if re.search(r'(linux-x64|linux-x86_64|musl[-_]?x64|node-addon-system-linux-x64|koffi-linux-x64)', rel):
                warnings.append('ignored host optional addon: '+rel); continue
              elfs.append(p)
          bundled=set()
          for p in elfs:
            info=elf_info(p)
            if not info: continue
            cls,machine,needed=info
            if machine!=EXPECTED[a.arch] or cls!=2: errors.append(f'wrong ELF arch {p.relative_to(root)} class={cls} machine={machine}')
            bundled.update(n for n in needed if n not in SYSTEM)
          # Resolve every non-system NEEDED name by exact basename or SONAME-equivalent path.
          available={p.name for p in (root/'lib').glob('*') if p.is_file()}
          jni_names=set()
          if layout=='split':
            native_manifest=None
            if native_manifest_path.is_file():
              try: native_manifest=json.loads(native_manifest_path.read_text(encoding='utf-8'))
              except (OSError,json.JSONDecodeError) as e: errors.append(f'runtime-native-manifest.json unreadable: {e}')
            for name in JNI_REQUIRED:
              p=a.jni_dir/name
              if not p.is_file():
                errors.append(f'missing jniLibs component {name}'); continue
              info=elf_info(p)
              if not info: errors.append(f'jniLibs component not an ELF: {name}'); continue
              cls,machine,needed=info
              if machine!=EXPECTED[a.arch] or cls!=2: errors.append(f'wrong ELF arch {name} class={cls} machine={machine}')
              jni_names.add(name)
              bundled.update(n for n in needed if n not in SYSTEM)
              if native_manifest:
                entry=next((x for x in native_manifest.get('libs',[]) if x.get('name')==name),None)
                if entry is None: errors.append(f'runtime-native-manifest.json 缺少 {name}')
                else:
                  d=hashlib.sha256(p.read_bytes()).hexdigest()
                  if d!=entry.get('sha256','').lower(): errors.append(f'{name} SHA-256 与 runtime-native-manifest.json 不一致')
                  if p.stat().st_size!=entry.get('bytes',-1): errors.append(f'{name} 大小与 runtime-native-manifest.json 不一致')
            available|=jni_names
          for n in sorted(bundled):
            if n not in available: errors.append(f'missing bundled DT_NEEDED {n}')
          print(f'ELF files: {len(elfs)} zip + {len(jni_names)} jniLibs; bundled DT_NEEDED: {len(bundled)}; system allowlist: {sorted(SYSTEM)}')
          for w in warnings: print('WARN:',w)
    except zipfile.BadZipFile as e: return fail(f'bad ZIP: {e}')
    if a.report: Path(a.report).write_text(('PASS\n' if not errors else 'FAIL\n')+'\n'.join(errors)+'\n')
    if errors:
      print('\n'.join(' - '+e for e in errors)); return 1
    print(f'PASS: {len(entries)} ZIP entries, paths/symlinks safe, ELF closure resolved')
    return 0
if __name__=='__main__': sys.exit(main())
