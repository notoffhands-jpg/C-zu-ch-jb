import re, sys
txt = open('C:/Users/notof/BotSystem-Paper/tools/perr.txt', encoding='utf-8', errors='ignore').read()
blocks = re.split(r'\n(?=C:\\Users)', txt)
seen = set()
for b in blocks:
    m = re.match(r'(C:\\[^:]+):(\d+): Fehler: (.*)', b)
    if not m:
        continue
    f, line, msg = m.groups()
    sym = re.search(r'Symbol: *(.*)', b)
    loc = re.search(r'Ort: *(.*)', b)
    key = (f, line, msg.strip())
    if key in seen:
        continue
    seen.add(key)
    lines = b.split('\n')
    code = lines[1].strip()[:120] if len(lines) > 1 else ''
    print('%s:%s | %s | %s | %s | %s' % (f.split('\\')[-1], line, msg[:45], (sym.group(1).strip() if sym else '')[:50], (loc.group(1).strip() if loc else '')[:45], code))
