#!/usr/bin/env python3
"""Resize the approved square artwork into legacy and adaptive launcher resources."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
source = root / 'branding/xingdu-icon.png'
res = root / 'app/src/main/res'
with Image.open(source) as artwork:
    assert artwork.width == artwork.height and artwork.width >= 432
    artwork = artwork.convert('RGB')
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96),
                          ('xxhdpi', 144), ('xxxhdpi', 192)]:
        folder = res / f'mipmap-{density}'
        folder.mkdir(parents=True, exist_ok=True)
        artwork.resize((size, size), Image.Resampling.LANCZOS).save(folder / 'ic_launcher.png')
    folder = res / 'drawable-nodpi'
    folder.mkdir(parents=True, exist_ok=True)
    artwork.resize((432, 432), Image.Resampling.LANCZOS).save(folder / 'ic_launcher_art.png')
folder = res / 'mipmap-anydpi-v26'
folder.mkdir(parents=True, exist_ok=True)
(folder / 'ic_launcher.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/black" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
''')
folder = res / 'drawable'
folder.mkdir(parents=True, exist_ok=True)
(folder / 'ic_launcher_foreground.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/ic_launcher_art" android:gravity="fill" />
''')
print('Prepared Xingdu legacy and adaptive launcher resources from', source)
