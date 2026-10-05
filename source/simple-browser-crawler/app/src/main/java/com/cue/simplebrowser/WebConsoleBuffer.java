package com.cue.simplebrowser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** User-bounded or unbounded plain-text event buffer held only in transient process memory. */
final class WebConsoleBuffer {
    private final int capacity;
    private final int maxPerSecond;
    private final ArrayDeque<WebConsoleEntry> entries = new ArrayDeque<>();
    private long rateWindowStartElapsed = Long.MIN_VALUE;
    private int acceptedInWindow;
    private long droppedByRateLimit;
    private long nextSequence = 1L;

    WebConsoleBuffer() {
        this(WebDebugPolicy.CONSOLE_RING_CAPACITY,
                WebDebugPolicy.CONSOLE_MAX_EVENTS_PER_SECOND);
    }

    WebConsoleBuffer(int capacity, int maxPerSecond) {
        if (capacity < 0 || maxPerSecond < 0) throw new IllegalArgumentException("limits must be non-negative");
        this.capacity = capacity;
        this.maxPerSecond = maxPerSecond;
    }

    synchronized boolean add(WebConsoleEntry event, long elapsedRealtimeMillis) {
        if (event == null || elapsedRealtimeMillis < 0) return false;
        if (maxPerSecond > 0) {
            if (rateWindowStartElapsed == Long.MIN_VALUE || elapsedRealtimeMillis < rateWindowStartElapsed
                    || elapsedRealtimeMillis - rateWindowStartElapsed >= 1000L) {
                rateWindowStartElapsed = elapsedRealtimeMillis;
                acceptedInWindow = 0;
            }
            if (acceptedInWindow >= maxPerSecond) {
                droppedByRateLimit++;
                return false;
            }
            acceptedInWindow++;
        }
        if (capacity > 0) while (entries.size() >= capacity) entries.removeFirst();
        entries.addLast(event.withSequence(nextSequence++));
        return true;
    }

    synchronized List<WebConsoleEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    synchronized int size() {
        return entries.size();
    }

    int capacity() {
        return capacity;
    }

    synchronized long droppedByRateLimit() {
        return droppedByRateLimit;
    }

    synchronized void clear() {
        entries.clear();
        rateWindowStartElapsed = Long.MIN_VALUE;
        acceptedInWindow = 0;
        droppedByRateLimit = 0;
        nextSequence = 1L;
    }
}
