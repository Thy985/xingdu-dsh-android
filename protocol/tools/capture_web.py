#!/usr/bin/env python3
"""Protocol probe: capture the official DSH web frontend's network traffic.

Protocol facts flow ONLY from these captures into docs/protocol-notes.md and
fixtures (AGENTS.md red line 5). No third-party client source is consulted.

Usage:
  python protocol/tools/capture_web.py --url <full-url-with-token> \
    --label <name> [--actions none|session] [--timeout 45]

Writes protocol/captures/<label>/{http.jsonl, ws.jsonl, meta.json}.
"""
import argparse, json, sys, time, pathlib, re
from playwright.sync_api import sync_playwright

def sanitize(u: str) -> str:
    return re.sub(r'token=[^&]+', 'token=<REDACTED>', u)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--url', required=True)
    ap.add_argument('--label', required=True)
    ap.add_argument('--actions', default='none', choices=['none', 'session'])
    ap.add_argument('--timeout', type=int, default=45)
    args = ap.parse_args()

    out = pathlib.Path(__file__).resolve().parent.parent / 'captures' / args.label
    out.mkdir(parents=True, exist_ok=True)
    http_f = (out / 'http.jsonl').open('w', encoding='utf-8')
    ws_f = (out / 'ws.jsonl').open('w', encoding='utf-8')
    t0 = time.time()

    def ts(): return round(time.time() - t0, 3)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()

        def on_request(req):
            try:
                http_f.write(json.dumps({'t': ts(), 'dir': 'req',
                    'method': req.method, 'url': sanitize(req.url),
                    'resource': req.resource_type,
                    'post': (req.post_data or '')[:2000]}, ensure_ascii=False) + '\n')
            except Exception: pass

        def on_response(resp):
            try:
                body = ''
                if resp.request.resource_type in ('xhr', 'fetch'):
                    try: body = resp.text()[:4000]
                    except Exception: body = '<unreadable>'
                http_f.write(json.dumps({'t': ts(), 'dir': 'resp',
                    'status': resp.status, 'url': sanitize(resp.url),
                    'body': body}, ensure_ascii=False) + '\n')
            except Exception: pass

        def on_ws(ws):
            ws_f.write(json.dumps({'t': ts(), 'event': 'ws-open',
                'url': sanitize(ws.url)}) + '\n')
            ws.on('framesent', lambda f: ws_f.write(json.dumps(
                {'t': ts(), 'dir': 'sent', 'payload': str(f)[:4000]},
                ensure_ascii=False) + '\n'))
            ws.on('framereceived', lambda f: ws_f.write(json.dumps(
                {'t': ts(), 'dir': 'recv', 'payload': str(f)[:4000]},
                ensure_ascii=False) + '\n'))

        page.on('request', on_request)
        page.on('response', on_response)
        page.on('websocket', on_ws)

        page.goto(args.url, wait_until='domcontentloaded', timeout=30000)
        page.wait_for_timeout(args.timeout * 1000)

        meta = {'label': args.label, 'url_template': sanitize(args.url),
                'captured_at': time.strftime('%FT%TZ', time.gmtime()),
                'note': 'raw probe capture; token redacted'}
        (out / 'meta.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding='utf-8')
        browser.close()
    http_f.close(); ws_f.close()
    print(f'captured → {out}')

if __name__ == '__main__':
    main()
