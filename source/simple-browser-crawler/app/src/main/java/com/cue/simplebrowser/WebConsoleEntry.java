package com.cue.simplebrowser;

/** Metadata-only diagnostic event; never holds page text, URLs, timestamps, or payload objects. */
final class WebConsoleEntry {
    enum Kind { CONSOLE, JAVASCRIPT_ERROR, UNHANDLED_REJECTION }
    enum Level { LOG, WARN, ERROR }

    final Kind kind;
    final Level level;
    final int argumentCount;
    final long sequence;

    private WebConsoleEntry(Kind kind, Level level, int argumentCount, long sequence) {
        this.kind = kind;
        this.level = level;
        this.argumentCount = argumentCount;
        this.sequence = sequence;
    }

    static WebConsoleEntry fromPayload(String categoryValue, String levelValue, int argumentCount) {
        if (categoryValue == null || levelValue == null || argumentCount < 0
                || argumentCount > WebDebugPolicy.CONSOLE_MAX_ARGUMENTS) return null;
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
        if (kind != Kind.CONSOLE && (level != Level.ERROR || argumentCount != 0)) return null;
        return new WebConsoleEntry(kind, level, argumentCount, 0L);
    }

    WebConsoleEntry withSequence(long assignedSequence) {
        return new WebConsoleEntry(kind, level, argumentCount, assignedSequence);
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
