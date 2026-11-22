#!/usr/bin/env python3
"""Generate sanitized protocol fixtures from raw captures.

Red line 5: fixtures come only from our own probe captures, pinned to
DSH_PROTOCOL_TARGET (the bundled engine version). UUIDs, host paths and event
timestamps are replaced with stable placeholders so fixtures are deterministic
and repo-safe. Capture files keep two record shapes over time (`dir` is
`req`/`resp` in the page-load captures, `request`/`response` in the in-session
captures); both are normalized here.

Usage: make_fixtures.py [capture_label ...]
  default: page-load + in-session + session-domain captures (see DEFAULT_CAPTURES)
"""
import json, pathlib, re, sys
from urllib.parse import quote

ROOT = pathlib.Path(__file__).resolve().parent.parent
TARGET = "0.2.1-rc.2"
SESSION_CAPTURES = ["rc2-stream-message", "rc2-tool-approval", "rc2-steering"]
SESSION_DOMAIN_CAPTURES = [
    "rc2-session-archive",
    "rc2-session-workspace",
    "rc2-session-history",
    "rc2-session-errors",
    "rc2-session-reconnect",
]
DEFAULT_CAPTURES = ["rc2-page-load"] + SESSION_CAPTURES + SESSION_DOMAIN_CAPTURES

UUID_RE = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
PATH_MAP = [
    ("D:\\Projects\\Archive\\dsh-host-preview\\rc2-crud2-home\\.dsh", "<PROBE_HOME>"),
    ("D:\\Projects\\Archive\\dsh-host-preview\\rc2-cap-home\\.dsh", "<PROBE_HOME>"),
    ("D:\\Projects\\Archive\\dsh-host-preview\\probe-workspace", "<WORKSPACE>"),
    ("D:\\Documents\\deepseek-harness\\default-workspace", "<WORKSPACE_DEFAULT>"),
    ("D:\\Documents\\deepseek-harness\\second-workspace", "<WORKSPACE_SECOND>"),
    ("D:\\Projects\\Archive\\dsh-host-preview", "<PROBE_HOME>"),
    ("C:\\Users\\lenovo", "<HOST_HOME>"),
]

WIN_PATH_RE = re.compile(
    r'(?i)(?<![A-Za-z0-9])(?:[A-Z]%3A|[A-Z]:)(?:[\\/]|%5C|%2F){1,4}[^\s"\'<>|,;)\]}]*')

def _path_variants(raw):
    """Host paths appear raw, JSON-double-escaped, forward-slashed and URL-encoded."""
    doubled = raw.replace('\\', '\\\\')
    forward = raw.replace('\\', '/')
    variants = {
        raw, doubled, forward, doubled.replace('\\', '/'),
        quote(raw, safe=''), quote(doubled, safe=''),
    }
    return sorted(variants, key=len, reverse=True)

def scrub_paths(text):
    for raw, placeholder in PATH_MAP:
        for variant in _path_variants(raw):
            text = text.replace(variant, placeholder)
    # Catch any remaining drive-absolute path by shape: raw `D:\` / `D:/`, or
    # `%3A` for the colon and `%5C` / `%2F` for the separators, in any mix.
    return WIN_PATH_RE.sub("<HOST_PATH>", text)

SKILL_ENTRY_PLACEHOLDER = "<SKILL_ENTRY_REDACTED>"

def redact_skill_entries(entries, uuid_map):
    """Skill catalogs carry a host-installed name+description list per entry."""
    out = []
    for entry in entries:
        if not isinstance(entry, dict):
            out.append(redact(entry, uuid_map))
            continue
        out.append({
            k: (SKILL_ENTRY_PLACEHOLDER
                if k in ('name', 'description') and isinstance(v, str)
                else redact(v, uuid_map))
            for k, v in entry.items() if k != 'time'
        })
    return out

def redact(obj, uuid_map):
    """Replace uuids with stable placeholders (u1, u2… in order of appearance).

    Host-injected skill catalogs are replaced wholesale: they describe the
    maintainer's machine, not the protocol.
    """
    if isinstance(obj, str):
        if "A skill is a reusable set of task-specific instructions" in obj:
            return "<SKILLS_CATALOG_REDACTED>"
        def sub(m):
            u = m.group(0)
            if u == '00000000-0000-4000-8000-000000000000':
                return '<GHOST_UUID>'
            if u not in uuid_map: uuid_map[u] = f"<UUID{len(uuid_map)+1}>"
            return uuid_map[u]
        return scrub_paths(UUID_RE.sub(sub, obj))
    if isinstance(obj, list): return [redact(x, uuid_map) for x in obj]
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k == 'time': continue
            if k == 'entries' and isinstance(v, list):
                out[k] = redact_skill_entries(v, uuid_map)
            else:
                out[k] = redact(v, uuid_map)
        return out
    return obj

def load_ws(cap):
    rows = []
    for line in (cap / 'ws.jsonl').open(encoding='utf-8'):
        row = json.loads(line)
        if row.get('dir') in ('sent', 'recv'):
            try: row['parsed'] = json.loads(row.get('payload', ''))
            except Exception: row['parsed'] = None
        rows.append(row)
    return rows

def normalize_http(cap):
    rows = []
    for line in (cap / 'http.jsonl').open(encoding='utf-8'):
        row = json.loads(line)
        row['dir'] = {'request': 'req', 'response': 'resp'}.get(row.get('dir'), row.get('dir'))
        if 'body' in row and 'post' not in row:
            row['post'] = row.pop('body')
        rows.append(row)
    return rows

def page_load_fixtures(cap, uuid_map):
    fixtures = {}
    ws = load_ws(cap)
    sent_open = next((f['payload'] for f in ws if f.get('dir') == 'sent' and '"type":"open"' in str(f.get('payload', ''))), None)
    recv_item = next((f['payload'] for f in ws if f.get('dir') == 'recv'), None)
    if sent_open:
        o = json.loads(sent_open)
        fixtures['ws-open-envelope'] = {'type': o['type'], 'streamId': '<STREAM_UUID>',
            'endpoint': o['endpoint'], 'payload': redact(o.get('payload', {}), uuid_map)}
    if recv_item:
        i = json.loads(recv_item)
        fixtures['ws-item-envelope'] = {'type': i['type'], 'streamId': '<STREAM_UUID>',
            'value': redact(i.get('value', {}), uuid_map)}
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

    http = normalize_http(cap)
    endpoints = sorted({(r['method'], re.sub(r'\?.*$', '', r['url']).split('/api/')[-1])
        for r in http if r['dir'] == 'req' and '/api/' in r['url']
        and r.get('resource') in ('xhr', 'fetch')})
    fixtures['http-endpoint-inventory'] = [
        {'method': m, 'path': '/api/' + p} for m, p in endpoints]
    return fixtures

def event_by_type(ws, event_type, occurrence=0):
    hits = [f for f in ws if f.get('dir') == 'recv' and isinstance(f.get('parsed'), dict)
            and (f['parsed'].get('value') or {}).get('type') == 'event'
            and f['parsed']['value']['event']['type'] == event_type]
    return hits[occurrence] if len(hits) > occurrence else None

def item_by(ws, pred):
    return next((f for f in ws if f.get('dir') == 'recv' and isinstance(f.get('parsed'), dict)
                 and isinstance(f['parsed'].get('value'), dict) and pred(f['parsed']['value'])), None)

def http_pair(http, needle):
    """Return (request body, response body) as dicts for the first matching call."""
    req = next((r for r in http if r['dir'] == 'req' and needle in r.get('url', '')), None)
    resp = next((r for r in http if r['dir'] == 'resp' and needle in r.get('url', '')
                 and req and r['t'] >= req['t']), None)
    def body(row):
        try: return json.loads(row.get('post') or '{}')
        except Exception: return row.get('post')
    return (body(req) if req else None), (body(resp) if resp else None)

def envelope(fixture_value, uuid_map):
    return redact({'type': 'item', 'streamId': '<STREAM_UUID>', 'value': fixture_value}, uuid_map)

def stream_fixtures(cap, uuid_map):
    fixtures = {}
    ws = load_ws(cap)
    http = normalize_http(cap)

    req, resp = http_pair(http, '/session/prompt')
    if req: fixtures['http-session-prompt-queue-request'] = redact(req, uuid_map)
    if resp: fixtures['http-session-prompt-response'] = redact(resp, uuid_map)

    user_msg = event_by_type(ws, 'user/message')
    if user_msg: fixtures['ws-session-user-message'] = envelope(user_msg['parsed']['value'], uuid_map)

    start = item_by(ws, lambda v: v.get('type') == 'assistant-stream' and v['frame'].get('type') == 'start')
    if start: fixtures['ws-session-assistant-stream-start'] = envelope(start['parsed']['value'], uuid_map)

    delta = item_by(ws, lambda v: v.get('type') == 'assistant-stream'
                    and v['frame'].get('type') == 'chunk' and v['frame']['chunk'].get('type') == 'text-delta')
    if delta: fixtures['ws-session-assistant-stream-text-delta'] = envelope(delta['parsed']['value'], uuid_map)

    end = item_by(ws, lambda v: v.get('type') == 'assistant-stream' and v['frame'].get('type') == 'end')
    if end: fixtures['ws-session-assistant-stream-end'] = envelope(end['parsed']['value'], uuid_map)

    message = event_by_type(ws, 'assistant/message')
    if message: fixtures['ws-session-assistant-message'] = envelope(message['parsed']['value'], uuid_map)

    turn_end = event_by_type(ws, 'turn/end')
    if turn_end: fixtures['ws-session-turn-end'] = envelope(turn_end['parsed']['value'], uuid_map)
    return fixtures

def approval_fixtures(cap, uuid_map):
    fixtures = {}
    ws = load_ws(cap)
    http = normalize_http(cap)

    message = event_by_type(ws, 'assistant/message')
    if message: fixtures['ws-session-assistant-tool-call-message'] = envelope(message['parsed']['value'], uuid_map)

    call = event_by_type(ws, 'tool/call')
    if call: fixtures['ws-session-tool-call'] = envelope(call['parsed']['value'], uuid_map)

    asked = event_by_type(ws, 'approval/asked')
    if asked: fixtures['ws-session-approval-asked'] = envelope(asked['parsed']['value'], uuid_map)

    request = next((f for f in ws if f.get('dir') == 'recv' and isinstance(f.get('parsed'), dict)
                    and f['parsed'].get('value', {}).get('type') == 'waterfall'
                    and f['parsed']['value'].get('event') == 'approval/request'), None)
    if request: fixtures['ws-session-approval-request'] = envelope(request['parsed']['value'], uuid_map)

    req, resp = http_pair(http, '/$events/result')
    if req: fixtures['http-events-result-request'] = redact(req, uuid_map)
    if resp: fixtures['http-events-result-response'] = redact(resp, uuid_map)

    decided = event_by_type(ws, 'approval/decided')
    if decided: fixtures['ws-session-approval-decided'] = envelope(decided['parsed']['value'], uuid_map)

    result = event_by_type(ws, 'tool/result')
    if result: fixtures['ws-session-tool-result'] = envelope(result['parsed']['value'], uuid_map)
    return fixtures

def steering_fixtures(cap, uuid_map):
    fixtures = {}
    ws = load_ws(cap)
    http = normalize_http(cap)

    requests = [r for r in http if r['dir'] == 'req' and '/session/prompt' in r.get('url', '')]
    steer_req = next((r for r in requests if 'steer' in (r.get('post') or '')), None)
    if steer_req:
        fixtures['http-session-prompt-steer-request'] = redact(json.loads(steer_req['post']), uuid_map)
        resp = next((r for r in http if r['dir'] == 'resp' and r['t'] >= steer_req['t']
                     and '/session/prompt' in r.get('url', '')), None)
        if resp: fixtures['http-session-prompt-steer-response'] = redact(json.loads(resp['post']), uuid_map)

    spliced = next((f for f in ws if f.get('dir') == 'recv' and isinstance(f.get('parsed'), dict)
                    and f['parsed'].get('value', {}).get('type') == 'event'
                    and f['parsed']['value']['event']['type'] == 'agent/inbox/spliced'
                    and f['parsed']['value']['event']['data'].get('target') == 'next-step'
                    and f['parsed']['value']['event']['data'].get('inserted')), None)
    if spliced: fixtures['ws-session-inbox-spliced'] = envelope(spliced['parsed']['value'], uuid_map)

    step = next((f for f in ws if f.get('dir') == 'recv' and isinstance(f.get('parsed'), dict)
                 and f['parsed'].get('value', {}).get('type') == 'event'
                 and f['parsed']['value']['event']['type'] == 'step/start'
                 and f['parsed']['value']['event']['data'].get('step') == 2), None)
    if step: fixtures['ws-session-step-start'] = envelope(step['parsed']['value'], uuid_map)

    user_msgs = [f for f in ws if f.get('dir') == 'recv' and isinstance(f.get('parsed'), dict)
                 and f['parsed'].get('value', {}).get('type') == 'event'
                 and f['parsed']['value']['event']['type'] == 'user/message']
    midturn = next((f for f in user_msgs if '补充要求' in json.dumps(f['parsed'], ensure_ascii=False)), None)
    if midturn: fixtures['ws-session-steer-user-message'] = envelope(midturn['parsed']['value'], uuid_map)

    turn_end = event_by_type(ws, 'turn/end')
    if turn_end: fixtures['ws-session-steer-turn-end'] = envelope(turn_end['parsed']['value'], uuid_map)

    endpoints = sorted({(r['method'], re.sub(r'\?.*$', '', r['url']).split('/api/')[-1])
                        for r in http if r['dir'] == 'req' and '/api/' in r['url']})
    fixtures['http-session-endpoint-inventory'] = [
        {'method': m, 'path': '/api/' + p} for m, p in endpoints]
    return fixtures

def http_calls(http, needle):
    """All (request, response) pairs whose URL contains needle, in capture order."""
    calls = []
    for req in (r for r in http if r['dir'] == 'req' and needle in r.get('url', '')):
        resp = next((r for r in http if r['dir'] == 'resp' and needle in r.get('url', '')
                     and r['t'] >= req['t']), None)
        calls.append((req, resp))
    return calls

def body_dict(row):
    if not row: return None
    try: return json.loads(row.get('post') or '{}')
    except Exception: return row.get('post')

def http_rpc_fixtures(fixtures, http, needle, base, uuid_map, index=0):
    calls = http_calls(http, needle)
    if len(calls) <= index: return
    req, resp = calls[index]
    fixtures[f'http-{base}-request'] = redact(body_dict(req), uuid_map)
    if resp is not None:
        fixtures[f'http-{base}-response'] = redact(body_dict(resp), uuid_map)

def ws_open_fixture(ws, endpoint):
    """Last open frame for an endpoint (the activation pair, not the page-load one)."""
    found = None
    for f in ws:
        o = f.get('parsed')
        if f.get('dir') == 'sent' and isinstance(o, dict) \
                and o.get('type') == 'open' and o.get('endpoint') == endpoint:
            found = o
    return found

def session_archive_fixtures(cap, uuid_map):
    """HTTP archive call (rc2-session-archive)."""
    fixtures = {}
    http = normalize_http(cap)
    http_rpc_fixtures(fixtures, http, '/workspace/archiveSession',
                      'workspace-archive-session', uuid_map)
    return fixtures

def session_workspace_fixtures(cap, uuid_map):
    """Session list/create and workspace unarchive; workspace switch emits no RPC."""
    fixtures = {}
    ws = load_ws(cap)
    http = normalize_http(cap)
    http_rpc_fixtures(fixtures, http, '/session/list', 'session-list', uuid_map)
    http_rpc_fixtures(fixtures, http, '/session/create', 'session-create', uuid_map)
    http_rpc_fixtures(fixtures, http, '/session/create',
                      'session-create-workspace-switch', uuid_map, index=1)
    http_rpc_fixtures(fixtures, http, '/workspace/unarchiveSession',
                      'workspace-unarchive-session', uuid_map)
    archived = item_by(ws, lambda v: v.get('type') == 'archived')
    if archived:
        fixtures['ws-workspace-follow-archived'] = envelope(archived['parsed']['value'], uuid_map)
    return fixtures

def session_history_fixtures(cap, uuid_map):
    """Activation = open session/follow + job/list; history arrives as one snapshot."""
    fixtures = {}
    ws = load_ws(cap)
    for endpoint, name in (('session/follow', 'ws-session-follow-open'),
                           ('job/list', 'ws-job-list-open')):
        o = ws_open_fixture(ws, endpoint)
        if o:
            fixtures[name] = redact({'type': 'open', 'streamId': '<STREAM_UUID>',
                                     'endpoint': o['endpoint'],
                                     'payload': o.get('payload', {})}, uuid_map)
    cancel = next((f for f in ws if f.get('dir') == 'sent'
                   and isinstance(f.get('parsed'), dict)
                   and f['parsed'].get('type') == 'cancel'), None)
    if cancel:
        fixtures['ws-stream-cancel'] = {'type': 'cancel', 'streamId': '<STREAM_UUID>'}
    snapshot = next((f for f in ws if f.get('dir') == 'recv'
                     and isinstance(f.get('parsed'), dict)
                     and isinstance(f['parsed'].get('value'), dict)
                     and f['parsed']['value'].get('type') == 'snapshot'
                     and len(f['parsed']['value'].get('records') or []) >= 30), None)
    if snapshot:
        fixtures['ws-session-follow-history-snapshot'] = envelope(
            snapshot['parsed']['value'], uuid_map)
    return fixtures

def session_error_fixtures(cap, uuid_map):
    """Error envelopes from ghost-id probes (HTTP 200 + result.ok=false)."""
    fixtures = {}
    http = normalize_http(cap)
    http_rpc_fixtures(fixtures, http, '/session/list',
                      'session-list-args-invalid', uuid_map, index=1)
    http_rpc_fixtures(fixtures, http, '/session/create',
                      'session-create-workspace-not-found', uuid_map, index=1)
    http_rpc_fixtures(fixtures, http, '/workspace/archiveSession',
                      'workspace-archive-session-not-found', uuid_map, index=0)
    http_rpc_fixtures(fixtures, http, '/workspace/unarchiveSession',
                      'workspace-unarchive-session-ghost', uuid_map, index=0)
    return fixtures

def session_reconnect_fixtures(cap, uuid_map):
    """After a transport drop the frontend reopens every mux stream (no resume)."""
    fixtures = {}
    ws = load_ws(cap)
    seen_close, reopened = False, []
    for row in ws:
        if row.get('event') == 'close':
            seen_close = True
            continue
        p = row.get('parsed')
        if seen_close and row.get('dir') == 'sent' and isinstance(p, dict) \
                and p.get('type') == 'open':
            reopened.append(redact({'endpoint': p.get('endpoint'),
                                    'payload': p.get('payload', {})}, uuid_map))
    if reopened:
        fixtures['ws-session-reconnect-open-sequence'] = reopened
    return fixtures

BUILDERS = {
    'rc2-page-load': page_load_fixtures,
    'rc2-stream-message': stream_fixtures,
    'rc2-tool-approval': approval_fixtures,
    'rc2-steering': steering_fixtures,
    'rc2-session-archive': session_archive_fixtures,
    'rc2-session-workspace': session_workspace_fixtures,
    'rc2-session-history': session_history_fixtures,
    'rc2-session-errors': session_error_fixtures,
    'rc2-session-reconnect': session_reconnect_fixtures,
}

def main(labels):
    out = ROOT / 'fixtures'
    out.mkdir(parents=True, exist_ok=True)
    uuid_map = {}
    fixtures, order = {}, []
    for label in labels:
        cap = ROOT / 'captures' / label
        if not (cap / 'ws.jsonl').exists():
            print(f'skip {label}: no capture at {cap}')
            continue
        produced = BUILDERS[label](cap, uuid_map)
        fixtures.update(produced)
        order.extend(name for name in produced if name not in order)

    for name in order:
        (out / f'{name}.json').write_text(
            json.dumps(fixtures[name], ensure_ascii=False, indent=2) + '\n',
            encoding='utf-8', newline='\n')

    manifest = {
        'DSH_PROTOCOL_TARGET': TARGET,
        'source_captures': [l for l in labels if (ROOT / 'captures' / l / 'ws.jsonl').exists()],
        'fixtures': order,
    }
    (out / 'manifest.json').write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + '\n',
        encoding='utf-8', newline='\n')
    print(f'wrote {len(order)} fixtures + manifest → {out}')

if __name__ == '__main__':
    main(sys.argv[1:] or DEFAULT_CAPTURES)
