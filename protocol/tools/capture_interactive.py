#!/usr/bin/env python3
"""Interactive protocol probe: drive a real session (send message, observe
streaming, tool-call and approval frames) while recording all traffic.

Facts flow only into protocol-notes.md / fixtures (red line 5).
"""
import argparse, json, sys, time, pathlib, re
from playwright.sync_api import sync_playwright

def sanitize(u): return re.sub(r'token=[^&]+', 'token=<REDACTED>', u)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--url', required=True)
    ap.add_argument('--label', required=True)
    ap.add_argument('--prompt', required=True)
    ap.add_argument('--observe-seconds', type=int, default=150)
    args = ap.parse_args()

    out = pathlib.Path(__file__).resolve().parent.parent / 'captures' / args.label
    out.mkdir(parents=True, exist_ok=True)
    http_f = (out / 'http.jsonl').open('w', encoding='utf-8')
    ws_f = (out / 'ws.jsonl').open('w', encoding='utf-8')
    ui_f = (out / 'ui.jsonl').open('w', encoding='utf-8')
    t0 = time.time()
    def ts(): return round(time.time() - t0, 3)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.on('request', lambda r: safe_write(http_f, {'t': ts(), 'dir': 'req',
            'method': r.method, 'url': sanitize(r.url), 'resource': r.resource_type,
            'post': (r.post_data or '')[:3000]}))
        page.on('response', lambda r: safe_write(http_f, {'t': ts(), 'dir': 'resp',
            'status': r.status, 'url': sanitize(r.url),
            'body': safe_body(r)}))
        def on_ws(ws):
            safe_write(ws_f, {'t': ts(), 'event': 'ws-open', 'url': sanitize(ws.url)})
            ws.on('framesent', lambda f: safe_write(ws_f, {'t': ts(), 'dir': 'sent', 'payload': str(f)[:6000]}))
            ws.on('framereceived', lambda f: safe_write(ws_f, {'t': ts(), 'dir': 'recv', 'payload': str(f)[:6000]}))
        page.on('websocket', on_ws)

        page.goto(args.url, wait_until='domcontentloaded', timeout=30000)
        page.wait_for_timeout(8000)

        # composer: locate a textarea or contenteditable near the bottom
        # dismiss first-run modals (预览版说明 → 继续) that intercept the composer
        for label in ['继续', '开始使用', '知道了', '确定']:
            btn = page.get_by_text(label, exact=True)
            try:
                if btn.count() and btn.first.is_visible():
                    ui_dump_line(ui_f, ts, f'dismiss-modal[{label}]')
                    btn.first.click()
                    page.wait_for_timeout(1500)
            except Exception: pass

        # select the probe model (default DeepSeek key is invalid in the probe home)
        try:
            page.get_by_text('DeepSeek-V4.1-Flash', exact=False).first.click(timeout=8000)
            page.wait_for_timeout(1200)
            page.get_by_text('模型', exact=True).first.click(timeout=6000)
            page.wait_for_timeout(1200)
            page.get_by_text('agnes-3.0-flash', exact=False).first.click(timeout=6000)
            ui_dump_line(ui_f, ts, 'model-selected[agnes-3.0-flash]')
            page.wait_for_timeout(1000)
        except Exception as e:
            ui_dump_line(ui_f, ts, f'model-select-failed:{e}')

        composer = page.locator('[contenteditable="true"]').first
        composer.wait_for(state='visible', timeout=15000)
        composer.fill(args.prompt)
        ui_dump_line(ui_f, ts, 'prompt-filled')
        page.screenshot(path=str(out / 'before-send.png'))
        # send: Enter, or a send button if Enter does not submit
        page.keyboard.press('Enter')
        ui_dump_line(ui_f, ts, 'enter-pressed')
        page.wait_for_timeout(3000)
        page.screenshot(path=str(out / 'after-send.png'))

        # observe streaming / tool calls / approvals; click approval when it appears
        deadline = time.time() + args.observe_seconds
        approved = False
        while time.time() < deadline:
            texts = visible_texts(page)
            if texts: ui_dump_line(ui_f, ts, ' | '.join(texts[:6]))
            if not approved:
                for label in ['允许一次', '允许', '批准', 'Approve', 'Allow', '接受', '同意']:
                    btn = page.get_by_text(label, exact=False).first
                    try:
                        if btn.is_visible():
                            ui_dump_line(ui_f, ts, f'APPROVAL-CANDIDATE[{label}]')
                            btn.click()
                            approved = True
                            ui_dump_line(ui_f, ts, f'approved[{label}]')
                            break
                    except Exception: pass
            time.sleep(2)

        browser.close()
    for f in (http_f, ws_f, ui_f): f.close()
    print(f'captured → {out}')

def safe_body(r):
    try:
        if r.request.resource_type in ('xhr', 'fetch'): return r.text()[:4000]
    except Exception: pass
    return ''

def safe_write(f, obj):
    try: f.write(json.dumps(obj, ensure_ascii=False) + '\n')
    except Exception: pass

def visible_texts(page):
    try:
        return [t for t in page.locator('body').inner_text().split('\n') if t.strip()][:8]
    except Exception: return []

def ui_dump_line(f, ts, what):
    try: f.write(json.dumps({'t': ts(), 'ui': what}, ensure_ascii=False) + '\n')
    except Exception: pass
def dump_ui(page, f, ts): ui_dump_line(f, ts, ' | '.join(visible_texts(page)))

if __name__ == '__main__':
    main()
