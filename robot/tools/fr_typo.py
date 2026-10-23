#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""French typography for the app's French resources, as core's FrTypo applies it to
core's catalog: a no-break space (U+00A0) before ":" and inside « », before % $ and units; a narrow no-break space
(U+202F, the OQLF's "fine") before ; ! ?; the typographic apostrophe ’ and the ellipsis …. XML can't run code, so this
writes them into res/values-fr-rCA/*.xml as the entities &#160; and &#8239; (visible in the file).

    python3 -I robot/tools/fr_typo.py robot          # lists what it would change
    python3 -I robot/tools/fr_typo.py robot --fix    # changes it

FrenchTypographyTest fails on what this would change and prints the command. Keep the rules in step with core
i18n/FrTypo.kt: the test applies FrTypo to each value and expects it unchanged.
"""
import os
import re
import sys

NBSP = '\u00a0'
FINE = '\u202f'  # FrTypo.FINE: the OQLF's fine space before ; ! ? (open question 5, decided by default)
SP = ' \u00a0\u202f'

PROTECTED = re.compile(r"`[^`\n]*`|[A-Za-z][A-Za-z0-9+.\-]*://[^ \t\n\x0b\x0c\r\u00a0\u202f]*|Termux:API|\d:\d|\{[A-Za-z][A-Za-z0-9_]*\}")
COLON = re.compile(f'([^{SP}\\n])[{SP}]*:')
HIGH = re.compile(f'([^{SP}\\n;!?])[{SP}]*([;!?]+)')
OPEN = re.compile(f'«[{SP}]*')
CLOSE = re.compile(f'[{SP}]*»')
PERCENT = re.compile(f'([0-9\ue001])[{SP}]*([%$])')
UNIT = re.compile(f'([0-9\ue001])[{SP}]+(Go|Mo|ko|km|°C|°F|h|min|ms|s)(?![^\\W_]|[\u2019\'])')
HOUR_MIN = re.compile(f'([0-9\ue001]{NBSP}h)[{SP}]+(?=[0-9]|\ue000)')
HOLE = re.compile('\ue000(\\d+)\ue001')


def fix(s):
    """Core FrTypo.fix, line for line."""
    if not s:
        return s
    holes = []

    def hold(m):
        holes.append(m.group(0))
        return f'\ue000{len(holes) - 1}\ue001'
    t = PROTECTED.sub(hold, s)
    t = t.replace('...', '…').replace("'", '’')
    t = COLON.sub(lambda m: m.group(1) + NBSP + ':', t)
    t = HIGH.sub(lambda m: m.group(1) + FINE + m.group(2), t)
    t = OPEN.sub('«' + NBSP, t)
    t = CLOSE.sub(NBSP + '»', t)
    t = PERCENT.sub(lambda m: m.group(1) + NBSP + m.group(2), t)
    t = UNIT.sub(lambda m: m.group(1) + NBSP + m.group(2), t)
    t = HOUR_MIN.sub(lambda m: m.group(1) + NBSP, t)
    return HOLE.sub(lambda m: holes[int(m.group(1))], t)


# In the XML: Android's placeholders, entities and tags stay as they are (each one a {xN} hole for fix()).
RAW_HOLES = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]|&[a-zA-Z]+;|<[^>]+>")
VALUE = re.compile(r'(<(string|item)\b[^>]*>)(.*?)(</\2>)', re.S)


def fix_xml_text(raw):
    """One value as written in the XML: \\' unescaped (it becomes ’), the spaces written as entities."""
    text = raw.replace('&#160;', NBSP).replace('&#xA0;', NBSP).replace('&#8239;', FINE).replace('&#x202F;', FINE).replace("\\'", "'")
    kept = []

    def keep(m):
        kept.append(m.group(0))
        return '{x' + str(len(kept) - 1) + '}'
    masked = RAW_HOLES.sub(keep, text)
    out = fix(masked)
    out = re.sub(r'\{x(\d+)\}', lambda m: kept[int(m.group(1))], out)
    return out.replace(NBSP, '&#160;').replace(FINE, '&#8239;')


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    base = os.path.join(sys.argv[1], 'app', 'src', 'main', 'res', 'values-fr-rCA')
    write = '--fix' in sys.argv[2:]
    changed = 0
    for fn in sorted(os.listdir(base)):
        if not fn.endswith('.xml'):
            continue
        path = os.path.join(base, fn)
        src = open(path, encoding='utf-8').read()

        def one(m):
            nonlocal changed
            new = fix_xml_text(m.group(3))
            if new != m.group(3):
                changed += 1
                name = re.search(r'name="([^"]+)"', m.group(1))
                print(f"{fn}: {name.group(1) if name else m.group(1)}: {m.group(3)!r} -> {new!r}")
            return m.group(1) + new + m.group(4)
        out = VALUE.sub(one, src)
        if write and out != src:
            open(path, 'w', encoding='utf-8').write(out)
    print(f"{changed} value(s) {'fixed' if write else 'to fix (run with --fix)'}")
    return 0 if write or changed == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
