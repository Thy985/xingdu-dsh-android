#!/usr/bin/env python3
"""Static verifier for the Android runtime ZIP; never executes runtime binaries."""
from __future__ import annotations
import argparse, hashlib, os, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path
EXPECTED={'aarch64':183,'arm64':183,'x86_64':62,'x64':62}
SYSTEM={'libc.so','libdl.so','libm.so','liblog.so','libz.so','libandroid.so','libjnigraphics.so','libgcc.so','libstdc++.so'}
ELF_MAGIC=b'\x7fELF'

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
        for required in ('bin/node','bin/bash','bin/rg'):
          if required not in names: errors.append(f'missing {required}')
        for forbidden in ('bin/su','bin/sudo','bin/doas','bin/tsu'):
          if forbidden in names: errors.append(f'forbidden privilege helper {forbidden}')
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
          for n in sorted(bundled):
            if n not in available: errors.append(f'missing bundled DT_NEEDED {n}')
          print(f'ELF files: {len(elfs)}; bundled DT_NEEDED: {len(bundled)}; system allowlist: {sorted(SYSTEM)}')
          for w in warnings: print('WARN:',w)
    except zipfile.BadZipFile as e: return fail(f'bad ZIP: {e}')
    if a.report: Path(a.report).write_text(('PASS\n' if not errors else 'FAIL\n')+'\n'.join(errors)+'\n')
    if errors:
      print('\n'.join(' - '+e for e in errors)); return 1
    print(f'PASS: {len(entries)} ZIP entries, paths/symlinks safe, ELF closure resolved')
    return 0
if __name__=='__main__': sys.exit(main())
