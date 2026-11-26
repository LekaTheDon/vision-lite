#!/usr/bin/env python3
"""tools/lang/en.json -> app/src/main/assets/lang/<tag>.json via Gemini. Key from env GEMINI_KEY (never stored).
Resumable: keys already translated in the output file are skipped."""
import json, os, sys, time, urllib.request, pathlib
KEY = os.environ["GEMINI_KEY"]
MODEL = os.environ.get("MODEL", "gemini-2.5-flash")
LANGS = {"ar": ("Arabic", "العربية"), "fr": ("French", "Français"), "es": ("Spanish", "Español"), "de": ("German", "Deutsch"),
         "it": ("Italian", "Italiano"), "pt": ("Portuguese (Brazil)", "Português"), "tr": ("Turkish", "Türkçe"), "ru": ("Russian", "Русский"),
         "hi": ("Hindi", "हिन्दी"), "ur": ("Urdu", "اردو"), "fa": ("Persian (Farsi)", "فارسی"), "zh": ("Simplified Chinese", "简体中文")}
src = json.load(open("tools/lang/en.json", encoding="utf-8"))
keys = sorted(src)
out_dir = pathlib.Path("app/src/main/assets/lang"); out_dir.mkdir(parents=True, exist_ok=True)
PROMPT = """You translate the user interface of a TV streaming app (films, series, anime, live TV / IPTV, sports, debrid links) into {lang}.
Input: a JSON object whose keys are English UI strings. Output: a JSON object with the SAME keys, each value the {lang} translation.
Rules: natural, short, everyday {lang} as a polished TV app would use (like Netflix/Apple TV in {lang}); keep placeholders like {{0}} {{1}} exactly;
keep symbols (›, ·, ★, ●, ✓, arrows, emoji) and punctuation style; keep brand/service names untouched (Trakt, TorBox, IMDb, TMDB, Real-Debrid, Dolby Vision, HDR10, Atmos, 4K, VOD, IPTV, Xtream, M3U, EPG, AniList, MyAnimeList, Simkl, Gemini, ExoPlayer, mpv, FEL);
technical tokens (codecs, units like MB, ms, fps) stay as is. If a key is clearly code, a query, an ID or not human text, return it unchanged.
Only the JSON object, nothing else."""
def call(chunk, lang):
    body = {"contents": [{"parts": [{"text": PROMPT.format(lang=lang) + "\n" + json.dumps(chunk, ensure_ascii=False)}]}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2}}
    req = urllib.request.Request(f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent",
                                 data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "x-goog-api-key": KEY})
    with urllib.request.urlopen(req, timeout=300) as r:
        d = json.load(r)
    return json.loads(d["candidates"][0]["content"]["parts"][0]["text"])
tags = sys.argv[1:] or list(LANGS)
N = int(os.environ.get("CHUNK", "300"))
for tag in tags:
    lang, own = LANGS[tag]
    f = out_dir / f"{tag}.json"
    have = json.load(open(f, encoding="utf-8")) if f.exists() else {"@name": own}
    todo = [k for k in keys if k not in have]
    print(tag, "todo", len(todo), flush=True)
    for i in range(0, len(todo), N):
        chunk = {k: k for k in todo[i:i + N]}
        for attempt in range(6):
            try:
                res = call(chunk, lang); break
            except Exception as e:
                msg = getattr(e, "code", "") or str(e)[:120]
                print(tag, i, "retry", attempt, msg, flush=True); time.sleep(20 * (attempt + 1))
        else:
            print(tag, i, "FAILED chunk", flush=True); continue
        for k in chunk:
            v = res.get(k)
            if isinstance(v, str) and v.strip() and v.count("{") == k.count("{"):
                have[k] = v
        json.dump(have, open(f, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
        print(tag, i + len(chunk), "/", len(todo), flush=True)
        time.sleep(7)
for tag in tags:
    f = out_dir / f"{tag}.json"
    if f.exists():
        d = json.load(open(f, encoding="utf-8")); d = {k: v for k, v in d.items() if k.startswith("@") or v != k}
        json.dump(d, open(f, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
print("done", flush=True)
