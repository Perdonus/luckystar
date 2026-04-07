#!/usr/bin/env python3
import base64
import io
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'raki_suta'
PUBLIC = ROOT / 'app' / 'public'
DATA = PUBLIC / 'data'
THUMBS = DATA / 'thumbs'

DATA.mkdir(parents=True, exist_ok=True)
THUMBS.mkdir(parents=True, exist_ok=True)

PNG_1X1 = base64.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlH0iEAAAAASUVORK5CYII='
)


def parse_meta(name: str):
    stem = Path(name).stem
    display = stem.replace('_manga-chan.me', '').replace('_', ' ')
    m = re.search(r'_v(\d+)_ch([\d.]+)', stem)
    if m:
        volume = int(m.group(1))
        chapter = m.group(2)
        order = float(chapter)
        group = f'Version {volume}'
        title = f'v{volume} · Chapter {chapter}'
    else:
        m2 = re.search(r'tom-(\d+)-glava-([\d.]+)', stem)
        if m2:
            volume = int(m2.group(1))
            chapter = m2.group(2)
            order = float(chapter)
            group = f'Tom {volume}'
            title = f'Tom {volume} · Chapter {chapter}'
        else:
            m3 = re.search(r'(\d+)-(\d+)', stem)
            if m3:
                volume = int(m3.group(1))
                chapter = m3.group(2)
                order = float(chapter)
                group = f'Tom {volume}'
                title = f'Tom {volume} · Chapter {chapter}'
            else:
                volume = 999
                chapter = stem
                order = 0
                group = 'Other'
                title = display
    return {
        'id': re.sub(r'[^a-zA-Z0-9]+', '-', stem.lower()).strip('-'),
        'file': name,
        'title': title,
        'displayTitle': display,
        'group': group,
        'volume': volume,
        'chapter': str(chapter),
        'sortKey': [volume, order, display],
    }


def natural_key(s: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r'(\d+)', s)]


chapters = []
for zip_path in sorted(SRC.glob('*.zip')):
    meta = parse_meta(zip_path.name)
    with zipfile.ZipFile(zip_path) as zf:
        names = [n for n in zf.namelist() if n.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))]
        names.sort(key=natural_key)
        page_count = len(names)
        thumb_name = f"{meta['id']}.jpg"
        thumb_path = THUMBS / thumb_name
        if names:
            try:
                data = zf.read(names[0])
                thumb_path.write_bytes(data)
            except Exception:
                thumb_path.write_bytes(PNG_1X1)
        else:
            thumb_path.write_bytes(PNG_1X1)
        meta.update({
            'pageCount': page_count,
            'thumb': f'data/thumbs/{thumb_name}',
            'size': zip_path.stat().st_size,
        })
    chapters.append(meta)

chapters.sort(key=lambda x: x['sortKey'])
for i, ch in enumerate(chapters):
    ch['index'] = i
    ch.pop('sortKey', None)

manifest = {
    'title': 'Lucky Star Library',
    'chapterCount': len(chapters),
    'chapters': chapters,
}

(DATA / 'chapters.json').write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
print(f'Wrote {len(chapters)} chapters to {DATA / "chapters.json"}')
