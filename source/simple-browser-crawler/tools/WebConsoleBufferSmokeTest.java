package com.cue.simplebrowser;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline tests for metadata-only console records, bounded memory, and clear semantics. */
public final class WebConsoleBufferSmokeTest {
    public static void main(String[] args) throws Exception {
        check(args.length == 2, "expected MainActivity.java and GeckoViewBrowserAdapter.java");
        check(!WebDebugPolicy.IN_APP_CONSOLE_DEFAULT, "in-app capture must default off");
        check(!WebDebugPolicy.inAppConsoleEnabled(false, true), "missing preference is not consent");
        check(!WebDebugPolicy.inAppConsoleEnabled(true, false), "explicit opt-out remains off");
        check(WebDebugPolicy.inAppConsoleEnabled(true, true), "only explicit opt-in grants use");
        check(!WebDebugPolicy.REMOTE_DEBUGGING_DEFAULT, "remote debugging must remain off by default");

        Set<String> fieldNames = new HashSet<>();
        for (Field field : WebConsoleEntry.class.getDeclaredFields()) {
            fieldNames.add(field.getName());
            check(field.getType() != String.class, "entry must not retain arbitrary text");
            check(field.getType() != java.util.Date.class, "entry must not retain wall-clock timestamps");
        }
        check(fieldNames.equals(Set.of("kind", "level", "argumentCount", "sequence")),
                "entry may retain only fixed enums, argument count, and local sequence: " + fieldNames);

        WebConsoleEntry log = WebConsoleEntry.fromPayload("console", "log", 3);
        WebConsoleEntry warn = WebConsoleEntry.fromPayload("console", "warn", 1);
        WebConsoleEntry error = WebConsoleEntry.fromPayload("console", "error", 0);
        WebConsoleEntry jsError = WebConsoleEntry.fromPayload("javascript-error", "error", 0);
        WebConsoleEntry rejection = WebConsoleEntry.fromPayload("unhandled-rejection", "error", 0);
        check(log != null && log.kind == WebConsoleEntry.Kind.CONSOLE
                        && log.level == WebConsoleEntry.Level.LOG && log.argumentCount == 3,
                "console level/category/count must be accepted without a message field");
        check(warn != null && warn.level == WebConsoleEntry.Level.WARN && warn.argumentCount == 1,
                "warn metadata accepted");
        check(error != null && error.level == WebConsoleEntry.Level.ERROR,
                "error metadata accepted");
        check(jsError != null && jsError.kind == WebConsoleEntry.Kind.JAVASCRIPT_ERROR,
                "uncaught error category accepted without its text");
        check(rejection != null && rejection.kind == WebConsoleEntry.Kind.UNHANDLED_REJECTION,
                "rejection category accepted without its reason");
        check(WebConsoleEntry.fromPayload("console", "debug", 0) == null,
                "unsupported console level rejected");
        check(WebConsoleEntry.fromPayload("javascript-error", "log", 0) == null,
                "error cannot masquerade as console log");
        check(WebConsoleEntry.fromPayload("dom", "error", 0) == null,
                "non-allowlisted category rejected");
        check(WebConsoleEntry.fromPayload("console", "log", -1) == null,
                "negative argument count rejected");
        check(WebConsoleEntry.fromPayload("console", "log", WebDebugPolicy.CONSOLE_MAX_ARGUMENTS + 1) == null,
                "oversized argument count rejected");
        check(jsError.argumentCount == 0 && rejection.argumentCount == 0,
                "error records carry category only, not page-provided values");

        WebConsoleBuffer buffer = new WebConsoleBuffer();
        for (int i = 0; i <= WebDebugPolicy.CONSOLE_RING_CAPACITY; i++) {
            check(buffer.add(WebConsoleEntry.fromPayload("console", "log", i % 5), i * 1000L),
                    "one event per second should pass rate limiting");
        }
        List<WebConsoleEntry> snapshot = buffer.snapshot();
        check(snapshot.size() == 500 && snapshot.get(0).sequence == 2L
                        && snapshot.get(snapshot.size() - 1).sequence == 501L,
                "ring retains only the newest 500 metadata records with local ordering");
        buffer.clear();
        check(buffer.size() == 0 && buffer.snapshot().isEmpty() && buffer.droppedByRateLimit() == 0,
                "clear must remove all retained entries and counters");
        check(buffer.add(log, 600_000L) && buffer.snapshot().get(0).sequence == 1L,
                "clear resets the local ordinal; no absolute timestamp is stored");

        WebConsoleBuffer limited = new WebConsoleBuffer(10, 2);
        check(limited.add(log, 1000L) && limited.add(warn, 1001L), "first two events pass");
        check(!limited.add(error, 1002L) && limited.droppedByRateLimit() == 1,
                "per-second intake limit rejects excess events");
        check(limited.add(error, 2000L), "rate limit resets in next elapsed-time window");
        limited.clear();
        check(limited.droppedByRateLimit() == 0 && limited.size() == 0,
                "clear also resets rate-limiter state");

        String main = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String adapter = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        check(main.contains("entry.sequence") && main.contains("entry.categoryLabel()")
                        && main.contains("entry.levelLabel()") && main.contains("entry.argumentCount")
                        && !main.contains("entry.message") && !main.contains("entry.receivedAtMillis"),
                "UI rows may render only sequence/category/level/argument count");
        check(adapter.contains("payload.opt(\"category\")")
                        && adapter.contains("payload.opt(\"level\")")
                        && adapter.contains("payload.opt(\"argumentCount\")")
                        && !adapter.contains("payload.optString(\"message\"")
                        && !adapter.contains("payload.opt(\"stack\")"),
                "native parser reads only typed metadata, not message/stack");
        String bufferSource = Files.readString(Path.of("app/src/main/java/com/cue/simplebrowser/WebConsoleBuffer.java"),
                StandardCharsets.UTF_8);
        check(!bufferSource.contains("SharedPreferences") && !bufferSource.contains("FileOutputStream")
                        && !bufferSource.contains("Database") && !bufferSource.contains("upload"),
                "event buffer is in-memory only");

        System.out.println("Web console metadata smoke test: PASS (no text/URL/wall-time fields, 500-event cap, elapsed rate limit, clear, UI/native allowlist)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
