#!/usr/bin/env python3
"""English UI sentences in the Kotlin source -> tools/lang/en.json (key = sentence, numbers/templates as {0}, {1}...)."""
import re, json, pathlib, sys
root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java")
lit = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
tmpl = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_.]*')
skip = re.compile(r'^(https?:|[a-z0-9_.\-/:%?=&]+$|[A-Z0-9_]+$|.*\.(json|kt|db|png|jpg|m3u8?)$)|^#|\\[ntu]|\{\s*"|^\W*$')
out = {}
for f in sorted(root.rglob("*.kt")):
    for line in f.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s.startswith(("import ", "package ", "//", "*", "/*", "Log.", "@")) or "Regex(" in s or "Log." in s:
            continue
        for m in lit.finditer(line):
            t = m.group(1)
            if len(t) < 2 or skip.search(t) or not re.search(r"[A-Za-z]{2}", t):
                continue
            if not (t[0].isupper() or t[0] in "‹›★●⏪✓·" or " " in t):
                continue
            i = 0
            def rep(_):
                nonlocal_i[0] += 1
                return "{%d}" % (nonlocal_i[0] - 1)
            nonlocal_i = [0]
            k = tmpl.sub(rep, t).replace('\\"', '"').replace("\\$", "$")
            if re.fullmatch(r"[\{\}\d\s\W]*", k):
                continue
            out[k] = k
print(len(out), "sentences", sum(len(k) for k in out), "chars")
pathlib.Path("tools/lang/en.json").write_text(json.dumps(out, ensure_ascii=False, indent=0, sort_keys=True), encoding="utf-8")
