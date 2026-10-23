#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Robot's user-visible text inventory: every string literal in the Kotlin sources of the
six modules, classified, counted, and mapped to the areas of 1.1's French work.

    python3 -I robot/tools/strings_inventory.py robot                  # the tables (§3.2)
    python3 -I robot/tools/strings_inventory.py robot --area memory    # the guarded literals of one area, with their lines
    python3 -I robot/tools/strings_inventory.py robot --check          # compares with the guard's baselines
    python3 -I robot/tools/strings_inventory.py robot --rows out.tsv   # every literal, one row each

The rules (kinds, brands, prompt and diagnostic files, the areas) are in robot/l10n/scan_rules.json, which the guard
(app/src/test/.../l10n/UserTextGuardTest.kt, a JVM port of this lexer and classifier) reads too. This script is for
reports; the test is the guard. Nothing here changes a file except --rows' output.

The lexer (§3.1): it skips // and nested /* */ comments; reads "..." with escapes, \"\"\"...\"\"\" raw strings and char
literals; walks ${...} templates with nested braces and strings (each hole becomes {}; a string inside a template is a
message of its own); and merges literals joined by + (across lines too) into one message.
"""
import argparse
import collections
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

IDENT = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
ESCAPES = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', "'": "'", '\\': '\\', '$': '$', 'b': '\b'}


class Lit:
    __slots__ = ('start', 'end', 'line', 'text', 'raw', 'holes', 'inner')

    def __init__(self, start, line):
        self.start = start
        self.line = line
        self.end = start
        self.text = []
        self.raw = False
        self.holes = 0
        self.inner = []


def scan(src):
    """The literals of [src], and its code mask: code characters kept, comments and string contents blanked, each
    literal's opening quote kept at its start."""
    n = len(src)
    lits = []
    code = [' '] * n

    def skip_char(j):
        if src.startswith("'\\u", j):
            return j + 8
        if src.startswith("'\\", j):
            return j + 4
        k = src.find("'", j + 1)
        return k + 1 if k != -1 and k - j <= 3 else j + 1

    def read_string(i, line):
        raw = src.startswith('"""', i)
        lit = Lit(i, line)
        lit.raw = raw
        text = []
        i += 3 if raw else 1
        while i < n:
            c = src[i]
            if raw and src.startswith('"""', i):
                j = i
                while j + 3 < n and src[j + 3] == '"':
                    text.append('"')
                    j += 1
                i = j + 3
                break
            if not raw and c == '"':
                i += 1
                break
            if not raw and c == '\\' and i + 1 < n:
                e = src[i + 1]
                if e == 'u':
                    try:
                        text.append(chr(int(src[i + 2:i + 6], 16)))
                    except ValueError:
                        text.append('?')
                    i += 6
                    continue
                text.append(ESCAPES.get(e, e))
                i += 2
                continue
            if c == '$' and i + 1 < n and src[i + 1] == '{':
                depth = 0
                j = i + 1
                while j < n:
                    cj = src[j]
                    if cj == '{':
                        depth += 1
                        j += 1
                    elif cj == '}':
                        depth -= 1
                        j += 1
                        if depth == 0:
                            break
                    elif cj == '"':
                        sub, j, line = read_string(j, line)
                        lit.inner.append(sub)
                    elif cj == "'":
                        j = skip_char(j)
                    elif cj == '\n':
                        line += 1
                        j += 1
                    else:
                        j += 1
                text.append('{}')
                lit.holes += 1
                i = j
                continue
            if c == '$' and i + 1 < n:
                m = IDENT.match(src, i + 1)
                if m:
                    text.append('{}')
                    lit.holes += 1
                    i = m.end()
                    continue
            if c == '\n':
                line += 1
            text.append(c)
            i += 1
        lit.end = i
        # "😀" escapes: one character, as the JVM reads them.
        lit.text = ''.join(text).encode('utf-16-le', 'surrogatepass').decode('utf-16-le', 'replace')
        return lit, i, line

    i = 0
    line = 1
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            k = src.find('\n', i)
            i = n if k == -1 else k
            continue
        if src.startswith('/*', i):
            depth = 0
            while i < n:
                if src.startswith('/*', i):
                    depth += 1
                    i += 2
                elif src.startswith('*/', i):
                    depth -= 1
                    i += 2
                    if depth == 0:
                        break
                else:
                    if src[i] == '\n':
                        line += 1
                    i += 1
            continue
        if c == '"':
            lit, i, line = read_string(i, line)
            lits.append(lit)
            code[lit.start] = '"'
            continue
        if c == "'":
            i = skip_char(i)
            continue
        if c == '\n':
            line += 1
        code[i] = c
        i += 1
    return lits, ''.join(code)


def ident_char(c, extra):
    return c.isalnum() or c in extra


def enclosing_call(code, pos):
    """The call (an identifier chain) whose parentheses hold [pos], '{name}' or '{block}' for a lambda or block, '[' for a
    collection literal; and the named argument (or assigned name) the literal is the value of, '' when none."""
    depth = 0
    j = pos - 1
    named = None
    while j >= 0:
        c = code[j]
        if c in ')]}':
            depth += 1
        elif c in '([{':
            if depth == 0:
                if c == '(':
                    k = j - 1
                    while k >= 0 and code[k] in ' \t\n':
                        k -= 1
                    e = k + 1
                    while k >= 0 and ident_char(code[k], '_.?!'):
                        k -= 1
                    return code[k + 1:e].strip('.?!'), named
                if c == '{':
                    k = j - 1
                    while k >= 0 and code[k] in ' \t\n':
                        k -= 1
                    if k >= 0 and code[k] == ')':
                        return '{block}', named
                    e = k + 1
                    while k >= 0 and ident_char(code[k], '_.?!'):
                        k -= 1
                    return '{' + code[k + 1:e].strip('.?!') + '}', named
                return '[', named
            depth -= 1
        elif c == ',' and depth == 0 and named is None:
            named = ''
        elif c == '=' and depth == 0 and named is None:
            if j > 0 and code[j - 1] in '=!<>':
                pass
            elif j + 1 < len(code) and code[j + 1] == '=':
                pass
            else:
                k = j - 1
                while k >= 0 and code[k] in ' \t':
                    k -= 1
                e = k + 1
                while k >= 0 and ident_char(code[k], '_'):
                    k -= 1
                named = code[k + 1:e] or ''
        j -= 1
    return '', named


def line_prefix(code, pos):
    k = code.rfind('\n', 0, pos)
    return code[k + 1:pos].strip()


def merge(lits, code):
    """Literals joined by '+' (across lines too) as one message."""
    groups = []
    cur = None
    for lit in lits:
        if cur is not None:
            if code[cur[-1].end:lit.start].strip() == '+':
                cur.append(lit)
                continue
            groups.append(cur)
        cur = [lit]
    if cur:
        groups.append(cur)
    return groups


def messages(src):
    """Every message of a source: dicts with line, text, raw, call, named, before, in_template."""
    lits, code = scan(src)
    out = []
    for g in merge(lits, code):
        first = g[0]
        call, named = enclosing_call(code, first.start)
        out.append({'line': first.line, 'text': ''.join(x.text for x in g), 'raw': any(x.raw for x in g), 'call': call,
                    'named': named or '', 'before': line_prefix(code, first.start)[-100:], 'in_template': False})
        for x in g:
            for sub in x.inner:
                out.append({'line': sub.line, 'text': sub.text, 'raw': sub.raw, 'call': '{template}', 'named': '', 'before': '',
                            'in_template': True})
    return out


class Rules:
    def __init__(self, path):
        r = json.load(open(path, encoding='utf-8'))
        self.r = r
        self.p = {k: re.compile(v, re.S if k == 'logcode_enum' else 0) for k, v in r['patterns'].items()}
        self.exclude = [re.compile(x) for x in r['exclude']]
        self.allowed_dirs = [re.compile(x) for x in r['allowed_dirs']]
        self.marker = re.compile(r['marker'])
        self.marker_any = re.compile(r['marker_any'])
        self.areas = [(a, re.compile(x)) for a, x in r['areas']]
        self.sets = {k: set(r[k]) for k in ('error_calls', 'animate_calls', 'ui_sink_not_visible', 'visible_named', 'tool_calls',
                                             'prompt_files', 'diag_files', 'fr_words', 'en_words', 'brands')}

    def area(self, rel):
        for a, rx in self.areas:
            if rx.search(rel):
                return a
        return None


def words(rules, t):
    return rules.p['word'].findall(t)


FOLD = str.maketrans('àâäéèêëîïôöùûüç', 'aaaeeeeiioouuuc')


def lang_of(rules, t):
    tl = t.lower()
    ws = [w.translate(FOLD) for w in rules.p['lang_word'].findall(tl)]
    fr = sum(1 for w in ws if w in rules.sets['fr_words']) + 2 * len(rules.p['fr_accent'].findall(t)) + 2 * len(rules.p['fr_elision'].findall(t))
    en = sum(1 for w in ws if w in rules.sets['en_words']) + 2 * len(rules.p['en_contraction'].findall(tl))
    return 'FR' if fr > en else 'EN'


def logcode_ranges(rules, src):
    """Line ranges of the enum classes that implement LogCode: their titles are the voice log's (diagnostics)."""
    out = []
    for m in rules.p['logcode_enum'].finditer(src):
        i = m.end()
        depth = 1
        while i < len(src) and depth:
            if src[i] == '{':
                depth += 1
            elif src[i] == '}':
                depth -= 1
            i += 1
        out.append((src.count('\n', 0, m.start()) + 1, src.count('\n', 0, i) + 1))
    return out


def classify(rules, rel, r, ranges):
    """The kind of a message (§3.1's table, first rule that matches): glue, brand, key, log, error, diag, data, prompt,
    regex, addr, format, input, visible or single."""
    P, S = rules.p, rules.sets
    t = r['text']
    f = rel.split('/')[-1]
    call = r['call']
    last = call.split('.')[-1] if call else ''
    named = r['named']
    before = r['before']
    stripped = t.replace('{}', ' ')
    ws = words(rules, stripped)
    if r['in_template'] and len(ws) < 2:
        return 'glue'
    if not ws or (len(ws) == 1 and len(ws[0]) <= 2 and not P['whitespace'].search(stripped.strip())):
        return 'glue'
    if t.strip() in S['brands']:
        return 'brand'
    if last.startswith('animate') or last in S['animate_calls']:
        return 'key'
    if P['log_call'].search(call) or call in ('Log', 'RLog'):
        return 'log'
    if last in S['error_calls'] or before.rstrip().endswith('throw') or 'Exception(' in before[-30:]:
        return 'error'
    if f in S['diag_files'] or (P['enum_call'].search(last) and any(a <= r['line'] <= b for a, b in ranges)):
        return 'diag'
    letters = sum(1 for ch in t if ch.isalpha())
    if (len(t) > 12 and letters / len(t) < 0.35) or P['svg_path'].search(t):
        return 'data'
    if ('SYSTEM' in named or 'PROMPT' in named or P['prompt_call'].search(call) or 'Prompt' in call) and len(ws) >= 2:
        return 'prompt'
    if (P['regex_call'].search(call) and last in ('Regex', 'toRegex', 'compile', 'containsMatchIn', 'rx', 're')) or \
            (r['raw'] and P['regex_syntax'].search(t)):
        return 'regex'
    if P['regex_syntax'].search(t) and (' ' not in t or t.count('\\') >= 2 or '(?' in t):
        return 'regex'
    if P['addr'].search(t.strip()):
        return 'addr'
    if last == 'ofPattern' or (P['format'].search(t.strip()) and len(ws) <= 3 and ' ' not in t.strip()[1:] and not P['latin3'].search(t)):
        return 'format'
    if P['hex'].search(t.strip()):
        return 'data'
    multi = len(ws) >= 2 and P['multi'].search(stripped) is not None
    if last in S['tool_calls'] or (named == 'description' and f == 'Tools.kt'):
        return 'prompt' if multi else 'key'
    sink = P['ui_sink'].search(call) is not None
    sink_visible = sink and last not in S['ui_sink_not_visible']
    if rel.startswith(('app', 'voice')) and (sink_visible or named in S['visible_named']) and (multi or P['starts_upper'].search(t)):
        return 'visible'
    if (f in S['prompt_files'] or 'Prompt' in f or 'PROMPT' in named or 'Prompt' in named or 'prompt' in named or 'promptLine' in before) and multi:
        return 'prompt'
    if multi and P['prompt_rx'].search(t) and lang_of(rules, t) == 'EN' and not P['you'].search(t.replace('the user', '')):
        return 'prompt'
    if multi and P['the_user'].search(t) and lang_of(rules, t) == 'EN':
        return 'prompt'
    if P['fr_accent'].search(t) and 'Router' not in f and len(ws) >= 1 and not P['regex_syntax'].search(t):
        return 'visible'
    if '{}' in t and len(ws) >= 1 and 'Router' not in f and not P['json_call'].search(call) and P['word3'].search(stripped):
        return 'visible'
    if P['json_call'].search(call) and not multi and not sink:
        return 'key'
    if P['cap_word_end'].search(t.strip()):
        return 'visible'
    if P['ident_shape'].search(t) and not P['whitespace'].search(t):
        if P['cap_word'].search(t):
            if sink or named in S['visible_named'] or P['enum_call'].search(last) or before.rstrip().endswith('->'):
                return 'visible'
            return 'single'
        if P['lower_word'].search(t) and (P['input_call'].search(call) or call in ('{when}', '{block}', '') or P['router_file'].search(f)):
            return 'input'
        if P['key_shape'].search(t):
            return 'key'
        if P['lower_word'].search(t):
            if last == 'Text' or (P['enum_call'].search(last) and not t.isascii()):
                return 'visible'
            return 'key' if rel.startswith(('app', 'voice')) else 'input'
        return 'key'
    starts_lower = P['starts_lower'].search(t) is not None
    ends_sentence = P['ends_sentence'].search(t.strip()) is not None
    in_router = P['router_file'].search(f) is not None
    value_pos = before.rstrip().endswith(' to') or before.rstrip().endswith('->')
    if sink_visible or named in S['visible_named']:
        return 'visible'
    if P['enum_call'].search(last) and multi:
        return 'visible'
    if starts_lower and not ends_sentence and 'Router' in f and '{}' not in t:
        return 'input'
    if starts_lower and not ends_sentence and not value_pos and (P['input_call'].search(call) or in_router or call == '{when}'):
        return 'input'
    if starts_lower and not ends_sentence and not multi:
        return 'input'
    if starts_lower and P['input_call'].search(call) and not value_pos and '{}' not in t and len(ws) <= 4 and not ends_sentence:
        return 'input'
    return 'visible'


def sources(root, rules):
    """Every Kotlin source of the six modules' main code, as (path from robot/, file), vendored code left out."""
    out = []
    for m in rules.r['modules']:
        base = os.path.join(root, m, 'src', 'main', 'java')
        for d, dirs, files in os.walk(base):
            dirs.sort()
            for fn in sorted(files):
                if fn.endswith('.kt'):
                    p = os.path.join(d, fn)
                    rel = os.path.relpath(p, root).replace(os.sep, '/')
                    if not any(x.search(rel) for x in rules.exclude):
                        out.append((rel, p))
    return sorted(out)


def sha1(text):
    return hashlib.sha1(text.encode('utf-8')).hexdigest()


def inventory(root, rules):
    """Every message of every source, classified: dicts with module, package, file (from robot/), line, kind, lang, text,
    area, and guard (what the guard does with it; a literal in a catalog, core/i18n, is kind 'catalog': 'baseline' when it must be in its area's baseline, 'marked' for a
    "// l10n: not text (reason)" line, 'bad-marker' for a marker without its reason, '' otherwise)."""
    rows = []
    for rel, path in sources(root, rules):
        src = open(path, encoding='utf-8').read()
        lines = src.split('\n')
        ranges = logcode_ranges(rules, src)
        parts = rel.split('/')
        module = parts[0]
        pkg = '/'.join(parts[parts.index('heyrobot') + 1:-1]) if 'heyrobot' in parts else '/'.join(parts[4:-1])
        area = rules.area(rel)
        allowed = any(x.search(rel) for x in rules.allowed_dirs)
        for r in messages(src):
            # The catalogs (core/i18n) are where the words go, not text to move: counted apart.
            kind = 'catalog' if allowed else classify(rules, rel, r, ranges)
            lang = lang_of(rules, r['text']) if kind in ('visible', 'single') else ''
            guard = ''
            if kind == 'visible' and not allowed:
                raw = lines[r['line'] - 1] if r['line'] - 1 < len(lines) else ''
                if rules.marker.search(raw):
                    guard = 'marked'
                elif rules.marker_any.search(raw):
                    guard = 'bad-marker'
                else:
                    guard = 'baseline'
            rows.append({'module': module, 'package': pkg or '(root)', 'file': rel, 'line': r['line'], 'kind': kind, 'lang': lang,
                         'text': r['text'], 'area': area or '', 'guard': guard})
    return rows


def resources(root):
    """The app's string resources: {file name: {'strings', 'plurals', 'arrays', 'prefixes': Counter}} for values/ and
    values-fr-rCA/."""
    out = {}
    for d in ('values', 'values-fr-rCA'):
        base = os.path.join(root, 'app', 'src', 'main', 'res', d)
        if not os.path.isdir(base):
            continue
        for fn in sorted(os.listdir(base)):
            if not (fn.startswith('strings') and fn.endswith('.xml')):
                continue
            x = ET.parse(os.path.join(base, fn)).getroot()
            c = collections.Counter()
            kinds = collections.Counter()
            for e in x:
                if e.tag in ('string', 'plurals', 'string-array'):
                    kinds[e.tag] += 1
                    c[e.get('name').split('_')[0] + '_'] += 1
            out[d + '/' + fn] = {'kinds': kinds, 'prefixes': c}
    return out


def table(title, header, rows):
    print('\n' + title)
    w = [max(len(str(x)) for x in col) for col in zip(header, *rows)] if rows else [len(h) for h in header]
    print('  '.join(str(h).ljust(w[i]) if i == 0 else str(h).rjust(w[i]) for i, h in enumerate(header)))
    for r in rows:
        print('  '.join(str(v).ljust(w[i]) if i == 0 else str(v).rjust(w[i]) for i, v in enumerate(r)))


def baselines(root):
    """The guard's baselines: {area: Counter((file, sha1))}."""
    out = {}
    base = os.path.join(root, 'app', 'src', 'test', 'resources', 'l10n')
    if not os.path.isdir(base):
        return out
    for fn in sorted(os.listdir(base)):
        m = re.fullmatch(r'baseline-([a-z]+)\.txt', fn)
        if m:
            c = collections.Counter()
            for line in open(os.path.join(base, fn), encoding='utf-8'):
                line = line.rstrip('\n')
                if line and not line.startswith('#'):
                    f, h = line.split('\t')
                    c[(f, h)] += 1
            out[m.group(1)] = c
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('root', help='the robot/ directory')
    ap.add_argument('--area', help="list one area's guarded literals")
    ap.add_argument('--check', action='store_true', help="compare with the guard's baselines")
    ap.add_argument('--rows', help='write every message as TSV to this file')
    a = ap.parse_args()
    root = os.path.abspath(a.root)
    rules = Rules(os.path.join(root, 'l10n', 'scan_rules.json'))
    rows = inventory(root, rules)
    if a.rows:
        with open(a.rows, 'w', encoding='utf-8') as f:
            f.write('module\tpackage\tfile\tline\tkind\tlang\tarea\tguard\ttext\n')
            for r in rows:
                t = r['text'].replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n')
                f.write(f"{r['module']}\t{r['package']}\t{r['file']}\t{r['line']}\t{r['kind']}\t{r['lang']}\t{r['area']}\t{r['guard']}\t{t}\n")
    guarded = [r for r in rows if r['guard'] == 'baseline']
    if a.area:
        for r in sorted((r for r in guarded if r['area'] == a.area), key=lambda r: (r['file'], r['line'])):
            print(f"{r['file']}:{r['line']}\t{sha1(r['text'])[:10]}\t{r['text']!r}")
        return 0
    if a.check:
        have = baselines(root)
        want = collections.defaultdict(collections.Counter)
        for r in guarded:
            want[r['area'] or '?'][(r['file'], sha1(r['text']))] += 1
        bad = 0
        for area in sorted(set(have) | set(want)):
            extra = have.get(area, collections.Counter()) - want[area]
            missing = want[area] - have.get(area, collections.Counter())
            for (f, h), n in sorted(extra.items()):
                bad += n
                print(f'baseline-{area}.txt: {f}\t{h} matches no literal here (x{n})')
            for (f, h), n in sorted(missing.items()):
                bad += n
                text = next((r['text'] for r in guarded if r['file'] == f and sha1(r['text']) == h), '')
                print(f'baseline-{area}.txt lacks {f}\t{h} {text!r} (x{n})')
        print(f'{bad} difference(s) between this scan and the baselines')
        return 1 if bad else 0

    mods = rules.r['modules']
    files = sources(root, rules)
    nlines = sum(open(p, encoding='utf-8').read().count('\n') for _, p in files)
    print(f'Scanned {len(files)} Kotlin files, {nlines:,} lines, in {", ".join(":" + m for m in mods)} (vendored code left out).')

    kinds = ['visible EN', 'visible FR', 'single', 'brand', 'prompt', 'diag', 'input', 'key', 'addr', 'regex', 'format', 'data', 'log', 'error', 'glue',
             'catalog']
    by = collections.defaultdict(collections.Counter)
    for r in rows:
        k = r['kind'] + (' ' + r['lang'] if r['kind'] == 'visible' else '')
        by[k][r['module']] += 1
    trs = []
    for k in kinds:
        trs.append([k] + [f'{by[k][m]:,}' for m in mods] + [f'{sum(by[k].values()):,}'])
    tot = collections.Counter(r['module'] for r in rows)
    trs.append(['All'] + [f'{tot[m]:,}' for m in mods] + [f'{len(rows):,}'])
    table('All literal messages, by kind and module (occurrences):', ['Kind'] + [':' + m for m in mods] + ['Total'], trs)

    vis = [r for r in rows if r['kind'] in ('visible', 'single')]
    trs = []
    for m in mods:
        en = [r for r in vis if r['module'] == m and r['lang'] == 'EN']
        fr = [r for r in vis if r['module'] == m and r['lang'] == 'FR']
        trs.append([':' + m, f"{len(set(r['text'] for r in en)):,}", f'{len(en):,}', f"{len(set(r['text'] for r in fr)):,}"])
    en = [r for r in vis if r['lang'] == 'EN']
    fr = [r for r in vis if r['lang'] == 'FR']
    trs.append(['All (a text in two modules once)', f"{len(set(r['text'] for r in en)):,}", f'{len(en):,}', f"{len(set(r['text'] for r in fr)):,}"])
    table('User-visible text (visible + single):', ['Module', 'Different English', 'English occurrences', 'Different French'], trs)

    pk = collections.defaultdict(list)
    for r in vis:
        pk[(r['module'], r['package'])].append(r)
    trs = []
    for (m, p), rs in sorted(pk.items(), key=lambda x: -len(set(r['text'] for r in x[1] if r['lang'] == 'EN'))):
        en = [r for r in rs if r['lang'] == 'EN']
        if len(en) < 20:
            continue
        trs.append([f':{m} {p}', f"{len(set(r['text'] for r in en)):,}", f'{len(en):,}', f"{sum(1 for r in rs if r['lang'] == 'FR'):,}",
                    f"{sum(1 for r in en if '{}' in r['text']):,}"])
    table('By package (20 English occurrences or more):', ['Package', 'Different', 'Occurrences', 'French', 'With arguments'], trs)

    ar = collections.defaultdict(list)
    for r in guarded:
        ar[r['area'] or '(none)'].append(r)
    have = baselines(root)
    trs = []
    for area in rules.r['area_names'] + sorted(set(ar) - set(rules.r['area_names'])):
        rs = ar.get(area, [])
        trs.append([area, f'{len(rs):,}', f"{sum(1 for r in rs if r['lang'] == 'FR'):,}", f"{len(set(r['file'] for r in rs)):,}",
                    f"{sum(have.get(area, collections.Counter()).values()):,}"])
    trs.append(['All', f'{len(guarded):,}', f"{sum(1 for r in guarded if r['lang'] == 'FR'):,}", f"{len(set(r['file'] for r in guarded)):,}",
                f"{sum(sum(c.values()) for c in have.values()):,}"])
    table('The guard, by area (visible literals outside the catalogs, resources and marked lines):',
          ['Area', 'Literals', 'French', 'Files', 'Baseline lines'], trs)
    marked = [r for r in rows if r['guard'] in ('marked', 'bad-marker')]
    print(f"Lines marked \"// l10n: not text (reason)\": {sum(1 for r in marked if r['guard'] == 'marked')}"
          f" (without a reason: {sum(1 for r in marked if r['guard'] == 'bad-marker')}).")
    unassigned = sorted(set(r['file'] for r in guarded if not r['area']))
    if unassigned:
        print('Files with guarded literals and no area: ' + ', '.join(unassigned))

    res = resources(root)
    trs = []
    for fn, x in res.items():
        trs.append([fn, x['kinds']['string'], x['kinds']['plurals'], x['kinds']['string-array'],
                    ', '.join(f'{p} {n}' for p, n in sorted(x['prefixes'].items(), key=lambda kv: -kv[1]))])
    table('String resources:', ['File', 'Strings', 'Plurals', 'Arrays', 'Prefixes'], trs)
    return 0


if __name__ == '__main__':
    sys.exit(main())
