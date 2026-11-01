#!/usr/bin/env python3
"""Audit every bundled FOSCH E-number against Wikipedia's live E-number list.

Outputs:
  audit-output/wikipedia_e_number_audit.csv
  audit-output/wikipedia_e_number_audit.json
  audit-output/wikipedia_e_number_audit.md

This verifies the code/name -> article mapping, page availability, section headings,
and whether a suitable food-use section can be selected without guessing.
"""
from __future__ import annotations

import csv
import html
import json
import re
import sys
import time
import unicodedata
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, unquote, urlparse
from urllib.request import Request, urlopen

API = "https://en.wikipedia.org/w/api.php"
USER_AGENT = "FOSCH-Wikipedia-E-number-audit/1.0 (open-source app catalogue verification)"
ROOT = Path(__file__).resolve().parents[1]
CATALOGUE = ROOT / "app/src/main/assets/e_numbers.js"
OUT = ROOT / "audit-output"
OUT.mkdir(exist_ok=True)

def fetch_json(params: dict) -> dict:
    query = dict(params)
    query.setdefault("format", "json")
    query.setdefault("formatversion", "2")
    query.setdefault("maxlag", "5")
    url = API + "?" + urlencode(query)
    last_error = None
    for attempt in range(6):
        req = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
        try:
            with urlopen(req, timeout=45) as response:
                data = json.loads(response.read().decode("utf-8"))
            if "error" in data:
                code = data["error"].get("code", "")
                if code in ("maxlag", "ratelimited") and attempt < 5:
                    time.sleep(min(2 ** attempt, 20))
                    continue
                raise RuntimeError(data["error"])
            time.sleep(0.12)
            return data
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError, RuntimeError) as exc:
            last_error = exc
            if attempt == 5:
                break
            retry_after = 2 ** attempt
            if isinstance(exc, HTTPError) and exc.headers.get("Retry-After"):
                try:
                    retry_after = max(retry_after, float(exc.headers["Retry-After"]))
                except ValueError:
                    pass
            time.sleep(min(retry_after, 30))
    raise RuntimeError(f"Wikipedia request failed after retries: {last_error}")

class CellParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.table_depth = 0
        self.row = None
        self.cell = None
        self.anchor = None
        self.rows = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "table":
            self.table_depth += 1
        elif self.table_depth and tag == "tr":
            self.row = {"cells": []}
        elif self.row is not None and tag in ("td", "th"):
            self.cell = {"text": "", "links": []}
        elif self.cell is not None and tag == "a":
            self.anchor = {"href": attrs.get("href", ""), "title": attrs.get("title", ""), "text": ""}

    def handle_data(self, data):
        if self.cell is not None:
            self.cell["text"] += data
        if self.anchor is not None:
            self.anchor["text"] += data

    def handle_endtag(self, tag):
        if tag == "a" and self.anchor is not None:
            href = self.anchor.get("href", "")
            if href.startswith("/wiki/") and not href.startswith("/wiki/Special:"):
                self.cell["links"].append(self.anchor)
            self.anchor = None
        elif tag in ("td", "th") and self.cell is not None:
            self.cell["text"] = re.sub(r"\s+", " ", self.cell["text"]).strip()
            self.row["cells"].append(self.cell)
            self.cell = None
        elif tag == "tr" and self.row is not None:
            if self.row["cells"]:
                self.rows.append(self.row)
            self.row = None
        elif tag == "table" and self.table_depth:
            self.table_depth -= 1

def normalise(value: str) -> str:
    value = html.unescape(str(value or ""))
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    value = value.casefold().replace("sulph", "sulf").replace("aluminium", "aluminum")
    value = re.sub(r"\([^)]*\)", " ", value)
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()

def code_base(code: str) -> str:
    return re.sub(r"\([ivxlcdm]+\)$", "", code.strip(), flags=re.I)

def article_title_from_link(link: dict) -> str:
    title = (link.get("title") or "").strip()
    if title:
        return title
    slug = link.get("href", "").split("/wiki/", 1)[-1]
    return unquote(slug).replace("_", " ")

def load_catalogue() -> list[dict]:
    source = CATALOGUE.read_text(encoding="utf-8")
    match = re.search(r"window\.FOSCH_E_NUMBER_INDEX\s*=\s*(\[.*?\])\s*;", source, re.S)
    if not match:
        raise RuntimeError("Could not locate FOSCH_E_NUMBER_INDEX in e_numbers.js")
    return json.loads(match.group(1))

def load_list_rows() -> tuple[dict, str]:
    data = fetch_json({"action": "parse", "page": "E_number", "prop": "text"})
    parsed = data.get("parse") or {}
    page_html = parsed.get("text") or ""
    if isinstance(page_html, list):
        page_html = "".join(str(x) for x in page_html)
    parser = CellParser()
    parser.feed(page_html)
    table = {}
    for row in parser.rows:
        cells = row["cells"]
        if len(cells) < 2:
            continue
        code = re.sub(r"\s+", "", cells[0]["text"]).upper()
        if re.fullmatch(r"E\d+[A-Z]?", code):
            table[code] = {"code": code, "name_cell": cells[1],
                           "row_text": " | ".join(c["text"] for c in cells)}
    return table, str(parsed.get("revid") or "")

def score_name(entry_name: str, link: dict) -> int:
    target = normalise(entry_name)
    variants = {normalise(link.get("title", "")), normalise(link.get("text", ""))} - {""}
    if target and target in variants:
        return 100
    if target and any(v and (target in v or v in target) for v in variants):
        return 82
    target_tokens = set(target.split())
    best = 0
    for value in variants:
        tokens = set(value.split())
        if target_tokens and tokens:
            ratio = len(target_tokens & tokens) / max(len(target_tokens), len(tokens))
            best = max(best, int(ratio * 70))
    return best

def resolve_entry(entry: dict, table: dict) -> dict:
    code = str(entry.get("code", "")).strip().upper()
    name = str(entry.get("name", "")).strip()
    base = code_base(code)
    exact_row = table.get(code)
    parent_row = table.get(base)
    rows_to_check = []
    if exact_row:
        rows_to_check.append(("exact_code", exact_row))
    if parent_row and parent_row is not exact_row:
        rows_to_check.append(("parent_code", parent_row))

    for source, row in rows_to_check:
        links = row["name_cell"]["links"]
        ranked = sorted(((score_name(name, link), link) for link in links), key=lambda x: x[0], reverse=True)
        if ranked and ranked[0][0] >= 82:
            score, link = ranked[0]
            return {"mapping_status": source + "_name_match", "title": article_title_from_link(link),
                    "url": "https://en.wikipedia.org" + link["href"], "match_score": score,
                    "mapping_note": "Article link matched the catalogue name within the exact/parent code row."}

    global_matches = []
    for row in table.values():
        for link in row["name_cell"]["links"]:
            score = score_name(name, link)
            if score >= 100:
                global_matches.append((score, article_title_from_link(link), link["href"]))
    unique = {(title, href) for _, title, href in global_matches}
    if len(unique) == 1:
        _, title, href = next(iter(unique))
        return {"mapping_status": "global_exact_name_match", "title": title,
                "url": "https://en.wikipedia.org" + href, "match_score": 100,
                "mapping_note": "Exact name link found in another E-number list row."}

    for source, row in rows_to_check:
        links = row["name_cell"]["links"]
        if len(links) == 1:
            link = links[0]
            return {"mapping_status": source + "_single_link_fallback",
                    "title": article_title_from_link(link),
                    "url": "https://en.wikipedia.org" + link["href"], "match_score": 0,
                    "mapping_note": "Only one article link exists in the exact/parent E-number row; verify sub-entry applicability."}

    stored = str(entry.get("wikiUrl") or "").strip()
    if stored.startswith("https://en.wikipedia.org/wiki/"):
        slug = urlparse(stored).path.split("/wiki/", 1)[-1]
        return {"mapping_status": "stored_url_fallback", "title": unquote(slug).replace("_", " "),
                "url": stored, "match_score": 0,
                "mapping_note": "No exact code/name link was found; used the bundled legacy URL for checking only."}

    return {"mapping_status": "no_direct_article_link", "title": "", "url": "",
            "match_score": 0, "mapping_note": "No matching article link in the E-number list and no stored URL."}

def section_parent_has_children(sections: list[dict], position: int) -> bool:
    level = int(sections[position].get("level") or 0)
    for nxt in sections[position + 1:]:
        next_level = int(nxt.get("level") or 0)
        if next_level <= level:
            break
        return True
    return False

FOOD_HEADINGS = [
    (0, re.compile(r"^(food coloring|food colouring|food additive|food uses?|uses? in food|food applications?|applications? in food)$", re.I)),
    (1, re.compile(r"^(foods?|food products?|food industry|food and beverage uses?|food and other domestic uses|food preservation|culinary uses?|use in foods?)$", re.I)),
    (2, re.compile(r"\b(food|foods|culinary|beverage|foodstuff|foodstuffs)\b", re.I)),
]

def choose_food_section(sections: list[dict]) -> tuple[dict | None, str]:
    usable = [s for s in sections if s.get("index") is not None]
    ranked = []
    for pos, section in enumerate(usable):
        heading = re.sub(r"\s+", " ", str(section.get("line") or section.get("anchor") or "")).strip()
        if not heading:
            continue
        if re.search(r"\b(safety|allerg|toxic|health effects|adverse|medical|pharmac|deficien|disease|history|production|synthesis|chemistry|regulation)\b", heading, re.I):
            continue
        for priority, pattern in FOOD_HEADINGS:
            if pattern.search(heading):
                ranked.append((priority, pos, section, heading))
                break
    if ranked:
        ranked.sort(key=lambda item: (item[0], item[1]))
        chosen = ranked[0]
        return chosen[2], f"Food-related heading matched: {chosen[3]}"

    for pos, section in enumerate(usable):
        heading = str(section.get("line") or section.get("anchor") or "").strip()
        if re.fullmatch(r"(uses?|applications?|modern uses?)", heading, re.I):
            if not section_parent_has_children(usable, pos):
                return section, f"Generic {heading} section has no child subsections."
    return None, "No clearly food-specific section; preview should use an article overview or be manually reviewed."

def audit_page(title: str) -> dict:
    data = fetch_json({"action": "parse", "page": title, "prop": "sections"})
    parsed = data.get("parse") or {}
    if not parsed:
        raise RuntimeError(data.get("error") or f"Article not found: {title}")
    sections = parsed.get("sections") or []
    chosen, reason = choose_food_section(sections)
    return {"resolved_title": parsed.get("title") or title, "page_id": parsed.get("pageid"),
            "sections": sections, "section": chosen, "section_reason": reason}

def write_outputs(results: list[dict], pages: dict, wiki_revision: str) -> None:
    csv_path = OUT / "wikipedia_e_number_audit.csv"
    json_path = OUT / "wikipedia_e_number_audit.json"
    md_path = OUT / "wikipedia_e_number_audit.md"
    fields = ["code", "name", "mapping_status", "mapping_note", "article_title", "article_url",
              "page_status", "selected_section", "selected_section_index", "section_reason",
              "all_sections", "manual_review"]
    with csv_path.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for row in results:
            writer.writerow({k: row.get(k, "") for k in fields})
    payload = {"audit_source": "Wikipedia E_number page and MediaWiki parse API",
               "wikipedia_list_revision": wiki_revision, "catalogue_entries": len(results),
               "unique_article_pages_checked": len(pages), "results": results, "pages": pages}
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    counts = Counter(row["manual_review"] for row in results)
    mappings = Counter(row["mapping_status"] for row in results)
    section_counts = Counter(row["selected_section"] or "(none)" for row in results)
    problem_rows = [r for r in results if r["manual_review"] != "verified"]
    lines = ["# FOSCH Wikipedia E-number article and section audit", "",
             f"- Wikipedia list revision: {wiki_revision or 'not supplied'}",
             f"- Catalogue entries audited: **{len(results)}**",
             f"- Unique article pages checked: **{len(pages)}**", "",
             "## Outcome", "", "| Check | Entries |", "|---|---:|"]
    for label, count in counts.most_common():
        lines.append(f"| {label} | {count} |")
    lines += ["", "## Mapping methods", "", "| Mapping method | Entries |", "|---|---:|"]
    for label, count in mappings.most_common():
        lines.append(f"| {label} | {count} |")
    lines += ["", "## Selected sections", "", "| Section | Entries |", "|---|---:|"]
    for label, count in section_counts.most_common(30):
        lines.append(f"| {label} | {count} |")
    lines += ["", "## Entries needing review", ""]
    if not problem_rows:
        lines.append("None.")
    else:
        lines += ["| E-number | Catalogue name | Article | Section | Reason |",
                  "|---|---|---|---|---|"]
        for r in problem_rows:
            lines.append("| " + " | ".join(str(r.get(k) or "—").replace("|", "\\|").replace("\n", " ")
                                              for k in ("code", "name", "article_title", "selected_section", "manual_review")) + " |")
    lines += ["", "## Important interpretation", "",
              "A Wikipedia article link is not a scientific safety assessment. This audit checks whether the catalogue entry points to a corresponding article and whether the article exposes a suitable food-use section heading. Entries without a specific section are marked for overview fallback or manual review; no section is fabricated.", ""]
    md_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"Wrote {csv_path}")
    print(f"Wrote {json_path}")
    print(f"Wrote {md_path}")
    print("Manual review counts:", json.dumps(counts, ensure_ascii=False))
    print("Mapping counts:", json.dumps(mappings, ensure_ascii=False))

def main() -> int:
    entries = load_catalogue()
    print(f"Loaded {len(entries)} bundled E-number entries.")
    table, wiki_revision = load_list_rows()
    print(f"Parsed {len(table)} code rows from Wikipedia's E-number list.")
    resolved = [{**entry, **resolve_entry(entry, table)} for entry in entries]

    titles = sorted({r["title"] for r in resolved if r.get("title")})
    print(f"Checking {len(titles)} unique Wikipedia article pages for section headings.")
    pages = {}
    for index, title in enumerate(titles, 1):
        try:
            pages[title] = audit_page(title)
        except Exception as exc:
            pages[title] = {"error": str(exc), "resolved_title": title, "sections": [],
                            "section": None, "section_reason": "Wikipedia page could not be verified."}
            print(f"WARNING: {title}: {exc}", file=sys.stderr)
        if index % 25 == 0 or index == len(titles):
            print(f"Checked {index}/{len(titles)} article pages.")

    for row in resolved:
        title = row.get("title", "")
        page = pages.get(title) if title else None
        if not title:
            row.update({"page_status": "no article link", "article_title": "", "article_url": "",
                        "selected_section": "", "selected_section_index": "",
                        "section_reason": row["mapping_note"], "all_sections": "",
                        "manual_review": "no linked article"})
            continue
        if not page or page.get("error"):
            row.update({"page_status": "page could not be verified", "article_title": title,
                        "article_url": row.get("url", ""), "selected_section": "",
                        "selected_section_index": "", "section_reason": (page or {}).get("error", "No page data"),
                        "all_sections": "", "manual_review": "page lookup failed"})
            continue
        section = page.get("section") or {}
        section_title = str(section.get("line") or section.get("anchor") or "")
        method = row.get("mapping_status", "")
        if not section_title:
            review = "no suitable food-use section"
        elif method in ("stored_url_fallback", "exact_code_single_link_fallback", "parent_code_single_link_fallback"):
            review = "mapping needs manual review"
        else:
            review = "verified"
        row.update({"page_status": "found", "article_title": page.get("resolved_title") or title,
                    "article_url": row.get("url", ""), "selected_section": section_title,
                    "selected_section_index": section.get("index", "") if section else "",
                    "section_reason": page.get("section_reason", ""),
                    "all_sections": " ; ".join(str(s.get("line") or s.get("anchor") or "") for s in page.get("sections", [])),
                    "manual_review": review})

    write_outputs(resolved, pages, wiki_revision)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
