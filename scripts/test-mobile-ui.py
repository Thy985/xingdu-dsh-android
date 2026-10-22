#!/usr/bin/env python3
"""Run official UI regression suites with disposable loopback-only DSH homes.
Requires the optional host-preview DSH installation, Playwright and Chromium.
Never prints launch credentials or runs a model request/terminal command.
"""
from pathlib import Path
import os
import re
import signal
import socket
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
BIN = Path(os.environ.get('DSH_HOST_BIN', ROOT / 'runtime-build/host-preview/node_modules/@deepseek-ai/dsh/lib/bin.js'))
SUITES = ['mobile-feedback', 'mobile-composition', 'mobile-settings', 'mobile-web']
requested = sys.argv[1:] or SUITES
if any(s not in SUITES for s in requested):
    raise SystemExit('Unknown suite. Choose: ' + ', '.join(SUITES))
if not BIN.is_file():
    raise SystemExit('Install @deepseek-ai/dsh@0.2.1-rc.2 in runtime-build/host-preview or set DSH_HOST_BIN')
subprocess.run(['python3', 'scripts/build-mobile-shell.py'], cwd=ROOT, check=True)
work = ROOT / 'runtime-build'
work.mkdir(exist_ok=True)
passed = True
for suite in requested:
    temporary = Path(tempfile.mkdtemp(prefix=suite + '-', dir=work))
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    env = os.environ.copy()
    env['DSH_HOME'] = str(temporary / 'home')
    command = ['node', str(BIN), '--profile', 'web']
    if suite != 'mobile-web':
        command += ['--patch', str(work / 'mobile-shell.patch.json')]
        (work / 'mobile-shell.patch.json').write_text('[{"insert":[{"id":"android-mobile-shell","name":' + __import__('json').dumps(str(ROOT / 'mobile-shell/lib/index.js')) + '}]}]\n')
    command += ['--host', '127.0.0.1', '--port', str(port), '--no-open']
    logfile = temporary / 'server.log'
    with logfile.open('w') as output:
        server = subprocess.Popen(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    try:
        url = None
        for _ in range(300):
            match = re.search(r'http://127\.0\.0\.1:' + str(port) + r'/\?token=[^\s]+', logfile.read_text())
            if match:
                url = match.group()
                break
            if server.poll() is not None:
                raise RuntimeError('Isolated DSH server exited; inspect private runtime-build logs')
            time.sleep(.2)
        if not url:
            raise RuntimeError('Timed out waiting for isolated DSH server')
        env['DSH_TEST_URL'] = url
        env['DSH_TEST_WORKSPACE'] = str(temporary / 'workspace')
        print('RUN ' + suite + ' (fresh loopback instance)', flush=True)
        test = subprocess.Popen(['node', 'tests/' + suite + '.cjs'], cwd=ROOT, env=env,
                                start_new_session=True)
        try:
            code = test.wait(timeout=150)
        except subprocess.TimeoutExpired:
            code = 124
            print('Suite exceeded 150-second hard timeout', flush=True)
        finally:
            # Failed suites may leave a browser alive. Only clean our own group.
            try:
                os.killpg(test.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                test.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(test.pid, signal.SIGKILL)
                test.wait()
        if code:
            passed = False
            print('FAIL ' + suite, flush=True)
        else:
            print('PASS ' + suite, flush=True)
    finally:
        server.terminate()
        try:
            server.wait(timeout=15)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
raise SystemExit(0 if passed else 1)
