#!/usr/bin/env python3
"""Dependency-free structural and source-consistency smoke checks for the merged catalog."""
from __future__ import annotations

import collections
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
CATALOG_PATH = ASSETS / "search-engines/catalog.v1.json"
MODEL_PATH = ROOT / "app/src/main/java/com/cue/simplebrowser/SearchEngine.java"
CATALOG_JAVA_PATH = ROOT / "app/src/main/java/com/cue/simplebrowser/SearchEngineCatalog.java"
ADAPTER_PATH = ROOT / "app/src/main/java/com/cue/simplebrowser/SearchEnginePickerAdapter.java"
ACTIVITY_PATH = ROOT / "app/src/main/java/com/cue/simplebrowser/MainActivity.java"
STRINGS_PATH = ROOT / "app/src/main/res/values/strings.xml"
PDF_PATH = Path("/workspace/search-engines-extracted.json")
SEGMENT_PATHS = [Path(f"/workspace/search-templates-{s}.json") for s in
                 ("001-100", "101-200", "201-300", "301-400", "401-500")]
EXPECTED_LEGACY_IDS = {
    "google", "bing", "yahoo", "duckduckgo", "brave", "startpage", "ecosia", "qwant",
    "mojeek", "swisscows", "yandex", "kagi", "naver", "seznam", "metager", "perplexity",
    "you", "marginalia", "wiby", "mwmbl", "wikipedia",
}


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def valid_template(template: str) -> bool:
    if not isinstance(template, str) or template.count("{query}") != 1:
        return False
    if "{" in template.replace("{query}", "") or "}" in template.replace("{query}", ""):
        return False
    if "#" in template or any(c.isspace() or ord(c) < 32 for c in template):
        return False
    safe = template.replace("{query}", "safe-query-value")
    try:
        parsed = urlsplit(safe)
        _ = parsed.port
    except ValueError:
        return False
    return (parsed.scheme.lower() == "https" and bool(parsed.hostname)
            and parsed.username is None and parsed.password is None and parsed.fragment == ""
            and ("safe-query-value" in parsed.path or "safe-query-value" in (parsed.query or "")))


def normalized_name(value: str) -> str:
    import unicodedata
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii").lower()
    return re.sub(r"[^a-z0-9]+", "", value)


def load_json(path: Path):
    check(path.is_file(), f"required source file missing: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    catalog = load_json(CATALOG_PATH)
    check(catalog.get("schemaVersion") == 1, "catalog schemaVersion must remain 1")
    check(catalog.get("catalogVersion") == "2.0.0", "merged catalog version metadata missing")
    records = catalog.get("engines")
    check(isinstance(records, list) and len(records) == 502,
          "catalog must retain 500 PDF items plus the two non-overlapping legacy items")

    ids = [r.get("id") for r in records]
    check(len(ids) == len(set(ids)), "catalog IDs must be unique")
    check(ids[0] == "google" and "google" in ids, "Google stable ID/default order must remain")
    check(all(re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", value or "") for value in ids),
          "catalog IDs must remain stable lower-case ASCII slugs")
    check({r.get("category") for r in records} == {"FREE", "ACCOUNT_REQUIRED", "PAID"},
          "the original three access categories must remain the only access categories")

    pdf = load_json(PDF_PATH)
    source_items = pdf.get("entries")
    check(isinstance(source_items, list) and len(source_items) == 500, "PDF JSON must have 500 source entries")
    source_by_id = {r.get("id"): r for r in source_items}
    check(set(source_by_id) == set(range(1, 501)), "PDF source IDs must be exactly 1..500")
    segments = []
    for path in SEGMENT_PATHS:
        part = load_json(path)
        check(isinstance(part, list) and len(part) == 100, f"template segment must contain 100 rows: {path}")
        segments.extend(part)
    segment_by_id = {r.get("id"): r for r in segments}
    check(len(segments) == 500 and set(segment_by_id) == set(range(1, 501)),
          "segment JSON IDs must cover exactly 1..500 without duplicates")

    pdf_records = [r for r in records if isinstance(r.get("pdfCatalogId"), int)]
    check(len(pdf_records) == 500, "every PDF row must have exactly one merged catalog item")
    merged_by_source = {r["pdfCatalogId"]: r for r in pdf_records}
    check(set(merged_by_source) == set(range(1, 501)), "merged PDF IDs must cover exactly 1..500")

    status_counts = collections.Counter()
    nonverified_templates = []
    unsupported_verified = []
    for source_id in range(1, 501):
        source = source_by_id[source_id]
        segment = segment_by_id[source_id]
        merged = merged_by_source[source_id]
        check(source.get("name") == segment.get("name"), f"source ID/name mismatch between PDF and segment: {source_id}")
        check(merged.get("sourceName") == source["name"] and merged.get("name") == source["name"],
              f"merged source ID/name mismatch: {source_id}")
        check(merged.get("pdfPrimaryCategory") == source.get("pdf_category", {}).get("primary"),
              f"primary category was not preserved for source ID {source_id}")
        check(merged.get("pdfSecondaryCategory") == source.get("pdf_category", {}).get("secondary"),
              f"secondary category was not preserved for source ID {source_id}")
        check(merged.get("functionalSubcategories") == source.get("functional_subcategories", []),
              f"functional subcategory tags differ for source ID {source_id}")
        check(merged.get("pdfFeeClassification", "") == (source.get("fee_classification_from_appendix") or ""),
              f"PDF fee classification was not preserved for source ID {source_id}")
        check(merged.get("pdfOperationalStatus", "") == (source.get("operational_status_from_appendix") or ""),
              f"PDF operational status was not preserved for source ID {source_id}")
        check(merged.get("pdfFlags") == (source.get("flags") or {}),
              f"PDF uncertainty flags were not preserved for source ID {source_id}")
        check(merged.get("templateStatus") == segment.get("template_status"),
              f"template status does not match segment source ID {source_id}")
        status_counts[segment.get("template_status")] += 1
        check(merged.get("templateEvidenceUrl", "") == (segment.get("evidence_url") or ""),
              f"template evidence URL was not preserved for source ID {source_id}")
        check(merged.get("templateEvidenceNote", "") == (segment.get("evidence_note") or ""),
              f"template evidence note was not preserved for source ID {source_id}")
        check(merged.get("templateAccessNote", "") == (segment.get("access_note") or ""),
              f"template access limits were not preserved for source ID {source_id}")
        expected_segment_template = (segment.get("search_template") or "") if segment.get("template_status") == "verified" else ""
        check(merged.get("search_template", "") == expected_segment_template,
              f"search_template must exactly follow the verified segment field for source ID {source_id}")
        if segment.get("template_status") != "verified" and merged.get("search_template"):
            nonverified_templates.append(source_id)
        check(merged.get("functionalSubcategories"), f"PDF item has no functional facet: {source_id}")

        runtime = merged.get("urlTemplate", "")
        origin = merged.get("urlTemplateOrigin")
        legacy = merged.get("legacyCatalogEntry")
        if merged.get("legacyTemplatePreserved"):
            check(isinstance(legacy, dict), f"legacy snapshot missing for mapped item {source_id}")
            check(runtime == legacy.get("urlTemplate"), f"legacy runtime URL changed for mapped item {source_id}")
            check(merged.get("category") == legacy.get("category"), f"legacy access category changed for mapped item {source_id}")
            check(origin == "legacy_compatibility", f"legacy URL provenance missing for source ID {source_id}")
        elif segment.get("template_status") == "verified" and valid_template(expected_segment_template):
            check(runtime == expected_segment_template and origin == "verified_segment",
                  f"verified supported template not installed for source ID {source_id}")
        else:
            check(runtime == "", f"unverified/unsupported non-legacy record has executable runtime URL: {source_id}")
            if segment.get("template_status") == "verified":
                unsupported_verified.append(source_id)
                check(origin == "verified_but_not_app_executable",
                      f"unsupported verified template should be marked non-executable: {source_id}")
        check(bool(merged.get("templateExecutionAvailable")) == bool(runtime),
              f"runtime executable flag mismatch for source ID {source_id}")
        if runtime:
            check(valid_template(runtime), f"runtime URL template structure invalid for source ID {source_id}")
        if segment.get("template_status") == "verified" and not valid_template(expected_segment_template):
            check(not runtime, f"template with external placeholder must not run in app: {source_id}")

    check(status_counts == {"verified": 264, "unclear": 171, "not_available": 65},
          f"unexpected verification distribution: {dict(status_counts)}")
    check(not nonverified_templates, f"nonverified templates leaked: {nonverified_templates}")
    check(unsupported_verified == [403, 409, 464],
          f"unexpected verified-but-not-app-executable templates: {unsupported_verified}")

    expected_overlap = {}
    legacy_snapshot_ids = set()
    for record in records:
        snapshot = record.get("legacyCatalogEntry")
        if snapshot:
            check(snapshot.get("id") not in legacy_snapshot_ids, f"legacy ID duplicated: {snapshot.get('id')}")
            legacy_snapshot_ids.add(snapshot.get("id"))
            check(record.get("urlTemplate") == snapshot.get("urlTemplate"),
                  f"legacy runtime URL changed for {snapshot.get('id')}")
            if record.get("pdfCatalogId") is not None:
                expected_overlap[snapshot["id"]] = record["pdfCatalogId"]
    check(legacy_snapshot_ids == EXPECTED_LEGACY_IDS,
          f"legacy IDs were lost or added: {legacy_snapshot_ids ^ EXPECTED_LEGACY_IDS}")
    check(len(expected_overlap) == 19, f"expected 19 legacy/PDF overlaps, got {len(expected_overlap)}")
    check({r["id"] for r in records if r.get("pdfCatalogId") is None} == {"naver", "seznam"},
          "the two legacy-only entries Naver and Seznam must remain intact")
    check(all(r.get("iconAvailable") for r in records if r.get("legacyTemplatePreserved")),
          "legacy bundled icon availability must remain intact")

    primary = {r["pdfPrimaryCategory"] for r in pdf_records}
    secondary = {r["pdfSecondaryCategory"] for r in pdf_records}
    functions = {tag for r in pdf_records for tag in r["functionalSubcategories"]}
    check(len(primary) == 23 and len(secondary) == 62 and len(functions) == 27,
          f"unexpected taxonomy counts: primary={len(primary)}, secondary={len(secondary)}, functions={len(functions)}")
    api_configured = [r for r in pdf_records if r.get("serviceType") == "api_or_configured_service"]
    check(all(r["category"] != "FREE" for r in api_configured),
          "API/configured or self-hosted services must not be presented as free public search")
    deployment = [r for r in pdf_records if r.get("accessAssessment") == "deployment_required_not_a_public_search_service"]
    check(all(r["category"] != "FREE" and "不等于已证实收费" in r["accessNote"] for r in deployment),
          "self-hosted entries must be restricted without falsely asserting paid status")

    model = MODEL_PATH.read_text(encoding="utf-8")
    catalog_java = CATALOG_JAVA_PATH.read_text(encoding="utf-8")
    adapter = ADAPTER_PATH.read_text(encoding="utf-8")
    activity = ACTIVITY_PATH.read_text(encoding="utf-8")
    strings = STRINGS_PATH.read_text(encoding="utf-8")
    check("functionalSubcategories" in model and "searchableText" in model
          and "toLowerCase(Locale.ROOT)" in model,
          "model must hold multi-label tags and precomputed normalized searchable text")
    check("getRawPath()" in model and "getRawQuery()" in model and 'replace("+", "%20")' in model,
          "model must validate path/query placeholders and encode path-segment queries safely")
    check("Non-verified segment must not provide search_template" in catalog_java
          and "functionalSubcategories.contains(feature)" in catalog_java
          and "engine.matchesQuery(query)" in catalog_java,
          "JSON loader/filter must enforce template provenance and match multi-value facets")
    check("Non-verified item has a non-legacy runtime URL template" in catalog_java
          and "Template execution flag disagrees with runtime URL" in catalog_java,
          "runtime templates must be limited to verified sources or explicit legacy compatibility")
    check("ListView engineList = new ListView(this)" in activity
          and "new SearchEnginePickerAdapter" in activity
          and "searchEngineFilterHandler.postDelayed(pendingSearchEngineFilter, 70L)" in activity,
          "picker must retain ListView virtualization and input debounce")
    check("cards.removeAllViews()" not in activity[activity.index("private void showSearchEnginePicker()"):
                                                       activity.index("private void showExtensionManager()")],
          "picker must not rebuild all rows during filtering")
    check("searchEngineIconExecutor.execute" in activity and "decodeSearchEngineIcon(assetPath)" in activity
          and "target.setImageDrawable(fallbackSearchEngineIcon())" in activity,
          "icons must keep asynchronous decode and immediate fallback")
    check("accessWarning(engine)" in adapter and "holder.warning.setVisibility" in adapter,
          "recycled picker rows must visibly warn about API, deployment, account, or uncertain cost gates")
    check("search_engine_template_unavailable" in activity and "search_engine_template_unavailable" in strings,
          "unverified/unsupported entries must give a specific no-query-executed notice")
    check('SEARCH_ENGINE_PREFERENCE = "search-engine"' in activity
          and "getString(SEARCH_ENGINE_PREFERENCE" in activity
          and "putString(SEARCH_ENGINE_PREFERENCE" in activity,
          "existing selected-engine preference key/read/write must remain compatible")

    legacy_category_counts = collections.Counter(r["category"] for r in records)
    print("PASS: 502 unique catalog IDs; 500 exact PDF ID/name/category/tag merges; 19 legacy overlaps + 2 retained-only;")
    print(f"      template states={dict(status_counts)}; primary={len(primary)}, secondary={len(secondary)}, functional={len(functions)}")
    print(f"      API/configured services={len(api_configured)}; deployment-only services={len(deployment)}; none are presented as free public search")
    print(f"      access categories={dict(legacy_category_counts)}; supported verified runtime templates={264-len(unsupported_verified)}; unsupported verified={unsupported_verified}")
    print("      JSON provenance, nonverified template suppression, multi-tag filtering, virtualization, debounce, async icons, and stable preference structure.")
    print("NOTE: local structural checks only; no Java/Android compilation or query URL access performed.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise
