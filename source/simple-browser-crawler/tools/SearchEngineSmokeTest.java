package com.cue.simplebrowser;

import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free regression checks for the provider catalog, templates, and custom engine store. */
public final class SearchEngineSmokeTest {
    private SearchEngineSmokeTest() { }

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "expected app assets directory and engine icon provenance manifest");
        String catalogJson = Files.readString(Path.of(args[0]).resolve("search-engines/catalog.v1.json"),
                StandardCharsets.UTF_8);
        SearchEngine.installBuiltIns(SearchEngineCatalog.parse(catalogJson));
        check(SearchEngine.ALL.size() == 502, "the 500 PDF entries and all 21 legacy entries must remain in the catalog");
        check(SearchEngine.CATEGORIES.size() == 3, "the picker must expose exactly three categories");
        check(SearchEngine.CATEGORIES.get(0).label.equals("免费且无需账号"), "no-account category label changed");
        check(SearchEngine.CATEGORIES.get(1).label.equals("免费需账号"), "account category label changed");
        check(SearchEngine.CATEGORIES.get(2).label.equals("付费"), "paid category label changed");
        check(SearchEngine.DEFAULT_ID.equals("google"), "Google must remain the default engine");
        check(SearchEngine.byId("unknown-provider").id.equals(SearchEngine.DEFAULT_ID),
                "unknown persisted ids must safely fall back to Google");
        check(SearchEngine.byId(null).id.equals(SearchEngine.DEFAULT_ID),
                "null persisted ids must safely fall back to Google");

        Set<String> expectedIds = new HashSet<>(Arrays.asList("google", "bing", "yahoo", "duckduckgo",
                "brave", "startpage", "ecosia", "qwant", "mojeek", "swisscows", "yandex", "kagi",
                "naver", "seznam", "metager", "perplexity", "you", "marginalia", "wiby", "mwmbl",
                "wikipedia"));
        Set<String> actualIds = new HashSet<>();
        for (SearchEngine engine : SearchEngine.ALL) actualIds.add(engine.id);
        check(actualIds.containsAll(expectedIds) && actualIds.size() == 502,
                "keep all original IDs, merge the PDF catalog without ID conflicts, and exclude domestic providers");
        for (String forbidden : Arrays.asList("baidu", "sogou", "shenma", "quark", "360")) {
            check(!actualIds.contains(forbidden), "domestic provider must remain excluded: " + forbidden);
        }
        check(SearchEngine.byId("wikipedia").buildSearchUrl("cats & 雪").equals(
                        "https://en.wikipedia.org/w/index.php?search=cats+%26+%E9%9B%AA"),
                "Wikipedia must use its official article search URL and UTF-8 query encoding");

        Map<String, SearchEngine.Category> expected = new HashMap<>();
        for (SearchEngine engine : SearchEngine.ALL) {
            if (expectedIds.contains(engine.id)) expected.put(engine.id, SearchEngine.Category.FREE);
        }
        expected.put("you", SearchEngine.Category.PAID);
        expected.put("kagi", SearchEngine.Category.ACCOUNT_REQUIRED);
        expected.put("metager", SearchEngine.Category.PAID);
        expected.put("perplexity", SearchEngine.Category.PAID);
        expected.put("yandex", SearchEngine.Category.PAID);
        expected.put("marginalia", SearchEngine.Category.PAID);
        expected.put("mwmbl", SearchEngine.Category.ACCOUNT_REQUIRED);
        for (SearchEngine engine : SearchEngine.ALL) {
            if (expected.containsKey(engine.id)) {
                check(engine.category == expected.get(engine.id), "legacy access category changed for " + engine.id);
            }
            check(engine.purpose != null && !engine.purpose.trim().isEmpty(), engine.name + " needs a purpose");
            check(engine.accessNote != null && !engine.accessNote.trim().isEmpty(),
                    engine.name + " needs an access note");
            if (!engine.urlTemplate.isEmpty()) {
                check(engine.urlTemplate.startsWith("https://"), engine.name + " must use HTTPS");
            }
            if (expectedIds.contains(engine.id)) {
                check(engine.iconAssetPath() != null && engine.iconAssetPath().equals("engine-icons/" + engine.id + ".png"),
                        engine.name + " must map to its bundled official icon asset");
                check(Files.isRegularFile(Path.of(args[0]).resolve(engine.iconAssetPath())),
                        engine.name + " official icon asset is missing");
            } else {
                check(engine.iconAssetPath() == null, engine.name + " must use the search-icon fallback without a bundled icon");
            }
        }

        Set<String> provenanceIds = new HashSet<>();
        int provenanceRows = 0;
        for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("engine_id\t")) continue;
            String[] fields = line.split("\\t", -1);
            check(fields.length == 8, "each icon provenance row must have eight tab-separated fields");
            check(actualIds.contains(fields[0]) && provenanceIds.add(fields[0]),
                    "icon provenance must have exactly one row per known provider: " + fields[0]);
            SearchEngine engine = SearchEngine.byId(fields[0]);
            check(fields[1].equals(engine.name) && fields[2].equals(engine.iconAssetPath()),
                    "provenance name/path must match the catalog for " + fields[0]);
            check(fields[3].startsWith("https://") && fields[4].startsWith("https://"),
                    "icon and official provider source URLs must use HTTPS for " + fields[0]);
            check(fields[6].contains("trademarks") && fields[6].contains("No permissive icon license was asserted"),
                    "icon provenance must preserve trademark attribution and avoid claiming an unverified license");
            Path asset = Path.of(args[0]).resolve(engine.iconAssetPath());
            check(fields[7].matches("[0-9a-f]{64}") && fields[7].equals(sha256(asset)),
                    "icon asset hash must match provenance for " + fields[0]);
            provenanceRows++;
        }
        check(provenanceRows == 21 && provenanceIds.equals(expectedIds),
                "provenance manifest must cover the original bundled icons exactly once");

        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> templates = new HashSet<>();
        String sample = "  cats & dogs + 雪  ";
        String encoded = URLEncoder.encode(sample.trim(), StandardCharsets.UTF_8.name());
        check(encoded.contains("%26") && encoded.contains("%2B") && encoded.contains("%E9%9B%AA"),
                "the smoke-test query must exercise reserved and non-ASCII characters");
        for (SearchEngine engine : SearchEngine.ALL) {
            check(ids.add(engine.id), "duplicate id: " + engine.id);
            check(names.add(engine.name), "duplicate display name: " + engine.name);
            if (engine.urlTemplate.isEmpty()) {
                check(engine.buildSearchUrl(sample) == null,
                        engine.name + " without a runtime template must not execute a query");
                check(engine.buildSearchUrl(null) == null, engine.name + " should ignore null queries");
                check(engine.buildSearchUrl(" \t\n ") == null, engine.name + " should ignore blank queries");
                continue;
            }
            templates.add(engine.urlTemplate);
            check(engine.urlTemplate.indexOf("{query}") >= 0
                            && engine.urlTemplate.indexOf("{query}") == engine.urlTemplate.lastIndexOf("{query}"),
                    engine.name + " must have exactly one query placeholder");
            String expectedEncoding = encoded;
            int queryStart = engine.urlTemplate.indexOf('?');
            int placeholder = engine.urlTemplate.indexOf("{query}");
            if (queryStart < 0 || placeholder < queryStart) expectedEncoding = expectedEncoding.replace("+", "%20");
            String expectedUrl = engine.urlTemplate.replace("{query}", expectedEncoding);
            check(expectedUrl.equals(engine.buildSearchUrl(sample)), engine.name + " URL encoding mismatch");
            String regularEncoding = queryStart < 0 || placeholder < queryStart
                    ? "hello%20world" : "hello+world";
            check(engine.buildSearchUrl("  hello world  ").equals(
                    engine.urlTemplate.replace("{query}", regularEncoding)),
                    engine.name + " should trim and encode a regular query");
            check(engine.buildSearchUrl(null) == null, engine.name + " should ignore null queries");
            check(engine.buildSearchUrl(" \t\n ") == null, engine.name + " should ignore blank queries");
        }
        check(SearchEngine.ALL.size() == ids.size(), "catalog count does not match unique ids");

        List<SearchEngine> filtered = SearchEngineCatalog.filter(SearchEngine.ALL,
                SearchEngine.Category.FREE, "隐私搜索", " STARTPAGE ");
        check(filtered.size() == 1 && filtered.get(0).id.equals("startpage"),
                "precomputed name/purpose/feature text should filter case-insensitively by access and feature facets");
        check(SearchEngineCatalog.featureCategories(SearchEngine.ALL, SearchEngine.Category.PAID)
                        .contains("隐私搜索"),
                "fine-grained feature categories should coexist with access categories");

        List<SearchEngine> worstCaseCategory = new ArrayList<>();
        for (int i = 0; i < 521; i++) {
            worstCaseCategory.add(SearchEngine.createBuiltIn("smoke-provider-" + i,
                    "Smoke Provider " + i, "S", "https://example.test/search?q={query}",
                    SearchEngine.Category.FREE, "长尾测试用途", "免费", "", "长尾专题"));
        }
        List<SearchEngine> worstCaseFiltered = SearchEngineCatalog.filter(worstCaseCategory,
                SearchEngine.Category.FREE, "长尾专题", "SMOKE PROVIDER 520");
        check(worstCaseCategory.size() == 521 && worstCaseFiltered.size() == 1
                        && worstCaseFiltered.get(0).id.equals("smoke-provider-520"),
                "521 entries in one access category should remain filterable through the precomputed catalog path");
        check(SearchEngineCatalog.filter(worstCaseCategory, SearchEngine.Category.PAID,
                        "长尾专题", "").isEmpty(),
                "access-category filtering must remain independent from feature-category filtering");
        check(SearchEngine.byId("wikipedia", SearchEngine.ALL).id.equals("wikipedia"),
                "an id stored by an earlier app version must still resolve after JSON migration");

        String mainActivity = Files.readString(Path.of(args[0]).getParent()
                .resolve("java/com/cue/simplebrowser/MainActivity.java"), StandardCharsets.UTF_8);
        check(mainActivity.contains("SEARCH_ENGINE_PREFERENCE = \"search-engine\""),
                "the persisted selected-engine preference key must remain compatible");
        check(mainActivity.contains("target.setImageDrawable(fallbackSearchEngineIcon())")
                        && mainActivity.contains("unavailableEngineIcons.contains(engine.id)")
                        && mainActivity.contains("searchEngineIconExecutor.execute")
                        && mainActivity.contains("decoded = decodeSearchEngineIcon(assetPath)"),
                "missing icon assets must leave a visible fallback while bundled images decode asynchronously");

        check(SearchEngine.validateUrlTemplate("https://example.org/search?q={query}") == null,
                "valid HTTPS query template should be accepted");
        check(SearchEngine.validateUrlTemplate("https://example.org/search/{query}") == null,
                "verified path-segment templates should be accepted");
        check(SearchEngine.validateUrlTemplate("http://example.org/search?q={query}") != null,
                "insecure HTTP template must be rejected");
        check(SearchEngine.validateUrlTemplate("javascript:alert(1)?q={query}") != null,
                "non-HTTPS scheme must be rejected");
        check(SearchEngine.validateUrlTemplate("https://example.org/{query}?q=x") == null,
                "path-segment placeholders are supported and encoded when a search URL is built");
        check(SearchEngine.validateUrlTemplate("https://example.org/search?q={query}&x={query}") != null,
                "multiple placeholders must be rejected");
        check(SearchEngine.validateUrlTemplate("https://example.org/search?q={query}#fragment") != null,
                "fragment-based template must be rejected");
        check(SearchEngine.validateUrlTemplate("https://user:pass@example.org/search?q={query}") != null,
                "credentials in URL authority must be rejected");
        check(SearchEngine.validateUrlTemplate("https://example.org/search?q={query}&bad=%ZZ") != null,
                "malformed URL encoding must be rejected");

        MemoryPersistence memory = new MemoryPersistence();
        CustomEngineStore store = new CustomEngineStore(memory);
        store.load();
        SearchEngine custom = SearchEngine.createCustom("custom-smoke-1", "示例搜索",
                "https://example.org/search?q={query}", SearchEngine.Category.FREE,
                "示例用途", "免费且无需账号");
        check(custom.iconAssetPath() == null,
                "custom engines without bundled icons must use the picker fallback instead of opening a missing asset");
        check(store.add(custom), "custom engine should be added");
        check(!store.add(custom), "duplicate custom engine must not be added");
        check(!store.remove("google"), "built-in engine must not be removable from custom store");
        check(!store.edit("google", custom), "built-in engine must not be editable from custom store");

        CustomEngineStore restored = new CustomEngineStore(memory);
        restored.load();
        check(restored.getAll().size() == 1, "custom engine should persist across store instances");
        check(restored.getAll().get(0).name.equals("示例搜索"), "custom UTF-8 fields should persist");
        check(SearchEngine.withCustom(restored.getAll()).size() == 503, "custom engine should join picker catalog");
        check(SearchEngine.byId(custom.id, SearchEngine.withCustom(restored.getAll())).id.equals(custom.id),
                "selected custom engine id should resolve");

        SearchEngine edited = SearchEngine.createCustom(custom.id, "更新搜索",
                "https://example.org/find?query={query}", SearchEngine.Category.ACCOUNT_REQUIRED,
                "更新用途", "免费需账号");
        check(restored.edit(custom.id, edited), "custom engine should be editable");
        CustomEngineStore editedReload = new CustomEngineStore(memory);
        editedReload.load();
        check(editedReload.getAll().get(0).name.equals("更新搜索"), "edited engine should persist");
        check(editedReload.getAll().get(0).category == SearchEngine.Category.ACCOUNT_REQUIRED,
                "edited category should persist");
        check(editedReload.getAll().get(0).buildSearchUrl(" cats & dogs ").equals(
                "https://example.org/find?query=cats+%26+dogs"), "custom query must be encoded safely");
        check(editedReload.remove(custom.id), "custom engine should be removable");
        CustomEngineStore removedReload = new CustomEngineStore(memory);
        removedReload.load();
        check(removedReload.getAll().isEmpty(), "custom removal should persist");

        System.out.println("PASS: versioned JSON structure, 21 stable built-ins, unique ids, exact categories, "
                + "521-item filtering, selection compatibility, icon fallback/provenance, HTTPS templates, "
                + "query encoding, and custom persistence");
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private static final class MemoryPersistence implements CustomEngineStore.Persistence {
        private String value;
        @Override public String read() { return value; }
        @Override public void write(String value) { this.value = value; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
