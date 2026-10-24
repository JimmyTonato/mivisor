"""Diagnóstico puntual: robots + una página, sin crawling ni integración de producción."""
import json
import os
import re
import time
from pathlib import Path
from urllib import error, parse, request, robotparser

from bs4 import BeautifulSoup

BASE = 'https://www.futbolenlatv.es'
UA = 'MiVisor/4'
PATHS = ['/deporte', '/deporte/futbol', '/deporte/tenis', '/deporte/motor', '/deporte/baloncesto']
REPORT = {'runner': {}, 'robots': {}, 'permissions': {}, 'page': None}
LOG = []


def emit(key, value):
    line = f'{key}: {value}'
    print(line, flush=True)
    LOG.append(line)
    escaped = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::notice title=Diagnóstico TV::{escaped}', flush=True)


def finish(result):
    REPORT['result'] = result
    emit('RESULTADO', result)
    Path('tv-source-report.json').write_text(json.dumps(REPORT, ensure_ascii=False, indent=2))
    Path('tv-source-log.txt').write_text('\n'.join(LOG) + '\n')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as f:
            f.write('```text\n' + '\n'.join(LOG) + '\n```\n')


def get(url, accept, handler=None):
    # Sin reintentos, sin proxies configurados por el diagnóstico.
    opener = request.build_opener(request.ProxyHandler({}), *([handler] if handler else []))
    start = time.monotonic()
    req = request.Request(url, headers={'User-Agent': UA, 'Accept': accept})
    try:
        response = opener.open(req, timeout=20)
    except error.HTTPError as exc:
        response = exc
    with response:
        body = response.read(12 * 1024 * 1024 + 1)
        info = {'http': response.code, 'final_url': response.geturl(),
                'content_type': response.headers.get('Content-Type', ''),
                'bytes': len(body), 'seconds': round(time.monotonic() - start, 3)}
        charset = response.headers.get_content_charset() or 'utf-8'
    return info, body, body.decode(charset, errors='replace')


def main():
    for line in Path('/etc/os-release').read_text().splitlines():
        if line.startswith(('PRETTY_NAME=', 'VERSION_ID=')):
            k, v = line.split('=', 1)
            REPORT['runner'][k] = v.strip('"')
    emit('GitHub Actions runner', REPORT['runner'])
    emit('USER_AGENT', UA)
    try:
        info, body, text = get(BASE + '/robots.txt', 'text/plain')
    except Exception as exc:
        emit('robots.txt ERROR', str(exc))
        finish('NO VIABLE TÉCNICAMENTE — no se pudieron comprobar permisos robots')
        return
    REPORT['robots'] = info
    Path('robots.txt').write_bytes(body)
    emit('robots.txt HTTP', info['http'])
    emit('robots.txt Final URL', info['final_url'])
    emit('robots.txt Content-Type', info['content_type'])
    emit('robots.txt CONTENIDO', text)
    if info['http'] != 200 or 'html' in info['content_type'].lower():
        finish('NO VIABLE TÉCNICAMENTE — robots no verificable; no se solicita /deporte')
        return
    robots = robotparser.RobotFileParser(BASE + '/robots.txt')
    robots.parse(text.splitlines())
    for path in PATHS:
        allowed = robots.can_fetch(UA, BASE + path)
        REPORT['permissions'][path] = 'ALLOWED' if allowed else 'DISALLOWED'
        emit('ROBOTS ' + path, REPORT['permissions'][path])
    if REPORT['permissions']['/deporte'] != 'ALLOWED':
        emit('GET /deporte', 'NO REALIZADO: robots lo prohíbe')
        finish('NO VIABLE POR ROBOTS')
        return

    class CheckedRedirect(request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            # Nunca seguir una redirección de página a una ruta prohibida o a otro origen.
            if parse.urlsplit(newurl).netloc != parse.urlsplit(BASE).netloc or not robots.can_fetch(UA, newurl):
                raise RuntimeError('Redirección detenida: fuera del origen o no permitida por robots')
            return super().redirect_request(req, fp, code, msg, headers, newurl)

    try:
        info, body, text = get(BASE + '/deporte', 'text/html,application/xhtml+xml', CheckedRedirect())
    except Exception as exc:
        emit('GET /deporte ERROR', str(exc))
        finish('NO VIABLE TÉCNICAMENTE')
        return
    REPORT['page'] = info
    Path('tv-source.html').write_bytes(body)
    for key, value in info.items():
        emit('GET /deporte ' + key, value)
    emit('HTML_BYTES', len(body))
    soup = BeautifulSoup(text, 'html.parser')
    for node in soup(['script', 'style', 'nav', 'header', 'footer']):
        node.decompose()
    visible = soup.get_text(' ', strip=True)
    terms = {'M_PLUS_LALIGA': r'M\+\s*LALIGA', 'MOVISTAR': r'Movistar', 'DAZN': r'DAZN',
             'LALIGA_TV_BAR': r'LaLiga\s*TV\s*Bar', 'CHAMPIONS': r'Champions',
             'MOTOGP': r'MotoGP', 'FORMULA1': r'F[oó]rmula\s*1', 'ATP': r'\bATP\b', 'WTA': r'\bWTA\b'}
    REPORT['contains'] = {}
    for name, pattern in terms.items():
        found = bool(re.search(pattern, visible, re.I) or any(re.search(pattern, x.get('alt', ''), re.I) for x in soup.find_all('img')))
        REPORT['contains'][name] = found
        emit('CONTAINS_' + name, str(found).lower())
    html_soup = BeautifulSoup(text, 'html.parser')
    structured = []
    for script in html_soup.find_all('script', type='application/ld+json'):
        try:
            structured.append(json.loads(script.string or script.get_text()))
        except ValueError:
            pass
    REPORT['json_ld'] = structured
    emit('JSON_LD_BLOCKS', len(structured))
    rows = []
    for row in soup.select('tr, article, li'):
        value = row.get_text(' ', strip=True)
        times = re.findall(r'\b(?:[01]\d|2[0-3]):[0-5]\d\b', value)
        if not times or len(value) > 3000:
            continue
        images = [{'alt': x.get('alt', ''), 'title': x.get('title', ''),
                   'url': x.get('src') or x.get('data-src')} for x in row.find_all('img')]
        rows.append({'hours': times, 'text': value, 'images': images,
                     'cells': [c.get_text(' ', strip=True) for c in row.find_all(['td', 'th'])]})
    REPORT['event_candidates'] = len(rows)
    REPORT['sample'] = rows[:5]
    emit('EVENT_CANDIDATES', len(rows))
    for i, row in enumerate(rows[:5], 1):
        emit(f'EVENTO {i}', json.dumps(row, ensure_ascii=False))
    hosts = set()
    for image in soup.find_all('img'):
        for attr in ['src', 'data-src', 'data-original']:
            url = image.get(attr)
            if url:
                host = parse.urlsplit(parse.urljoin(info['final_url'], url)).netloc
                if host:
                    hosts.add(host)
    REPORT['logo_hosts'] = sorted(hosts)
    emit('LOGOS_FOUND', str(bool(hosts)).lower())
    emit('LOGO_HOST', ', '.join(sorted(hosts)))
    channel_evidence = any(REPORT['contains'][x] for x in ['M_PLUS_LALIGA', 'MOVISTAR', 'DAZN', 'LALIGA_TV_BAR'])
    emit('DATOS_DEPORTIVOS_PRESENTES', 'sí' if rows else 'indeterminado')
    emit('CANALES_EN_HTML', 'sí' if channel_evidence else 'indeterminado')
    emit('NECESITA_JAVASCRIPT', 'no para la muestra' if rows and channel_evidence else 'indeterminado')
    viable = info['http'] == 200 and rows and channel_evidence
    finish('VIABLE' if viable else 'NO VIABLE TÉCNICAMENTE — no se confirmó una muestra de eventos y canales en HTML')


if __name__ == '__main__':
    main()
