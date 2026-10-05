package com.cue.simplebrowser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local-only, bounded history and bookmark storage. No network or account sync is used. */
final class BrowserDataStore {
    static final int MAX_HISTORY = 500;
    static final int MAX_BOOKMARKS = 500;
    private static final int MAX_ENCODED_CHARS = 2_000_000;

    interface Persistence {
        String readHistory();
        String readBookmarks();
        void writeHistory(String value);
        void writeBookmarks(String value);
    }

    static final class Entry {
        final String url;
        final String title;
        final long visitedAt;

        Entry(String url, String title, long visitedAt) {
            this.url = url;
            this.title = title;
            this.visitedAt = visitedAt;
        }
    }

    private final Persistence persistence;
    private final ArrayList<Entry> history = new ArrayList<>();
    private final LinkedHashMap<String, Entry> bookmarks = new LinkedHashMap<>();

    BrowserDataStore(Persistence persistence) {
        if (persistence == null) throw new IllegalArgumentException("Persistence is required");
        this.persistence = persistence;
        decodeHistory(persistence.readHistory());
        decodeBookmarks(persistence.readBookmarks());
    }

    synchronized void recordVisit(String rawUrl, String rawTitle, long visitedAt) {
        String url = normalizeUrl(rawUrl);
        if (url == null || visitedAt < 0) return;
        String title = cleanTitle(rawTitle, url);
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).url.equals(url)) {
                history.remove(i);
                break;
            }
        }
        history.add(0, new Entry(url, title, visitedAt));
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        persistence.writeHistory(encodeEntries(history));
    }

    synchronized List<Entry> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    synchronized void clearHistory() {
        history.clear();
        persistence.writeHistory("");
    }

    synchronized boolean addBookmark(String rawUrl, String rawTitle, long addedAt) {
        String url = normalizeUrl(rawUrl);
        if (url == null || addedAt < 0) return false;
        if (bookmarks.containsKey(url)) return false;
        if (bookmarks.size() >= MAX_BOOKMARKS) return false;
        bookmarks.put(url, new Entry(url, cleanTitle(rawTitle, url), addedAt));
        persistence.writeBookmarks(encodeEntries(bookmarks.values()));
        return true;
    }

    synchronized boolean removeBookmark(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (url == null || bookmarks.remove(url) == null) return false;
        persistence.writeBookmarks(encodeEntries(bookmarks.values()));
        return true;
    }

    synchronized boolean isBookmarked(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        return url != null && bookmarks.containsKey(url);
    }

    synchronized List<Entry> bookmarks() {
        return Collections.unmodifiableList(new ArrayList<>(bookmarks.values()));
    }

    private void decodeHistory(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_CHARS) return;
        String[] rows = encoded.split("\\n");
        for (int i = rows.length - 1; i >= 0 && history.size() < MAX_HISTORY; i--) {
            Entry entry = decodeEntry(rows[i]);
            if (entry == null) continue;
            for (int existing = 0; existing < history.size(); existing++) {
                if (history.get(existing).url.equals(entry.url)) {
                    history.remove(existing);
                    break;
                }
            }
            history.add(0, entry);
        }
    }

    private void decodeBookmarks(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_CHARS) return;
        String[] rows = encoded.split("\\n");
        for (String row : rows) {
            if (bookmarks.size() >= MAX_BOOKMARKS) break;
            Entry entry = decodeEntry(row);
            if (entry != null) bookmarks.putIfAbsent(entry.url, entry);
        }
    }

    private static Entry decodeEntry(String row) {
        if (row == null || row.isEmpty()) return null;
        String[] fields = row.split("\\t", -1);
        if (fields.length != 3) return null;
        try {
            String url = normalizeUrl(decode(fields[0]));
            String title = cleanTitle(decode(fields[1]), url);
            long timestamp = Long.parseLong(fields[2]);
            if (url == null || timestamp < 0) return null;
            return new Entry(url, title, timestamp);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String encodeEntries(Iterable<Entry> entries) {
        StringBuilder output = new StringBuilder();
        for (Entry entry : entries) {
            if (output.length() > 0) output.append('\n');
            output.append(encode(entry.url)).append('\t')
                    .append(encode(entry.title)).append('\t')
                    .append(entry.visitedAt);
        }
        return output.toString();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String normalizeUrl(String value) {
        if (value == null) return null;
        String url = value.trim();
        if (url.length() > 8192 || url.isEmpty()) return null;
        if (!(url.regionMatches(true, 0, "https://", 0, 8)
                || url.regionMatches(true, 0, "http://", 0, 7))) return null;
        for (int i = 0; i < url.length(); i++) {
            if (Character.isISOControl(url.charAt(i))) return null;
        }
        return url;
    }

    private static String cleanTitle(String value, String fallback) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty()) title = fallback == null ? "" : fallback;
        if (title.length() > 512) title = title.substring(0, 512);
        for (int i = 0; i < title.length(); i++) {
            if (Character.isISOControl(title.charAt(i))) return fallback == null ? "" : fallback;
        }
        return title;
    }
}
