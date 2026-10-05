package com.cue.simplebrowser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, non-executing parser for titles, readable text and candidate links. */
final class CrawlerHtmlParser {
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(?is)([a-zA-Z_:][a-zA-Z0-9_.:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Set<String> HIDDEN_CONTENT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "script", "style", "noscript", "template", "svg", "iframe", "object")));
    private CrawlerHtmlParser() { }

    static final class Document {
        final String title;
        final String text;
        final List<String> links;
        Document(String title, String text, List<String> links) {
            this.title = title;
            this.text = text;
            this.links = links;
        }
    }

    static Document parsePlain(String source) {
        if (source == null) return new Document("", "", Collections.<String>emptyList());
        String bounded = source.length() > 12_000 ? source.substring(0, 12_000) : source;
        return new Document("", collapseWhitespace(bounded), Collections.<String>emptyList());
    }

    static Document parse(String source, String pageUrl) {
        if (source == null) return new Document("", "", Collections.<String>emptyList());
        StringBuilder text = new StringBuilder(Math.min(source.length(), 16_384));
        StringBuilder title = new StringBuilder(256);
        LinkedHashSet<String> links = new LinkedHashSet<>();
        int hiddenDepth = 0;
        boolean inTitle = false;
        int cursor = 0;
        while (cursor < source.length() && text.length() < 12_000) {
            int opening = source.indexOf('<', cursor);
            if (opening < 0) {
                if (hiddenDepth == 0) appendText(text, source.substring(cursor));
                if (inTitle) appendBounded(title, decodeEntities(source.substring(cursor)), 512);
                break;
            }
            if (opening > cursor) {
                String plain = source.substring(cursor, opening);
                if (hiddenDepth == 0) appendText(text, plain);
                if (inTitle) appendBounded(title, decodeEntities(plain), 512);
            }
            int closing = findTagEnd(source, opening + 1);
            if (closing < 0) break;
            String rawTag = source.substring(opening + 1, closing).trim();
            cursor = closing + 1;
            if (rawTag.isEmpty() || rawTag.charAt(0) == '!' || rawTag.charAt(0) == '?') continue;
            boolean endTag = rawTag.charAt(0) == '/';
            if (endTag) rawTag = rawTag.substring(1).trim();
            int nameEnd = 0;
            while (nameEnd < rawTag.length() && (Character.isLetterOrDigit(rawTag.charAt(nameEnd))
                    || rawTag.charAt(nameEnd) == '-' || rawTag.charAt(nameEnd) == ':')) nameEnd++;
            if (nameEnd == 0) continue;
            String name = rawTag.substring(0, nameEnd).toLowerCase(Locale.ROOT);
            if ("title".equals(name)) {
                if (endTag) inTitle = false;
                else inTitle = true;
            }
            if (HIDDEN_CONTENT.contains(name)) {
                if (endTag) hiddenDepth = Math.max(0, hiddenDepth - 1);
                else if (!rawTag.endsWith("/")) hiddenDepth++;
                continue;
            }
            if (hiddenDepth != 0) continue;
            if (endTag) {
                if (isBlock(name)) appendSpace(text);
                continue;
            }
            if ("a".equals(name) && links.size() < ControlledCrawler.MAX_LINKS * 4) {
                String href = attribute(rawTag.substring(nameEnd), "href");
                String candidate = resolveLink(pageUrl, href);
                if (candidate != null) links.add(candidate);
            }
            if (isBlock(name)) appendSpace(text);
        }
        return new Document(collapseWhitespace(decodeEntities(title.toString())),
                collapseWhitespace(text.toString()), new ArrayList<>(links));
    }

    private static int findTagEnd(String source, int start) {
        char quote = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return i;
        }
        return -1;
    }

    private static String attribute(String attributes, String name) {
        Matcher matcher = ATTRIBUTE.matcher(attributes);
        while (matcher.find()) {
            if (!matcher.group(1).equalsIgnoreCase(name)) continue;
            for (int group = 2; group <= 4; group++) {
                if (matcher.group(group) != null) return decodeEntities(matcher.group(group).trim());
            }
        }
        return null;
    }

    private static String resolveLink(String base, String href) {
        if (href == null || href.isEmpty() || href.length() > 2_048) return null;
        try {
            URI target = new URI(href.trim());
            String scheme = target.getScheme();
            if (scheme != null && !scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")) return null;
            if (target.getUserInfo() != null) return null;
            target = new URI(base).resolve(target).normalize();
            if (target.getHost() == null || target.getRawUserInfo() != null) return null;
            return target.toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isBlock(String tag) {
        return "p".equals(tag) || "div".equals(tag) || "br".equals(tag) || "li".equals(tag)
                || "h1".equals(tag) || "h2".equals(tag) || "h3".equals(tag) || "h4".equals(tag)
                || "section".equals(tag) || "article".equals(tag) || "tr".equals(tag)
                || "td".equals(tag) || "th".equals(tag) || "main".equals(tag) || "header".equals(tag)
                || "footer".equals(tag) || "blockquote".equals(tag) || "pre".equals(tag);
    }

    private static void appendText(StringBuilder output, String value) {
        if (output.length() >= 12_000) return;
        appendBounded(output, decodeEntities(value), 12_000);
        appendSpace(output);
    }

    private static void appendSpace(StringBuilder output) {
        if (output.length() == 0 || output.charAt(output.length() - 1) != ' ') output.append(' ');
    }

    private static void appendBounded(StringBuilder output, String value, int maximum) {
        int remaining = maximum - output.length();
        if (remaining > 0 && value != null) output.append(value, 0, Math.min(remaining, value.length()));
    }

    private static String collapseWhitespace(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Z}\\s]+", " ").trim();
    }

    private static String decodeEntities(String value) {
        if (value == null || value.indexOf('&') < 0) return value == null ? "" : value;
        StringBuilder output = new StringBuilder(Math.min(value.length(), 12_000));
        for (int i = 0; i < value.length();) {
            char ch = value.charAt(i);
            if (ch != '&') {
                output.append(ch);
                i++;
                continue;
            }
            int semicolon = value.indexOf(';', i + 1);
            if (semicolon < 0 || semicolon - i > 12) {
                output.append(ch);
                i++;
                continue;
            }
            String entity = value.substring(i + 1, semicolon);
            Integer codePoint = entityCodePoint(entity);
            if (codePoint == null || !Character.isValidCodePoint(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                output.append(value, i, semicolon + 1);
            } else {
                output.appendCodePoint(codePoint);
            }
            i = semicolon + 1;
        }
        return output.toString();
    }

    private static Integer entityCodePoint(String entity) {
        if (entity.startsWith("#")) {
            try {
                if (entity.length() > 2 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X'))
                    return Integer.parseInt(entity.substring(2), 16);
                return Integer.parseInt(entity.substring(1));
            } catch (NumberFormatException ignored) { return null; }
        }
        switch (entity.toLowerCase(Locale.ROOT)) {
            case "amp": return (int) '&';
            case "lt": return (int) '<';
            case "gt": return (int) '>';
            case "quot": return (int) '"';
            case "apos": return (int) '\'';
            case "nbsp": return (int) ' ';
            case "ndash": return 0x2013;
            case "mdash": return 0x2014;
            case "hellip": return 0x2026;
            case "copy": return 0x00a9;
            case "reg": return 0x00ae;
            default: return null;
        }
    }
}
