#!/usr/bin/env python3
"""Prüft für jedes PvP-Item aus pvp_items.json, wie viele Resource Packs es auf Modrinth
für die Spielversion gibt. Läuft in GitHub Actions und schreibt eine Tabelle in die Job-Zusammenfassung."""
import json, os, sys, time, urllib.parse, urllib.request

VERSION = "1.21.11"
CATALOG = os.path.join(os.path.dirname(__file__), "..", "src/main/resources/assets/texturepackbuilder/pvp_items.json")
UA = "texturepackbuilder-coverage/1.0 (github actions)"


def search(query):
    facets = json.dumps([["project_type:resourcepack"], [f"versions:{VERSION}"]])
    url = "https://api.modrinth.com/v2/search?" + urllib.parse.urlencode(
        {"query": query, "facets": facets, "limit": 5, "index": "relevance"})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def main():
    items = json.load(open(CATALOG, encoding="utf-8"))["items"]
    rows, missing = [], []
    for it in items:
        total, titles = 0, []
        for q in it["search"]:
            res = search(q)
            total = max(total, res.get("total_hits", 0))
            for h in res["hits"]:
                if h["title"] not in titles:
                    titles.append(h["title"])
            time.sleep(0.3)
        rows.append((it["id"], total, ", ".join(titles[:4])))
        if total == 0:
            missing.append(it["id"])
        print(f"{it['id']:12} {total:5}  {', '.join(titles[:4])}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"## PvP-Abdeckung auf Modrinth ({VERSION})\n\n| Item | Treffer | Beispiele |\n|---|---|---|\n")
            for r in rows:
                f.write(f"| {r[0]} | {r[1]} | {r[2]} |\n")
    print("OHNE TREFFER:", ", ".join(missing) if missing else "keine")


if __name__ == "__main__":
    sys.exit(main())
