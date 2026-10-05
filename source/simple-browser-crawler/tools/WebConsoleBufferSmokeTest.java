package com.cue.simplebrowser;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline tests for configurable or unlimited raw-text diagnostics and memory-only behavior. */
public final class WebConsoleBufferSmokeTest {
    public static void main(String[] args) throws Exception {
        check(args.length == 2, "expected MainActivity.java and GeckoViewBrowserAdapter.java");
        check(WebDebugPolicy.IN_APP_CONSOLE_DEFAULT, "in-app Console must default on");
        check(WebDebugPolicy.inAppConsoleEnabled(false, false), "missing preference uses relaxed default");
        check(!WebDebugPolicy.inAppConsoleEnabled(true, false), "explicit opt-out remains off");
        check(WebDebugPolicy.REMOTE_DEBUGGING_DEFAULT, "remote debugging defaults on");
        check(WebDebugPolicy.CONSOLE_RING_CAPACITY == 5_000, "default buffer is 5,000 rows");
        check(Arrays.equals(WebDebugPolicy.consoleCapacityOptions(), new int[] {500, 1_000, 2_500, 5_000, 0}),
                "row cap includes explicit Unlimited option");
        check(WebDebugPolicy.normalizeConsoleCapacity(0) == 0
                        && WebDebugPolicy.normalizeConsoleCapacity(4_000) == 5_000,
                "zero remains Unlimited while unsupported values return to finite default");
        check(Arrays.equals(WebDebugPolicy.consoleEntryLimitOptions(), new int[] {16_384, 65_536, 0}),
                "per-entry size includes explicit Unlimited option");
        check(Arrays.equals(WebDebugPolicy.consoleRateLimitOptions(), new int[] {15, 60, 120, 0}),
                "event rate includes explicit Unlimited option");
        check(WebDebugPolicy.normalizeConsoleEntryLimit(0) == 0
                        && WebDebugPolicy.normalizeConsoleRateLimit(0) == 0
                        && WebDebugPolicy.consoleMaxArguments(0) == 0
                        && WebDebugPolicy.consoleMaxArgumentChars(0) == 0,
                "Unlimited removes native, argument-count, and per-argument application caps");

        Set<String> fieldNames = new HashSet<>();
        for (Field field : WebConsoleEntry.class.getDeclaredFields()) {
            fieldNames.add(field.getName());
            check(field.getType() != java.util.Date.class, "entry must not retain wall-clock timestamps");
            if (field.getType() == String.class) {
                check(field.getName().equals("content"), "only diagnostic content may be retained as text");
            }
        }
        check(fieldNames.equals(Set.of("kind", "level", "argumentCount", "content", "sequence")),
                "entry may retain only typed category/level, count, content, and ordinal: " + fieldNames);
        check(WebConsoleEntry.class.getDeclaredField("argumentCount").getType() == long.class,
                "native argument count must not impose a signed 32-bit cap");

        WebConsoleEntry log = WebConsoleEntry.fromPayload("console", "log", 3, "token=private-value");
        WebConsoleEntry warn = WebConsoleEntry.fromPayload("console", "warn", 1, "warning text");
        WebConsoleEntry error = WebConsoleEntry.fromPayload("console", "error", 2,
                "error: private\n at https://site.invalid/a.js:91");
        WebConsoleEntry jsError = WebConsoleEntry.fromPayload("javascript-error", "error", 1,
                "TypeError: private\n at /app.js:19");
        WebConsoleEntry rejection = WebConsoleEntry.fromPayload("unhandled-rejection", "error", 1,
                "rejected: private token");
        check(log != null && log.argumentCount == 3 && log.content.contains("token=private-value"),
                "raw scalar console text is retained for diagnostics");
        check(warn != null && warn.level == WebConsoleEntry.Level.WARN, "warning text is retained");
        check(error != null && error.content.contains("/a.js:91"), "error text and stack/path are retained");
        check(jsError != null && jsError.content.contains("TypeError"), "uncaught error text is retained");
        check(rejection != null && rejection.content.contains("private token"), "rejection reason is retained");
        check(WebConsoleEntry.fromPayload("console", "debug", 0, "x") == null,
                "unsupported console level rejected");
        check(WebConsoleEntry.fromPayload("javascript-error", "log", 0, "x") == null,
                "error cannot masquerade as console log");
        check(WebConsoleEntry.fromPayload("dom", "error", 0, "x") == null,
                "non-allowlisted category rejected");
        check(WebConsoleEntry.fromPayload("console", "log", -1, "x") == null,
                "negative argument count rejected");
        check(WebDebugPolicy.consoleMaxArguments(WebDebugPolicy.CONSOLE_MAX_ENTRY_CHARS) == 0
                        && WebConsoleEntry.fromPayload("console", "log", 65, "x") != null,
                "finite text caps do not impose an undisclosed fixed argument-count limit");
        check(WebConsoleEntry.fromPayload("console", "log", 65, "x", 0, 0) != null,
                "Unlimited accepts more than 64 arguments");
        check(WebConsoleEntry.fromPayload("console", "log", 1,
                        "x".repeat(WebDebugPolicy.CONSOLE_MAX_ENTRY_CHARS + 1)) == null,
                "default finite per-entry limit is enforced");
        String large = "x".repeat(WebDebugPolicy.CONSOLE_MAX_ENTRY_CHARS + 1);
        WebConsoleEntry unlimitedEntry = WebConsoleEntry.fromPayload("console", "log", 1, large, 0, 0);
        check(unlimitedEntry != null && unlimitedEntry.content.length() == large.length(),
                "Unlimited entry setting has no hidden application string cap");
        check(WebConsoleEntry.fromPayload("console", "log", Long.MAX_VALUE, "x", 0, 0) != null,
                "native count accepts values beyond signed 32-bit range");

        WebConsoleBuffer buffer = new WebConsoleBuffer();
        check(buffer.capacity() == WebDebugPolicy.CONSOLE_RING_CAPACITY,
                "default finite buffer is 5,000 rows");
        for (int i = 0; i <= WebDebugPolicy.CONSOLE_RING_CAPACITY; i++) {
            check(buffer.add(WebConsoleEntry.fromPayload("console", "log", i % 5, "entry-" + i), i * 1000L),
                    "one event per second should pass the default rate limit");
        }
        List<WebConsoleEntry> snapshot = buffer.snapshot();
        check(snapshot.size() == 5_000 && snapshot.get(0).sequence == 2L
                        && snapshot.get(snapshot.size() - 1).sequence == 5_001L,
                "finite ring retains the newest 5,000 entries");
        buffer.clear();
        check(buffer.size() == 0 && buffer.snapshot().isEmpty() && buffer.droppedByRateLimit() == 0,
                "clear removes entries and counters");
        check(buffer.add(log, 600_000L) && buffer.snapshot().get(0).sequence == 1L,
                "clear resets local ordinal; no absolute timestamp is stored");

        WebConsoleBuffer limited = new WebConsoleBuffer(10, 2);
        check(limited.add(log, 1000L) && limited.add(warn, 1001L), "first two events pass");
        check(!limited.add(error, 1002L) && limited.droppedByRateLimit() == 1,
                "finite per-second intake limit rejects excess events");
        check(limited.add(error, 2000L), "finite rate resets in next elapsed-time window");

        WebConsoleBuffer unlimited = new WebConsoleBuffer(0, 0);
        for (int i = 0; i < 5_005; i++) {
            check(unlimited.add(WebConsoleEntry.fromPayload("console", "log", 1, "unlimited-" + i, 0, 0),
                    10_000L), "Unlimited row/rate setting accepts events in the same time window");
        }
        check(unlimited.capacity() == 0 && unlimited.size() == 5_005
                        && unlimited.droppedByRateLimit() == 0,
                "0 row and event-rate limits mean no application-level cap");
        unlimited.clear();
        check(unlimited.size() == 0, "unlimited buffer can still be cleared explicitly");

        String main = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String adapter = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        check(main.contains("entry.sequence") && main.contains("entry.categoryLabel()")
                        && main.contains("entry.levelLabel()") && main.contains("entry.argumentCount")
                        && main.contains("entry.content") && main.contains("webConsoleBuffer.capacity()")
                        && main.contains("showConsoleBufferLimitDialog")
                        && main.contains("showConsoleEntryLimitDialog")
                        && main.contains("showConsoleRateLimitDialog")
                        && main.contains("showConsoleUnlimitedConfirmation")
                        && !main.contains("entry.receivedAtMillis"),
                "UI displays raw diagnostics, all selectable/unlimited controls, and no page URL/time");
        check(adapter.contains("payload.opt(\"content\")") && adapter.contains("payload.opt(\"category\")")
                        && adapter.contains("payload.opt(\"level\")")
                        && adapter.contains("payload.opt(\"argumentCount\")")
                        && adapter.contains("currentConsoleEntryLimit()")
                        && !adapter.contains("payload.optString(\"url\"")
                        && !adapter.contains("payload.opt(\"timestamp\")"),
                "native parser follows current user limits and reads no URL/time fields");
        String bufferSource = Files.readString(Path.of("app/src/main/java/com/cue/simplebrowser/WebConsoleBuffer.java"),
                StandardCharsets.UTF_8);
        check(!bufferSource.contains("SharedPreferences") && !bufferSource.contains("FileOutputStream")
                        && !bufferSource.contains("Database") && !bufferSource.contains("upload"),
                "event buffer is memory-only");
        System.out.println("Web console policy smoke test: PASS (finite and unlimited row/entry/rate modes, errors/stacks, clear, memory-only)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
