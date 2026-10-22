#!/usr/bin/env python3
"""Package our plain-JS client factory using DSH's documented shared-module format."""
from pathlib import Path
import json
import shutil
root = Path(__file__).resolve().parents[1]
package = root / 'mobile-shell'
manifest = json.loads((package / 'package.json').read_text())
css = (package / 'src/mobile.css').read_text()
client = (package / 'src/client.js').read_text()
output = ('window.__ModuleLoader__.load({id:' + json.dumps(manifest['name']) + ',factory:(require)=>{\n'
          'var module={exports:{}};var exports=module.exports;\n'
          'const MOBILE_STYLE=' + json.dumps(css, ensure_ascii=False) + ';\n' + client
          + '\nreturn module.exports;\n}});\n')
(package / 'lib/client.js').write_text(output)
destination = root / 'app/src/main/assets/addons/mobile-shell'
for relative in ['package.json', 'lib/index.js', 'lib/client.js']:
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(package / relative, target)
print(f'Mobile client assets ready: {destination}')
