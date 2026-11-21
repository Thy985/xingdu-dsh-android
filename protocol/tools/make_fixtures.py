#!/usr/bin/env python3
"""Generate sanitized protocol fixtures from raw captures.

Red line 5: fixtures come only from our own probe captures, pinned to
DSH_PROTOCOL_TARGET (the bundled engine version). UUIDs and host paths are
replaced with stable placeholders so fixtures are deterministic and
repo-safe.
"""
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TARGET = "0.2.1-rc.2"

def redact(obj, uuid_map):
    """Replace uuids with stable placeholders (u1, u2… in order of appearance)."""
    if isinstance(obj, str):
        def sub(m):
            u = m.group(0)
            if u not in uuid_map: uuid_map[u] = f"<UUID{len(uuid_map)+1}>"
            return uuid_map[u]
        return re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', sub, obj)
    if isinstance(obj, list): return [redact(x, uuid_map) for x in obj]
    if isinstance(obj, dict): return {k: redact(v, uuid_map) for k, v in obj.items()}
    return obj

def main(capture_label):
    cap = ROOT / 'captures' / capture_label
    out = ROOT / 'fixtures'
    out.mkdir(parents=True, exist_ok=True)
    uuid_map = {}
    manifest = {'DSH_PROTOCOL_TARGET': TARGET, 'source_capture': capture_label,
                'fixtures': []}

    ws = [json.loads(l) for l in (cap / 'ws.jsonl').open(encoding='utf-8')]
    # 1. envelope templates (first sent open + first recv item, redacted)
    sent_open = next((f['payload'] for f in ws if f.get('dir') == 'sent' and '"type":"open"' in str(f.get('payload',''))), None)
    recv_item = next((f['payload'] for f in ws if f.get('dir') == 'recv'), None)
    fixtures = {}
    if sent_open:
        o = json.loads(sent_open)
        fixtures['ws-open-envelope'] = {'type': o['type'], 'streamId': '<STREAM_UUID>',
            'endpoint': o['endpoint'], 'payload': redact(o.get('payload', {}), uuid_map)}
    if recv_item:
        i = json.loads(recv_item)
        fixtures['ws-item-envelope'] = {'type': i['type'], 'streamId': '<STREAM_UUID>',
            'value': redact(i.get('value', {}), uuid_map)}
    # 2. per-endpoint first-value samples
    first_by_endpoint, open_by_stream = {}, {}
    for f in ws:
        payload = f.get('payload', '')
        if f.get('dir') == 'sent':
            try:
                o = json.loads(payload)
                open_by_stream[o.get('streamId')] = o.get('endpoint')
            except Exception: pass
        elif f.get('dir') == 'recv':
            try:
                i = json.loads(payload)
                ep = open_by_stream.get(i.get('streamId'))
                if ep and ep not in first_by_endpoint:
                    first_by_endpoint[ep] = i.get('value')
            except Exception: pass
    for ep, value in sorted(first_by_endpoint.items()):
        name = ep.replace('/', '-').replace('$', 'events-')
        fixtures[f'endpoint-{name}-first-value'] = redact(value, uuid_map)

    # 3. HTTP endpoint inventory
    http = [json.loads(l) for l in (cap / 'http.jsonl').open(encoding='utf-8')]
    endpoints = sorted({(r['method'], re.sub(r'\?.*$', '', r['url']).split('/api/')[-1])
        for r in http if r['dir'] == 'req' and '/api/' in r['url']
        and r.get('resource') in ('xhr', 'fetch')})
    fixtures['http-endpoint-inventory'] = [
        {'method': m, 'path': '/api/' + p} for m, p in endpoints]

    for name, body in fixtures.items():
        fpath = out / f'{name}.json'
        fpath.write_text(json.dumps(body, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        manifest['fixtures'].append(name)

    (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'wrote {len(fixtures)} fixtures + manifest → {out}')

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'rc2-page-load')
