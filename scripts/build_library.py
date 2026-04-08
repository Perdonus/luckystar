#!/usr/bin/env python3
import json
import re
import shutil
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PUBLIC = ROOT / 'app' / 'public'
DATA_DIR = PUBLIC / 'data'
LIBRARY_DIR = PUBLIC / 'library'
SERIES_DIR = LIBRARY_DIR / 'series'
BANNERS_DIR = LIBRARY_DIR / 'banners'

LUCKY_SRC = ROOT / 'raki_suta'
CLAYMORE_SRC = Path('/srv/torrents/complete/claymore(manga)')
IMAGE_EXTS = {'.jpg', '.jpeg', '.png', '.webp', '.gif', '.avif'}
SKIP_NAME_PARTS = ('manga-chan.me.txt', 'avifdec.exe', 'преобразовать avif', '.db')


def natural_key(text: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r'(\d+)', text)]


def slugify(text: str) -> str:
    text = text.lower().replace('ё', 'е')
    text = re.sub(r'[^a-z0-9]+', '-', text)
    return text.strip('-')


def ensure_dir(path: Path):
    path.mkdir(parents=True, exist_ok=True)


def ensure_clean_dir(path: Path):
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def get_pages(archive: Path, out_dir: Path, extractor):
    existing = list_existing_pages(out_dir)
    if existing:
        return existing
    return extractor(archive, out_dir)


def rel_url(path: Path) -> str:
    rel = path.relative_to(PUBLIC).as_posix()
    return '/luckystar/' + rel


def write_svg(path: Path, title: str, subtitle: str, fill_a: str, fill_b: str):
    ensure_dir(path.parent)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900" viewBox="0 0 1600 900">
  <defs>
    <linearGradient id="g" x1="0" x2="1" y1="0" y2="1">
      <stop offset="0%" stop-color="{fill_a}"/>
      <stop offset="100%" stop-color="{fill_b}"/>
    </linearGradient>
  </defs>
  <rect width="1600" height="900" fill="url(#g)"/>
  <rect x="72" y="72" width="1456" height="756" rx="36" fill="rgba(0,0,0,0.14)" stroke="rgba(255,255,255,0.18)"/>
  <text x="100" y="410" fill="#fff" font-family="Inter, Arial, sans-serif" font-size="112" font-weight="700">{title}</text>
  <text x="104" y="490" fill="rgba(255,255,255,0.82)" font-family="Inter, Arial, sans-serif" font-size="42">{subtitle}</text>
</svg>'''
    path.write_text(svg, encoding='utf-8')


def is_image_name(name: str) -> bool:
    lower = name.lower()
    if any(part in lower for part in SKIP_NAME_PARTS):
        return False
    return Path(name).suffix.lower() in IMAGE_EXTS and not name.endswith('/')


def list_existing_pages(out_dir: Path) -> list[dict]:
    if not out_dir.exists():
        return []
    files = sorted([p for p in out_dir.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_EXTS], key=lambda p: natural_key(p.name))
    return [
        {
            'index': idx,
            'name': path.name,
            'width': None,
            'height': None,
            'size': path.stat().st_size,
            'url': rel_url(path),
        }
        for idx, path in enumerate(files, start=1)
    ]


def extract_zip_images(archive: Path, out_dir: Path) -> list[dict]:
    cached = list_existing_pages(out_dir)
    if cached:
        return cached
    ensure_clean_dir(out_dir)
    pages = []
    with zipfile.ZipFile(archive) as zf:
        names = [name for name in zf.namelist() if is_image_name(name)]
        names.sort(key=natural_key)
        for name in names:
            ext = Path(name).suffix.lower()
            dest = out_dir / f'{len(pages) + 1:03d}{ext}'
            try:
                dest.write_bytes(zf.read(name))
            except Exception:
                continue
            pages.append({
                'index': len(pages) + 1,
                'name': dest.name,
                'width': None,
                'height': None,
                'size': dest.stat().st_size,
                'url': rel_url(dest),
            })
    return pages


def extract_rar_images(archive: Path, out_dir: Path) -> list[dict]:
    cached = list_existing_pages(out_dir)
    if cached:
        return cached
    temp_dir = Path(tempfile.mkdtemp(prefix='luckystar_claymore_'))
    try:
        subprocess.run(['7z', 'x', '-y', f'-o{temp_dir}', str(archive)], capture_output=True, text=True)
        files = sorted(
            [path for path in temp_dir.rglob('*') if path.is_file() and is_image_name(path.name)],
            key=lambda path: natural_key(str(path.relative_to(temp_dir))),
        )
        if not files:
            return []
        ensure_clean_dir(out_dir)
        pages = []
        for source in files:
            ext = source.suffix.lower()
            dest = out_dir / f'{len(pages) + 1:03d}{ext}'
            shutil.move(str(source), str(dest))
            pages.append({
                'index': len(pages) + 1,
                'name': dest.name,
                'width': None,
                'height': None,
                'size': dest.stat().st_size,
                'url': rel_url(dest),
            })
        return pages
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def parse_lucky(file_name: str):
    stem = Path(file_name).stem
    for rx in [
        re.compile(r'.*?_v(?P<volume>\d+)_ch(?P<chapter>[\d.]+)', re.IGNORECASE),
        re.compile(r'.*?-tom-(?P<volume>\d+)-glava-(?P<chapter>[\d.]+)', re.IGNORECASE),
        re.compile(r'(?P<volume>\d+)-(?P<chapter>[\d.]+)', re.IGNORECASE),
    ]:
        match = rx.search(stem)
        if match:
            volume = int(match.group('volume'))
            chapter = match.group('chapter')
            return f'v{volume}', volume, chapter
    return 'v1', 1, stem


def parse_claymore(volume_dir: Path, archive_name: str):
    volume_match = re.search(r'vol\.\s*(\d+)', volume_dir.name, re.IGNORECASE)
    volume = int(volume_match.group(1)) if volume_match else 0
    stem = Path(archive_name).stem
    chapter_num = None
    for rx in [
        re.compile(r'_(\d{3})(?:_|$)', re.IGNORECASE),
        re.compile(r'clmr_(\d+)', re.IGNORECASE),
        re.compile(r'глава\s*(\d+(?:\.\d+)?)', re.IGNORECASE),
        re.compile(r'scene_(\d+)', re.IGNORECASE),
    ]:
        match = rx.search(stem)
        if match:
            chapter_num = match.group(1)
            break
    if chapter_num is None and 'последняя глава' in stem.lower():
        chapter_num = '155'
    label = f'Глава {chapter_num}' if chapter_num else stem
    return f'vol-{volume:02d}', volume, str(chapter_num) if chapter_num is not None else stem, label, stem


def chapter_sort_value(value: str):
    if re.fullmatch(r'\d+(?:\.\d+)?', value):
        return (0, float(value))
    return (1, natural_key(value))


def build_lucky() -> tuple[dict, list[dict]]:
    series_root = SERIES_DIR / 'lucky-star'
    ensure_dir(series_root)
    releases: dict[str, list[str]] = {}
    chapters = []
    for archive in sorted(LUCKY_SRC.glob('*.zip'), key=lambda path: natural_key(path.name)):
        release_id, volume, chapter_num = parse_lucky(archive.name)
        chapter_slug = slugify(Path(archive.name).stem)
        pages = get_pages(archive, series_root / release_id / chapter_slug, extract_zip_images)
        if not pages:
            continue
        chapter = {
            'id': chapter_slug,
            'seriesId': 'lucky-star',
            'releaseId': release_id,
            'volume': volume,
            'chapter': str(chapter_num),
            'chapterSort': float(chapter_num) if re.fullmatch(r'\d+(?:\.\d+)?', str(chapter_num)) else None,
            'title': f'Глава {chapter_num}',
            'shortTitle': f'Глава {chapter_num}',
            'sourceName': archive.name,
            'thumb': pages[0]['url'],
            'pageCount': len(pages),
            'pages': pages,
        }
        chapters.append(chapter)
        releases.setdefault(release_id, []).append(chapter['id'])
    chapter_index = {chapter['id']: chapter for chapter in chapters}
    release_items = []
    for release_id, chapter_ids in sorted(releases.items(), key=lambda item: natural_key(item[0])):
        release_items.append({
            'id': release_id,
            'title': release_id.upper(),
            'type': 'version',
            'number': int(release_id[1:]),
            'chapterIds': sorted(chapter_ids, key=lambda chapter_id: chapter_sort_value(chapter_index[chapter_id]['chapter'])),
        })
    return {
        'id': 'lucky-star',
        'slug': 'lucky-star',
        'title': 'Lucky Star',
        'banner': rel_url(BANNERS_DIR / 'lucky-star.svg'),
        'releaseLabel': 'Версии',
        'chapterLabel': 'Главы',
        'releases': release_items,
        'chapterCount': len(chapters),
    }, chapters


def build_claymore() -> tuple[dict, list[dict]]:
    series_root = SERIES_DIR / 'claymore'
    ensure_dir(series_root)
    chapters = []
    release_items = []
    for volume_dir in sorted([path for path in CLAYMORE_SRC.iterdir() if path.is_dir()], key=lambda path: natural_key(path.name)):
        volume_match = re.search(r'vol\.\s*(\d+)', volume_dir.name, re.IGNORECASE)
        volume = int(volume_match.group(1)) if volume_match else 0
        release_id = f'vol-{volume:02d}'
        release_chapter_ids = []
        for archive in sorted(volume_dir.glob('*.rar'), key=lambda path: natural_key(path.name)):
            _, _, chapter_num, title, raw_title = parse_claymore(volume_dir, archive.name)
            chapter_slug = slugify(f'{release_id}-{Path(archive.name).stem}')
            pages = get_pages(archive, series_root / release_id / chapter_slug, extract_rar_images)
            if not pages:
                continue
            chapter = {
                'id': chapter_slug,
                'seriesId': 'claymore',
                'releaseId': release_id,
                'volume': volume,
                'chapter': chapter_num,
                'chapterSort': float(chapter_num) if re.fullmatch(r'\d+(?:\.\d+)?', chapter_num) else None,
                'title': title,
                'shortTitle': raw_title,
                'sourceName': archive.name,
                'thumb': pages[0]['url'],
                'pageCount': len(pages),
                'pages': pages,
            }
            chapters.append(chapter)
            release_chapter_ids.append(chapter['id'])
        release_items.append({
            'id': release_id,
            'title': f'Том {volume}',
            'type': 'volume',
            'number': volume,
            'chapterIds': sorted(release_chapter_ids, key=lambda chapter_id: chapter_sort_value(next(ch['chapter'] for ch in chapters if ch['id'] == chapter_id))),
        })
    return {
        'id': 'claymore',
        'slug': 'claymore',
        'title': 'Claymore',
        'banner': rel_url(BANNERS_DIR / 'claymore.svg'),
        'releaseLabel': 'Тома',
        'chapterLabel': 'Главы',
        'releases': release_items,
        'chapterCount': len(chapters),
    }, chapters


def write_series_payload(series: dict, chapter_index: dict[str, dict], generated_at: str):
    payload = {
        'schemaVersion': 3,
        'generatedAt': generated_at,
        'series': series,
        'chapters': [
            chapter_index[chapter_id]
            for release in series['releases']
            for chapter_id in release['chapterIds']
            if chapter_id in chapter_index
        ],
    }
    (DATA_DIR / f"{series['slug']}.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')


def build_library():
    ensure_dir(DATA_DIR)
    ensure_dir(BANNERS_DIR)
    write_svg(BANNERS_DIR / 'lucky-star.svg', 'Lucky Star', 'Минималистичная библиотека', '#f59e0b', '#ef4444')
    write_svg(BANNERS_DIR / 'claymore.svg', 'Claymore', 'Минималистичная библиотека', '#334155', '#111827')

    lucky_series, lucky_chapters = build_lucky()
    claymore_series, claymore_chapters = build_claymore()
    chapters = lucky_chapters + claymore_chapters
    chapter_index = {chapter['id']: chapter for chapter in chapters}

    generated_at = datetime.now(timezone.utc).isoformat()
    library = {
        'schemaVersion': 3,
        'generatedAt': generated_at,
        'series': [lucky_series, claymore_series],
        'chapters': chapters,
        'chapterIndex': chapter_index,
    }
    (DATA_DIR / 'library.json').write_text(json.dumps(library, ensure_ascii=False, indent=2), encoding='utf-8')
    write_series_payload(lucky_series, chapter_index, generated_at)
    write_series_payload(claymore_series, chapter_index, generated_at)
    print(f'built library: lucky={len(lucky_chapters)} claymore={len(claymore_chapters)} total={len(chapters)}')


if __name__ == '__main__':
    build_library()
