package com.cue.simplebrowser;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Search providers supported by the address bar and their access metadata. */
final class SearchEngine {
    enum Category {
        FREE("免费且无需账号"),
        ACCOUNT_REQUIRED("免费需账号"),
        PAID("付费");

        final String label;

        Category(String label) {
            this.label = label;
        }
    }

    static final String DEFAULT_ID = "google";
    static final List<Category> CATEGORIES = Collections.unmodifiableList(
            Arrays.asList(Category.values()));
    private static final Pattern CUSTOM_ID = Pattern.compile("^custom-[a-zA-Z0-9-]{1,80}$");
    private static final String QUERY_PLACEHOLDER = "{query}";

    /** Replaced once at startup from the bundled, versioned JSON asset. */
    static volatile List<SearchEngine> ALL = Collections.emptyList();

    final String id;
    final String name;
    final String monogram;
    /** Runtime template. Empty means this catalogue item is descriptive-only, not executable. */
    final String urlTemplate;
    final Category category;
    final String purpose;
    final String accessNote;
    final String caveat;
    /** Legacy single facet retained for compatibility with the original picker and custom engines. */
    final String featureCategory;
    /** PDF-derived multi-label functional facets, independent of access category. */
    final List<String> functionalSubcategories;
    final String pdfPrimaryCategory;
    final String pdfSecondaryCategory;
    final String pdfDescription;
    final String templateStatus;
    /** Exact segment JSON field; blank for every status other than verified. */
    final String searchTemplate;
    final String templateEvidenceUrl;
    final String templateEvidenceNote;
    final String templateAccessNote;
    final String pdfFeeClassification;
    final String pdfOperationalStatus;
    final String accessAssessment;
    final boolean iconAvailable;
    final boolean custom;
    /** Lower-cased once per record so filtering never rebuilds text on each keystroke. */
    final String searchableText;

    private SearchEngine(String id, String name, String monogram, String urlTemplate,
                         Category category, String purpose, String accessNote, String caveat,
                         String featureCategory, List<String> functionalSubcategories,
                         String pdfPrimaryCategory, String pdfSecondaryCategory, String pdfDescription,
                         String templateStatus, String searchTemplate, String templateEvidenceUrl,
                         String templateEvidenceNote, String templateAccessNote,
                         String pdfFeeClassification, String pdfOperationalStatus,
                         String accessAssessment, boolean iconAvailable, boolean custom) {
        this.id = id;
        this.name = name;
        this.monogram = monogram;
        this.urlTemplate = urlTemplate == null ? "" : urlTemplate.trim();
        this.category = category;
        this.purpose = purpose;
        this.accessNote = accessNote;
        this.caveat = caveat;
        this.featureCategory = featureCategory == null ? "" : featureCategory.trim();
        ArrayList<String> tags = new ArrayList<>();
        if (functionalSubcategories != null) {
            for (String tag : functionalSubcategories) {
                if (tag != null && !tag.trim().isEmpty() && !tags.contains(tag.trim())) {
                    tags.add(tag.trim());
                }
            }
        }
        this.functionalSubcategories = Collections.unmodifiableList(tags);
        this.pdfPrimaryCategory = safe(pdfPrimaryCategory);
        this.pdfSecondaryCategory = safe(pdfSecondaryCategory);
        this.pdfDescription = safe(pdfDescription);
        this.templateStatus = safe(templateStatus);
        this.searchTemplate = safe(searchTemplate);
        this.templateEvidenceUrl = safe(templateEvidenceUrl);
        this.templateEvidenceNote = safe(templateEvidenceNote);
        this.templateAccessNote = safe(templateAccessNote);
        this.pdfFeeClassification = safe(pdfFeeClassification);
        this.pdfOperationalStatus = safe(pdfOperationalStatus);
        this.accessAssessment = safe(accessAssessment);
        this.iconAvailable = iconAvailable;
        this.custom = custom;
        StringBuilder index = new StringBuilder(name).append(' ').append(purpose).append(' ')
                .append(accessNote).append(' ').append(caveat).append(' ').append(this.featureCategory)
                .append(' ').append(this.pdfPrimaryCategory).append(' ').append(this.pdfSecondaryCategory)
                .append(' ').append(this.pdfDescription).append(' ').append(this.pdfFeeClassification)
                .append(' ').append(this.pdfOperationalStatus).append(' ').append(this.templateStatus)
                .append(' ').append(this.templateAccessNote).append(' ').append(this.templateEvidenceNote);
        for (String tag : tags) index.append(' ').append(tag);
        this.searchableText = index.toString().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    static SearchEngine createBuiltIn(String id, String name, String monogram, String urlTemplate,
                                      Category category, String purpose, String accessNote,
                                      String caveat, String featureCategory) {
        return createBuiltIn(id, name, monogram, urlTemplate, category, purpose, accessNote,
                caveat, featureCategory, Collections.singletonList(featureCategory), "", "", "",
                "legacy_existing", "", "", "", "", "", "", "legacy", true);
    }

    static SearchEngine createBuiltIn(String id, String name, String monogram, String urlTemplate,
                                      Category category, String purpose, String accessNote,
                                      String caveat, String featureCategory,
                                      List<String> functionalSubcategories,
                                      String pdfPrimaryCategory, String pdfSecondaryCategory,
                                      String pdfDescription, String templateStatus,
                                      String searchTemplate, String templateEvidenceUrl,
                                      String templateEvidenceNote, String templateAccessNote,
                                      String pdfFeeClassification, String pdfOperationalStatus,
                                      String accessAssessment, boolean iconAvailable) {
        return new SearchEngine(id, name, monogram, urlTemplate, category, purpose, accessNote,
                caveat, featureCategory, functionalSubcategories, pdfPrimaryCategory,
                pdfSecondaryCategory, pdfDescription, templateStatus, searchTemplate,
                templateEvidenceUrl, templateEvidenceNote, templateAccessNote,
                pdfFeeClassification, pdfOperationalStatus, accessAssessment, iconAvailable, false);
    }

    static void installBuiltIns(List<SearchEngine> builtIns) {
        if (builtIns == null || builtIns.isEmpty()) {
            throw new IllegalArgumentException("Built-in search-engine catalog is empty");
        }
        boolean hasDefault = false;
        for (SearchEngine engine : builtIns) {
            if (engine == null || engine.custom) {
                throw new IllegalArgumentException("Invalid built-in search-engine entry");
            }
            if (DEFAULT_ID.equals(engine.id)) {
                hasDefault = true;
            }
        }
        if (!hasDefault) {
            throw new IllegalArgumentException("Default search engine is missing");
        }
        ALL = Collections.unmodifiableList(new ArrayList<>(builtIns));
    }

    static SearchEngine createCustom(String id, String name, String urlTemplate, Category category,
                                     String purpose, String accessNote) {
        if (id == null || !CUSTOM_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("自定义引擎 ID 无效");
        }
        String cleanName = cleanRequired(name, 80, "请填写引擎名称");
        String cleanTemplate = urlTemplate == null ? "" : urlTemplate.trim();
        String templateError = validateUrlTemplate(cleanTemplate);
        if (templateError != null) {
            throw new IllegalArgumentException(templateError);
        }
        if (category == null) {
            throw new IllegalArgumentException("请选择访问分类");
        }
        String cleanPurpose = cleanRequired(purpose, 100, "请填写用途说明");
        String cleanAccess = cleanRequired(accessNote, 120, "请填写访问提示");
        int codePoint = cleanName.codePointAt(0);
        String monogram = new String(Character.toChars(codePoint)).toUpperCase(Locale.ROOT);
        return new SearchEngine(id, cleanName, monogram, cleanTemplate, category,
                cleanPurpose, cleanAccess, "", "自定义", Collections.singletonList("自定义"),
                "", "", "", "custom", "", "", "", "", "", "", "custom",
                false, true);
    }

    private static String cleanRequired(String value, int maxLength, String error) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maxLength) {
            throw new IllegalArgumentException(error);
        }
        return clean;
    }

    /** Accept a single HTTPS placeholder in either the path or query component. */
    static String validateUrlTemplate(String rawTemplate) {
        if (rawTemplate == null) {
            return "请输入 HTTPS 搜索 URL 模板";
        }
        String template = rawTemplate.trim();
        if (template.isEmpty() || template.length() > 2048) {
            return "URL 模板不能为空且不得超过 2048 个字符";
        }
        int placeholder = template.indexOf(QUERY_PLACEHOLDER);
        if (placeholder < 0 || placeholder != template.lastIndexOf(QUERY_PLACEHOLDER)) {
            return "URL 模板必须且只能包含一个 {query} 占位符";
        }
        if (template.indexOf('{') != placeholder || template.indexOf('}') != placeholder + QUERY_PLACEHOLDER.length() - 1) {
            return "URL 模板只允许使用 {query} 占位符";
        }
        if (template.indexOf('#') >= 0) {
            return "URL 模板不能包含片段部分";
        }
        for (int i = 0; i < template.length(); i++) {
            if (Character.isWhitespace(template.charAt(i)) || Character.isISOControl(template.charAt(i))) {
                return "URL 模板不能包含空格或控制字符";
            }
        }
        try {
            String marker = "safe-query-value";
            URI uri = new URI(template.replace(QUERY_PLACEHOLDER, marker));
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return "仅允许 HTTPS 搜索 URL";
            }
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535 || uri.getRawFragment() != null) {
                return "URL 主机或结构无效；不能包含用户名、密码或片段";
            }
            if (uri.getRawAuthority() != null && uri.getRawAuthority().contains("%")) {
                return "URL 主机部分不能包含百分号编码";
            }
            boolean inPath = uri.getRawPath() != null && uri.getRawPath().contains(marker);
            boolean inQuery = uri.getRawQuery() != null && uri.getRawQuery().contains(marker);
            if (!inPath && !inQuery) {
                return "{query} 必须位于 URL 路径或查询参数中，不能位于主机名";
            }
        } catch (Exception exception) {
            return "URL 模板格式无效";
        }
        return null;
    }

    static List<SearchEngine> withCustom(List<SearchEngine> customEngines) {
        ArrayList<SearchEngine> combined = new ArrayList<>(ALL);
        if (customEngines != null) {
            combined.addAll(customEngines);
        }
        return Collections.unmodifiableList(combined);
    }

    static SearchEngine byId(String id) {
        return byId(id, ALL);
    }

    static SearchEngine byId(String id, List<SearchEngine> engines) {
        if (id != null && engines != null) {
            for (SearchEngine engine : engines) {
                if (engine.id.equals(id)) {
                    return engine;
                }
            }
        }
        for (SearchEngine engine : ALL) {
            if (DEFAULT_ID.equals(engine.id)) {
                return engine;
            }
        }
        throw new IllegalStateException("Default search engine is missing");
    }

    boolean matchesQuery(String normalizedQuery) {
        return normalizedQuery == null || normalizedQuery.isEmpty()
                || searchableText.contains(normalizedQuery);
    }

    String iconAssetPath() {
        return custom || !iconAvailable ? null : "engine-icons/" + id + ".png";
    }

    String buildSearchUrl(String query) {
        if (query == null || urlTemplate.isEmpty()) {
            return null;
        }
        String trimmedQuery = query.trim();
        if (trimmedQuery.isEmpty()) return null;
        try {
            String encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8");
            int queryStart = urlTemplate.indexOf('?');
            int placeholder = urlTemplate.indexOf(QUERY_PLACEHOLDER);
            if (queryStart < 0 || placeholder < queryStart) {
                encodedQuery = encodedQuery.replace("+", "%20");
            }
            return urlTemplate.replace(QUERY_PLACEHOLDER, encodedQuery);
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new AssertionError("UTF-8 is required on Android", exception);
        }
    }
}
