"""Builds app/src/main/assets/anime_ids.txt from Fribb/anime-lists (anime-list-mini.json).
One id per line: tt… (IMDb), tv:N / movie:N (TMDB). Run: python3 -I tools/anime_ids.py <anime-list-mini.json> <out>"""
import json, sys
d = json.load(open(sys.argv[1]))
imdb, tmdb = set(), set()
for e in d:
    for i in e.get("imdb_id") or []:
        if isinstance(i, str) and i.startswith("tt"): imdb.add(i)
    t = e.get("themoviedb_id") or {}
    if isinstance(t, dict):
        for k, v in t.items():
            if k in ("tv", "movie") and v: tmdb.add(f"{k}:{v}")
with open(sys.argv[2], "w") as f:
    f.write("\n".join(sorted(imdb) + sorted(tmdb)) + "\n")
print(len(imdb), len(tmdb))
