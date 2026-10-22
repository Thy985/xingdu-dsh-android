#!/usr/bin/env python3
"""Build disposable x86_64 Android test runtime from the complete ARM JS runtime."""
from __future__ import annotations
import concurrent.futures, gzip, hashlib, json, os, re, shutil, subprocess, sys, tempfile
from pathlib import Path
from urllib.request import urlopen, Request

ROOT = Path('/workspace/dsh-android-native')
ARM = ROOT/'runtime-build/arm64-root'
OUT = ROOT/'runtime-build/x86-root'
BASE = 'https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-x86_64/'
INDEX_URL = BASE + 'Packages.gz'
PACKAGES = ['nodejs-lts','bash','readline','ncurses','libiconv','termux-tools','ripgrep','pcre2','openssl','c-ares','libicu','libsqlite','zlib','libc++','libuv','brotli','libandroid-support','libandroid-spawn','ca-certificates']
LOG = ROOT/'runtime-build/x86-build.log'

def log(s):
    print(s, flush=True)
    with LOG.open('a') as f: f.write(s+'\n')

def fetch(url, dest):
    with urlopen(Request(url, headers={'User-Agent':'dsh-x86-runtime/1'}), timeout=180) as r, open(dest,'wb') as f:
        while True:
            b=r.read(1024*1024)
            if not b: break
            f.write(b)

def parse_index(data):
    text = gzip.decompress(data).decode('utf-8', 'replace')
    out={}; cur={}
    for line in text.splitlines()+['']:
        if not line.strip():
            if cur.get('Package') in PACKAGES: out[cur['Package']]=cur.copy()
            cur={}; continue
        if ': ' in line:
            k,v=line.split(': ',1); cur[k]=v
    missing=[p for p in PACKAGES if p not in out]
    if missing: raise RuntimeError('index missing: '+','.join(missing))
    return out

def sha256(p):
    h=hashlib.sha256()
    with open(p,'rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
    return h.hexdigest()

def is_arm_elf(p):
    try:
        with open(p,'rb') as f: h=f.read(20)
        return len(h)>=20 and h[:4]==b'\x7fELF' and int.from_bytes(h[18:20],'little')==183
    except (OSError, IsADirectoryError): return False

def copy_deref(src, dst):
    source = src.resolve()
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists() or dst.is_symlink(): dst.unlink()
    shutil.copy2(source, dst)

def main():
    LOG.write_text('x86 runtime build start\n')
    if not ARM.is_dir(): raise RuntimeError(f'missing {ARM}')
    if OUT.exists(): shutil.rmtree(OUT)
    log(f'cp -a {ARM} -> {OUT}')
    shutil.copytree(ARM, OUT, symlinks=True)
    log('cleaning ARM ELF files (JS and non-ELF files retained)')
    removed=0
    for p in list(OUT.rglob('*')):
        if p.is_file() and is_arm_elf(p): p.unlink(); removed+=1
    log(f'removed ARM ELF: {removed}')
    with urlopen(INDEX_URL, timeout=180) as r: index=parse_index(r.read())
    dl=ROOT/'runtime-build/x86-debs'; dl.mkdir(exist_ok=True)
    jobs=[]
    for name in PACKAGES:
        ent=index[name]; dest=dl/Path(ent['Filename']).name
        jobs.append((name,ent,dest))
    def one(t):
        name,ent,dest=t
        if not dest.exists() or sha256(dest)!=ent['SHA256']:
            log(f'download {name} {ent["Filename"]}')
            fetch(BASE.replace('dists/stable/main/binary-x86_64/','')+ent['Filename'], dest)
        got=sha256(dest)
        if got.lower()!=ent['SHA256'].lower(): raise RuntimeError(f'SHA256 mismatch {name}: {got} != {ent["SHA256"]}')
        return name,dest
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        for name,dest in ex.map(one,jobs): log(f'verified {name} {dest.name}')
    with tempfile.TemporaryDirectory(prefix='dsh-x86-extract-') as td:
        flat=Path(td)/'data'; flat.mkdir()
        for name,dest in [(x[0],dl/Path(x[1]['Filename']).name) for x in jobs]:
            exdir=Path(td)/name; exdir.mkdir()
            subprocess.run(['dpkg-deb','-x',str(dest),str(exdir)],check=True)
            src=exdir/'data/data/com.termux/files/usr'
            if name == 'nodejs-lts' and (src/'include').exists():
                shutil.copytree(src/'include', flat/'include', symlinks=False, dirs_exist_ok=True)
            # Keep extraction flat, but install only selected executable names below.
            for p in src.rglob('*'):
                rel=p.relative_to(src); q=flat/rel
                if p.is_file() and (str(rel).startswith('lib/') or str(rel).startswith('share/')):
                    q.parent.mkdir(parents=True,exist_ok=True)
                    if q.exists() or q.is_symlink(): q.unlink()
                    shutil.copy2(p.resolve(),q)
                elif p.is_symlink() and (str(rel).startswith('lib/') or str(rel).startswith('share/')):
                    q.parent.mkdir(parents=True,exist_ok=True)
                    target=p.resolve()
                    if target.is_file(): shutil.copy2(target,q)
            # only these native executables overwrite ARM wrappers
            if name in ('nodejs-lts','bash','ripgrep'):
                for bn in ('node','bash','rg'):
                    s=src/'bin'/bn
                    if s.exists() and s.is_file(): copy_deref(s,OUT/'bin'/bn)
        # Copy headers needed to compile N-API addon, if supplied by node package.
        for cand in [flat/'include', flat/'include/node']:
            if cand.exists():
                dest=OUT/cand.relative_to(flat)
                if dest.exists(): shutil.rmtree(dest)
                shutil.copytree(cand,dest,symlinks=False)
        # shared libraries and certs, dereferenced to avoid broken aliases
        for p in flat.rglob('*'):
            rel=p.relative_to(flat)
            if p.is_file() and (str(rel).startswith('lib/') or str(rel).startswith('share/')):
                copy_deref(p,OUT/rel)
    # Ensure exactly named library aliases exist as real files, not symlinks.
    for p in list((OUT/'lib').glob('*')):
        if p.is_symlink(): copy_deref(p, p)
    # x86 ripgrep package naming expected by runtime JS.
    old=OUT/'lib/node_modules/@vscode/ripgrep-android-arm64'
    new=OUT/'lib/node_modules/@vscode/ripgrep-android-x64'
    if old.exists() and not new.exists(): shutil.copytree(old,new,symlinks=False)
    if new.exists():
        for p in new.rglob('*'):
            if p.is_file() and p.name=='rg': p.unlink()
        newbin=new/'bin/rg'; newbin.parent.mkdir(parents=True,exist_ok=True); copy_deref(OUT/'bin/rg',newbin)
    # Rebuild Node-API addon and PTY helper with NDK x86_64 Android API 26.
    clang='/home/ubuntu/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang'
    if not Path(clang).exists(): clang='x86_64-linux-android26-clang'
    nodeinc=OUT/'include/node'
    if not nodeinc.exists():
        # N-API headers are also shipped in the ARM runtime in some builds.
        for c in [ARM/'include/node', ARM/'lib/node_modules/node-addon-api']:
            if c.exists():
                nodeinc.mkdir(parents=True,exist_ok=True); break
    addon=OUT/'lib/node_modules/@deepseek-ai/node-addon-system/lib/system.node'; addon.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run([clang,'-shared','-fPIC','-O2','-I',str(nodeinc),str(ROOT/'third_party/runtime/flock.c'),'-o',str(addon)],check=True)
    pty=OUT/'bin/dsh_pty_exec'; subprocess.run([clang,'-O2','-fPIE','-pie',str(ROOT/'third_party/runtime/dsh_pty_exec.c'),'-o',str(pty)],check=True)
    # Any accidentally retained ARM ELF is a hard failure.
    leftovers=[str(p.relative_to(OUT)) for p in OUT.rglob('*') if p.is_file() and is_arm_elf(p)]
    if leftovers: raise RuntimeError('ARM ELF remains: '+','.join(leftovers[:20]))
    # ELF/needed closure report for adb testing.
    report={'node':subprocess.check_output(['file',str(OUT/'bin/node')],text=True).strip(),'needed':subprocess.check_output(['readelf','-d',str(OUT/'bin/node')],text=True).splitlines(),'arm_elf_remaining':leftovers}
    (ROOT/'runtime-build/x86-closure.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    log(report['node']); log('ARM ELF remaining: 0'); log('built '+str(addon)); log('built '+str(pty)); log('DONE '+str(OUT))
if __name__=='__main__':
    try: main()
    except Exception as e:
        log('ERROR '+repr(e)); raise
