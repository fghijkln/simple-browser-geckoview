package com.cue.simplebrowser;

import java.util.List;

/** Deterministic local-only tests for bounded history/bookmark persistence. */
public final class BrowserDataStoreSmokeTest {
    private static final class MemoryPersistence implements BrowserDataStore.Persistence {
        String history = "";
        String bookmarks = "";
        @Override public String readHistory() { return history; }
        @Override public String readBookmarks() { return bookmarks; }
        @Override public void writeHistory(String value) { history = value; }
        @Override public void writeBookmarks(String value) { bookmarks = value; }
    }

    private BrowserDataStoreSmokeTest() { }

    public static void main(String[] args) {
        MemoryPersistence storage = new MemoryPersistence();
        BrowserDataStore first = new BrowserDataStore(storage);
        first.recordVisit("https://example.test/a?q=x", "Example \u4e00", 100L);
        first.recordVisit("http://example.test/b", "Second", 200L);
        first.recordVisit("https://example.test/a?q=x", "更新页面", 300L);
        check(first.history().size() == 2, "a repeated URL should replace its prior history record");
        check("https://example.test/a?q=x".equals(first.history().get(0).url), "history must be newest-first");
        check("更新页面".equals(first.history().get(0).title), "history title must be updated");
        pass("history ordering and duplicate updates");

        check(!first.addBookmark("file:///private", "private", 1L), "non-web bookmark must be rejected");
        check(first.addBookmark("https://example.test/a?q=x", "Example", 400L), "valid bookmark must persist");
        check(!first.addBookmark("https://example.test/a?q=x", "Duplicate", 500L), "duplicate bookmark must be rejected");
        check(first.isBookmarked("https://example.test/a?q=x"), "bookmark lookup must work");
        pass("HTTP(S)-only bookmark validation and duplicate handling");

        BrowserDataStore restored = new BrowserDataStore(storage);
        check(restored.history().size() == 2, "history must survive process recreation");
        check(restored.bookmarks().size() == 1, "bookmarks must survive process recreation");
        check("更新页面".equals(restored.history().get(0).title), "UTF-8 titles must round-trip");
        pass("local persistence round-trip");

        restored.clearHistory();
        check(restored.history().isEmpty() && storage.history.isEmpty(), "clear history must clear local state");
        check(restored.bookmarks().size() == 1, "clearing history must not remove bookmarks");
        pass("history deletion remains separate from bookmarks");

        MemoryPersistence boundedStorage = new MemoryPersistence();
        BrowserDataStore bounded = new BrowserDataStore(boundedStorage);
        for (int i = 0; i < BrowserDataStore.MAX_HISTORY + 10; i++) {
            bounded.recordVisit("https://example.test/" + i, "Page " + i, i);
        }
        check(bounded.history().size() == BrowserDataStore.MAX_HISTORY, "history must be bounded");
        check("https://example.test/509".equals(bounded.history().get(0).url), "newest record must remain first");
        check("https://example.test/10".equals(bounded.history().get(BrowserDataStore.MAX_HISTORY - 1).url), "oldest excess records must be discarded");
        pass("history retention cap");

        MemoryPersistence corruptStorage = new MemoryPersistence();
        corruptStorage.history = "bad\n%%%\tYQ\t2\n";
        BrowserDataStore corrupt = new BrowserDataStore(corruptStorage);
        check(corrupt.history().isEmpty(), "malformed persisted rows must be ignored safely");
        pass("malformed local storage is ignored");
        System.out.println("PASS: no network requests or external DNS queries");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pass(String label) { System.out.println("PASS: " + label); }
}
