package com.cue.simplebrowser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parser and lightweight filtering helpers for the versioned built-in JSON catalog. */
final class SearchEngineCatalog {
    static final int SCHEMA_VERSION = 1;
    private static final Pattern BUILT_IN_ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private SearchEngineCatalog() { }

    static List<SearchEngine> parse(String json) {
        Map<String, Object> root = MiniJson.object(MiniJson.parse(json), "search-engine catalog");
        int schemaVersion = MiniJson.integer(root.get("schemaVersion"), "schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported search-engine catalog schema: " + schemaVersion);
        }
        List<Object> records = MiniJson.array(root.get("engines"), "engines");
        if (records.isEmpty()) {
            throw new IllegalArgumentException("Built-in search-engine catalog is empty");
        }
        ArrayList<SearchEngine> engines = new ArrayList<>(records.size());
        Set<String> ids = new LinkedHashSet<>();
        boolean hasDefault = false;
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = MiniJson.object(records.get(i), "engine at index " + i);
            String id = required(record, "id", i);
            if (!BUILT_IN_ID.matcher(id).matches() || id.startsWith("custom-")) {
                throw new IllegalArgumentException("Invalid built-in engine id at index " + i + ": " + id);
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate built-in engine id: " + id);
            }
            String name = required(record, "name", i);
            String monogram = required(record, "monogram", i);
            String urlTemplate = optionalString(record, "urlTemplate", i);
            String categoryName = required(record, "category", i);
            String purpose = required(record, "purpose", i);
            String accessNote = required(record, "accessNote", i);
            String featureCategory = required(record, "featureCategory", i);
            String caveat = optionalString(record, "caveat", i);
            String templateStatus = optionalString(record, "templateStatus", i);
            String searchTemplate = optionalString(record, "search_template", i);
            String templateEvidenceUrl = optionalString(record, "templateEvidenceUrl", i);
            String templateEvidenceNote = optionalString(record, "templateEvidenceNote", i);
            String templateAccessNote = optionalString(record, "templateAccessNote", i);
            String urlTemplateOrigin = optionalString(record, "urlTemplateOrigin", i);
            boolean templateExecutionAvailable = optionalBoolean(record, "templateExecutionAvailable", i,
                    !urlTemplate.isEmpty());
            boolean iconAvailable = optionalBoolean(record, "iconAvailable", i, true);
            List<String> functionalSubcategories = optionalStrings(record, "functionalSubcategories", i);
            if (functionalSubcategories.isEmpty()) {
                functionalSubcategories.add(featureCategory);
            }
            String pdfPrimaryCategory = optionalString(record, "pdfPrimaryCategory", i);
            String pdfSecondaryCategory = optionalString(record, "pdfSecondaryCategory", i);
            String pdfDescription = optionalString(record, "pdfDescription", i);
            String pdfFeeClassification = optionalString(record, "pdfFeeClassification", i);
            String pdfOperationalStatus = optionalString(record, "pdfOperationalStatus", i);
            String accessAssessment = optionalString(record, "accessAssessment", i);

            SearchEngine.Category category;
            try {
                category = SearchEngine.Category.valueOf(categoryName);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown access category for " + id + ": " + categoryName);
            }

            if (!urlTemplate.isEmpty()) {
                String templateError = SearchEngine.validateUrlTemplate(urlTemplate);
                if (templateError != null) {
                    throw new IllegalArgumentException("Invalid runtime URL template for " + id + ": " + templateError);
                }
            } else if (templateExecutionAvailable) {
                throw new IllegalArgumentException("Template execution marked available without a runtime template for " + id);
            }
            if (!"verified".equals(templateStatus) && !searchTemplate.isEmpty()) {
                throw new IllegalArgumentException("Non-verified segment must not provide search_template for " + id);
            }
            if ("verified".equals(templateStatus) && !searchTemplate.isEmpty()
                    && "verified_segment".equals(urlTemplateOrigin)) {
                String templateError = SearchEngine.validateUrlTemplate(searchTemplate);
                if (templateError != null || !urlTemplate.equals(searchTemplate)) {
                    throw new IllegalArgumentException("Executable verified template mismatch for " + id);
                }
            }
            boolean legacyTemplate = "legacy_compatibility".equals(urlTemplateOrigin)
                    || "legacy_catalog_only".equals(urlTemplateOrigin);
            if (!urlTemplate.isEmpty() && !"verified".equals(templateStatus) && !legacyTemplate) {
                throw new IllegalArgumentException("Non-verified item has a non-legacy runtime URL template for " + id);
            }
            if (templateExecutionAvailable != !urlTemplate.isEmpty()) {
                throw new IllegalArgumentException("Template execution flag disagrees with runtime URL for " + id);
            }

            engines.add(SearchEngine.createBuiltIn(id, name, monogram, urlTemplate,
                    category, purpose, accessNote, caveat, featureCategory,
                    functionalSubcategories, pdfPrimaryCategory, pdfSecondaryCategory,
                    pdfDescription, templateStatus, searchTemplate, templateEvidenceUrl,
                    templateEvidenceNote, templateAccessNote, pdfFeeClassification,
                    pdfOperationalStatus, accessAssessment, iconAvailable));
            if (SearchEngine.DEFAULT_ID.equals(id)) {
                hasDefault = true;
            }
        }
        if (!hasDefault) {
            throw new IllegalArgumentException("Built-in catalog must include Google fallback id 'google'");
        }
        return Collections.unmodifiableList(engines);
    }

    static List<SearchEngine> filter(List<SearchEngine> engines, SearchEngine.Category accessCategory,
                                     String featureCategory, String rawQuery) {
        if (engines == null || engines.isEmpty()) {
            return Collections.emptyList();
        }
        String feature = featureCategory == null ? "" : featureCategory.trim();
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        ArrayList<SearchEngine> filtered = new ArrayList<>();
        for (SearchEngine engine : engines) {
            if (accessCategory != null && engine.category != accessCategory) {
                continue;
            }
            if (!feature.isEmpty() && !engine.functionalSubcategories.contains(feature)
                    && !feature.equals(engine.featureCategory)) {
                continue;
            }
            if (engine.matchesQuery(query)) {
                filtered.add(engine);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    static List<String> featureCategories(List<SearchEngine> engines, SearchEngine.Category accessCategory) {
        if (engines == null || engines.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> found = new LinkedHashSet<>();
        for (SearchEngine engine : engines) {
            if (accessCategory == null || engine.category == accessCategory) {
                for (String feature : engine.functionalSubcategories) {
                    if (feature != null && !feature.isEmpty()) found.add(feature);
                }
                // Preserve the old single-facet chips for original and custom entries.
                if (engine.featureCategory != null && !engine.featureCategory.isEmpty()) {
                    found.add(engine.featureCategory);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(found));
    }

    private static String required(Map<String, Object> record, String key, int index) {
        return MiniJson.string(record.get(key), "engine[" + index + "]." + key).trim();
    }

    private static String optionalString(Map<String, Object> record, String key, int index) {
        Object value = record.get(key);
        if (value == null) return "";
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("engine[" + index + "]." + key + " must be a string");
        }
        return ((String) value).trim();
    }

    private static boolean optionalBoolean(Map<String, Object> record, String key, int index,
                                           boolean defaultValue) {
        Object value = record.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("engine[" + index + "]." + key + " must be a boolean");
        }
        return (Boolean) value;
    }

    private static List<String> optionalStrings(Map<String, Object> record, String key, int index) {
        Object value = record.get(key);
        if (value == null) return new ArrayList<>();
        List<Object> values = MiniJson.array(value, "engine[" + index + "]." + key);
        ArrayList<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            String text = MiniJson.string(item, "engine[" + index + "]." + key + " item").trim();
            if (!text.isEmpty() && !result.contains(text)) result.add(text);
        }
        return result;
    }
}
