package com.cue.simplebrowser;

/** Plain-text diagnostic event; never holds page objects, URLs, wall-clock time, or payload objects. */
final class WebConsoleEntry {
    enum Kind { CONSOLE, JAVASCRIPT_ERROR, UNHANDLED_REJECTION }
    enum Level { LOG, WARN, ERROR }

    final Kind kind;
    final Level level;
    final long argumentCount;
    final String content;
    final long sequence;

    private WebConsoleEntry(Kind kind, Level level, long argumentCount, String content, long sequence,
                            int maxEntryChars) {
        this.kind = kind;
        this.level = level;
        this.argumentCount = argumentCount;
        String value = content == null ? "" : content;
        this.content = maxEntryChars > 0 && value.length() > maxEntryChars
                ? value.substring(0, maxEntryChars) : value;
        this.sequence = sequence;
    }

    static WebConsoleEntry fromPayload(String categoryValue, String levelValue, long argumentCount,
                                       String content) {
        return fromPayload(categoryValue, levelValue, argumentCount, content,
                WebDebugPolicy.CONSOLE_MAX_ARGUMENTS, WebDebugPolicy.CONSOLE_MAX_ENTRY_CHARS);
    }

    static WebConsoleEntry fromPayload(String categoryValue, String levelValue, long argumentCount,
                                       String content, int maxArguments, int maxEntryChars) {
        if (categoryValue == null || levelValue == null || argumentCount < 0
                || maxArguments < 0 || maxEntryChars < 0
                || (maxArguments > 0 && argumentCount > maxArguments)) return null;
        if (content == null || (maxEntryChars > 0 && content.length() > maxEntryChars)) return null;
        final Kind kind;
        switch (categoryValue) {
            case "console": kind = Kind.CONSOLE; break;
            case "javascript-error": kind = Kind.JAVASCRIPT_ERROR; break;
            case "unhandled-rejection": kind = Kind.UNHANDLED_REJECTION; break;
            default: return null;
        }
        final Level level;
        switch (levelValue) {
            case "log": level = Level.LOG; break;
            case "warn": level = Level.WARN; break;
            case "error": level = Level.ERROR; break;
            default: return null;
        }
        if (kind != Kind.CONSOLE && (level != Level.ERROR || argumentCount > 1)) return null;
        return new WebConsoleEntry(kind, level, argumentCount, content, 0L, maxEntryChars);
    }

    WebConsoleEntry withSequence(long assignedSequence) {
        return new WebConsoleEntry(kind, level, argumentCount, content, assignedSequence, 0);
    }

    String categoryLabel() {
        switch (kind) {
            case JAVASCRIPT_ERROR: return "JS error";
            case UNHANDLED_REJECTION: return "Unhandled rejection";
            default: return "Console";
        }
    }

    String levelLabel() {
        return level.name().toLowerCase(java.util.Locale.ROOT);
    }
}
