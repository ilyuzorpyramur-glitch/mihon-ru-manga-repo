#!/usr/bin/env python3
"""Глубинная проверка источников: карточки, обложки, пагинация, поиск, жанры.
HTTP-уровень. Запуск: python3 tools/deep-check.py [--json out]"""
import argparse, glob, json, os, re, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
BAD_HOSTS = ('github.com', 'anilist.co', 'myanimelist.net', 'mangaupdates.com',
             'kitsu.app', 'shikimori.io', 'anime-planet.com', 'universe.leagueoflegends')

def gradle(d):
    s = open(d + '/build.gradle.kts', encoding='utf-8', errors='replace').read()
    return (re.search(r'name\s*=\s*"([^"]+)"', s).group(1),
            re.search(r'versionCode\s*=\s*(\d+)', s).group(1))

def kt_all(d):
    return '\n'.join(open(f, encoding='utf-8', errors='replace').read()
                     for f in glob.glob(d + '/src/**/*.kt', recursive=True))

def resolve_base(d, kt):
    m = re.search(r'baseUrl\s*=\s*"([^"]+)"', open(d + '/build.gradle.kts', encoding='utf-8', errors='replace').read())
    if m and '$' not in m.group(1):
        return m.group(1).rstrip('/')
    for u in re.findall(r'https?://[A-Za-z0-9.\\-]+', kt):
        if not any(b in u for b in BAD_HOSTS):
            return u.rstrip('/')
    return None

def block(kt, fname):
    i = kt.find(f'fun {fname}(')
    if i < 0:
        return None
    j = kt.find('{', i)
    eq = kt.find('=', i)
    if j < 0 or (0 <= eq < j):
        seg = kt[i:i + 600]
        return seg if 'GET(' in seg or 'POST(' in seg else None
    depth, k = 1, j + 1
    while depth > 0 and k < len(kt):
        depth += (kt[k] == '{') - (kt[k] == '}')
        k += 1
    return kt[j:k]

def first_url(fragment):
    if not fragment:
        return None
    for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', fragment):
        if len(lit) > 10 and ('://' in lit or '$baseUrl' in lit or '${baseUrl}' in lit):
            return lit
    return None

def build_url(lit, base, page):
    if lit is None:
        return None
    u = lit
    u = re.sub(r'\$\{\s*page\s*-\s*1\s*\}', str(20 * (page - 1)), u)
    u = re.sub(r'\$\{\s*(?:20|50)\s*\*\s*\(\s*page\s*-\s*1\s*\)\s*\}', str(20 * (page - 1)), u)
    u = u.replace('$baseUrl', base).replace('${baseUrl}', base)
    u = u.replace('$page', str(page)).replace('${page}', str(page))
    if '$' in u or '${' in u:
        return None
    return u

def fetch(url, timeout=25):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru,en;q=0.8"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            ct = r.headers.get('Content-Type', '')
            body = r.read(600_000)
            return r.status, body.decode('utf-8', 'replace'), ('json' if 'json' in ct else 'html')
    except urllib.error.HTTPError as e:
        return e.code, '', 'html'
    except Exception as e:
        return 0, str(e), 'html'

def cards(body, kind):
    if kind == 'json':
        try:
            data = json.loads(body)
        except Exception:
            return 0, 0
        lists = [v for v in (data.values() if isinstance(data, dict) else [data]) if isinstance(v, list)]
        blob = json.dumps(data, ensure_ascii=False)
        covers = len(set(re.findall(r'https?://[^"\\]+\\.(?:jpg|jpeg|png|webp)', blob)))
        return sum(len(v) for v in lists), covers
    hrefs = set(re.findall(r'<a[^>]+href="([^"]+)"', body))
    imgs = set(re.findall(r'<img[^>]+(?:src|data-src|data-lazy)="([^"]+)"', body))
    imgs |= set(re.findall(r"url\(['\"]?(https?://[^'\"\\)]+)", body))
    covers = {i for i in imgs if re.search(r'\.(jpg|jpeg|png|webp)', i, re.I) or '/thumb' in i or 'cover' in i}
    return len(hrefs), len(covers)

def probe(url):
    try:
        code, body, kind = fetch(url)
    except Exception:
        return {'code': 0, 'cards': 0, 'covers': 0, 'ok': False}
    n, cov = cards(body, kind)
    return {'code': code, 'cards': n, 'covers': cov, 'ok': bool(n and cov)}

def check(name):
    d = f'src/ru/{name}'
    disp, ver = gradle(d)
    kt = kt_all(d)
    base = resolve_base(d, kt)
    r = {'name': name, 'display': disp, 'ver': ver, 'base': base}
    if not base:
        r['verdict'] = 'НЕТ БАЗЫ'
        return r
    if 'multisrc.galleryadults' in kt:
        v = probe(base)
        r.update(popular=v, verdict='OK' if v['ok'] else 'ПРОВЕРИТЬ БАЗУ')
        return r
    frag = block(kt, 'popularMangaRequest') or block(kt, 'popularMangaUrl')
    lit = first_url(frag)
    p1 = build_url(lit, base, 1)
    p2 = build_url(lit, base, 2)
    if not p1:
        v = probe(base)
        r.update(popular=v, verdict='БАЗА ' + ('OK' if v['ok'] else 'FAIL') + ' (листинг вручную)')
        return r
    v1 = probe(p1)
    r['popular'] = v1
    if v1['ok'] and p2:
        c2, b2, _ = fetch(p2)
        c1, b1, _ = fetch(p1)
        r['pagination'] = {'page2': c2 == 200, 'differs': c2 == 200 and b1[:3000] != b2[:3000]}
    fsearch = block(kt, 'searchMangaRequest')
    slit = first_url(fsearch)
    if slit:
        s2 = re.sub(r'\$\{?\w*[Qq]uery\w*\}?', urllib.parse.quote('манга'), slit)
        s2 = re.sub(r'\$\{?\w*[Ss]earch\w*\}?', urllib.parse.quote('манга'), s2)
        s2 = re.sub(r'\$\{?\w*[Nn]ame\w*\}?', urllib.parse.quote('манга'), s2)
        su = build_url(s2, base, 1)
        if su:
            r['search'] = probe(su)
    r['genres'] = 'api' if '/api/constants' in kt else ('в коде' if 'getFilterList' in kt or 'FilterList' in kt else 'нет')
    r['verdict'] = 'OK' if v1['ok'] else 'FAIL ЛИСТИНГ'
    return r

names = sorted(os.path.basename(p) for p in glob.glob('src/ru/*'))
with ThreadPoolExecutor(max_workers=8) as ex:
    rows = list(ex.map(check, names))
ok = sum(1 for r in rows if r.get('popular', {}).get('ok'))
pag_ok = sum(1 for r in rows if r.get('pagination', {}).get('differs'))
s_ok = sum(1 for r in rows if r.get('search', {}).get('ok'))
for r in rows:
    p, pg, s = r.get('popular', {}), r.get('pagination', {}), r.get('search', {})
    line = f"{r['name']:20} {p.get('code','-'):>3} cards={p.get('cards','-'):>4} cov={p.get('covers','-'):>3}"
    if pg:
        line += f"  pag2={pg['page2']}/diff={pg['differs']}"
    if s:
        line += f"  search={'OK' if s['ok'] else 'FAIL'}({s['cards']})"
    line += f"  genres={r.get('genres')}  {r.get('verdict','')}"
    print(line)
print(f"\nЛистинг с обложками: {ok}/{len(rows)} · пагинация подтверждена: {pag_ok} · поиск работает: {s_ok}")
a = argparse.ArgumentParser(); a.add_argument('--json'); args = a.parse_args()
if args.json:
    json.dump(rows, open(args.json, 'w'), ensure_ascii=False, indent=1)
