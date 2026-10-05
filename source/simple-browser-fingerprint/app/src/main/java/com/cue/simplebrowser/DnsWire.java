package com.cue.simplebrowser;

import java.io.IOException;
import java.util.Arrays;

/** Strict, bounded DNS wire-message validation used before and after DoH. */
final class DnsWire {
    private static final int HEADER_SIZE = 12;
    private static final int MAX_MESSAGE_SIZE = 65535;

    private DnsWire() { }

    static void validateQuery(byte[] message) throws IOException {
        if (message == null || message.length < HEADER_SIZE || message.length > MAX_MESSAGE_SIZE) {
            throw new IOException("DNS query size is invalid");
        }
        int flags = u16(message, 2);
        if ((flags & 0x8000) != 0) throw new IOException("Expected a DNS query");
        if (((flags >>> 11) & 0x0f) != 0) throw new IOException("Unsupported DNS opcode");
        int questions = u16(message, 4);
        if (questions != 1) throw new IOException("Exactly one DNS question is required");
        parseSections(message);
    }

    /** Validates the complete response and matches its question to the request, then maps its ID. */
    static byte[] validateAndMapResponse(byte[] query, byte[] response) throws IOException {
        validateQuery(query);
        if (response == null || response.length < HEADER_SIZE || response.length > MAX_MESSAGE_SIZE) {
            throw new IOException("DNS response size is invalid");
        }
        int flags = u16(response, 2);
        if ((flags & 0x8000) == 0 || ((flags >>> 11) & 0x0f) != 0 || u16(response, 4) != 1) {
            throw new IOException("DNS response header is invalid");
        }
        parseSections(response);
        Question queryQuestion = question(query);
        Question responseQuestion = question(response);
        if (!queryQuestion.sameAs(responseQuestion)) throw new IOException("DNS response question does not match");
        byte[] mapped = Arrays.copyOf(response, response.length);
        mapped[0] = query[0];
        mapped[1] = query[1];
        return mapped;
    }

    static int questionEnd(byte[] message) throws IOException {
        if (message.length < HEADER_SIZE || u16(message, 4) != 1) throw new IOException("DNS question is missing");
        NameResult name = readName(message, HEADER_SIZE);
        int end = name.nextOffset + 4;
        if (end > message.length) throw new IOException("DNS question is truncated");
        return end;
    }

    static byte[] truncateForUdp(byte[] response, int maxDnsSize) throws IOException {
        if (response == null || response.length < HEADER_SIZE) throw new IOException("DNS response is truncated");
        if (response.length <= maxDnsSize) return Arrays.copyOf(response, response.length);
        int end = questionEnd(response);
        if (end > maxDnsSize) throw new IOException("DNS question exceeds the UDP packet budget");
        byte[] truncated = Arrays.copyOf(response, end);
        int flags = u16(truncated, 2) | 0x0200;
        truncated[2] = (byte) (flags >>> 8);
        truncated[3] = (byte) flags;
        truncated[6] = 0;
        truncated[7] = 0;
        truncated[8] = 0;
        truncated[9] = 0;
        truncated[10] = 0;
        truncated[11] = 0;
        return truncated;
    }

    private static Question question(byte[] message) throws IOException {
        NameResult name = readName(message, HEADER_SIZE);
        int p = name.nextOffset;
        if (p + 4 > message.length) throw new IOException("DNS question is truncated");
        return new Question(name.canonical, u16(message, p), u16(message, p + 2));
    }

    private static void parseSections(byte[] message) throws IOException {
        int qd = u16(message, 4);
        int an = u16(message, 6);
        int ns = u16(message, 8);
        int ar = u16(message, 10);
        long totalRecords = (long) qd + an + ns + ar;
        if (totalRecords > 4096) throw new IOException("DNS record count is excessive");
        int p = HEADER_SIZE;
        for (int i = 0; i < qd; i++) {
            p = readName(message, p).nextOffset;
            if (p + 4 > message.length) throw new IOException("DNS question is truncated");
            p += 4;
        }
        long rrCount = (long) an + ns + ar;
        for (long i = 0; i < rrCount; i++) {
            p = readName(message, p).nextOffset;
            if (p + 10 > message.length) throw new IOException("DNS resource record is truncated");
            int rdlength = u16(message, p + 8);
            p += 10;
            if ((long) p + rdlength > message.length) throw new IOException("DNS resource data is truncated");
            p += rdlength;
        }
        if (p != message.length) throw new IOException("DNS message has trailing or unparsed bytes");
    }

    private static NameResult readName(byte[] message, int start) throws IOException {
        if (start < 0 || start >= message.length) throw new IOException("DNS name is truncated");
        byte[] canonical = new byte[Math.min(255, message.length)];
        int canonicalLength = 0;
        int cursor = start;
        int nextOffset = -1;
        boolean jumped = false;
        boolean[] visited = new boolean[message.length];
        int hops = 0;
        while (true) {
            if (cursor >= message.length || ++hops > 128 || visited[cursor]) throw new IOException("DNS name pointer loop or truncation");
            visited[cursor] = true;
            int length = message[cursor] & 0xff;
            if ((length & 0xc0) == 0xc0) {
                if (cursor + 1 >= message.length) throw new IOException("DNS compression pointer is truncated");
                int pointer = ((length & 0x3f) << 8) | (message[cursor + 1] & 0xff);
                if (pointer >= message.length) throw new IOException("DNS compression pointer is out of range");
                if (!jumped) nextOffset = cursor + 2;
                jumped = true;
                cursor = pointer;
                continue;
            }
            if ((length & 0xc0) != 0 || length > 63) throw new IOException("DNS label encoding is invalid");
            cursor++;
            if (length == 0) {
                if (!jumped) nextOffset = cursor;
                if (canonicalLength == 0) canonical[canonicalLength++] = 0;
                return new NameResult(Arrays.copyOf(canonical, canonicalLength), nextOffset);
            }
            if (cursor + length > message.length || canonicalLength + length + 1 > 255) {
                throw new IOException("DNS label is truncated or too long");
            }
            canonical[canonicalLength++] = (byte) length;
            for (int i = 0; i < length; i++) {
                int value = message[cursor + i] & 0xff;
                canonical[canonicalLength++] = (byte) (value >= 'A' && value <= 'Z' ? value + 32 : value);
            }
            cursor += length;
            if (canonicalLength >= canonical.length) throw new IOException("DNS name exceeds buffer");
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static final class NameResult {
        final byte[] canonical;
        final int nextOffset;
        NameResult(byte[] canonical, int nextOffset) {
            this.canonical = canonical;
            this.nextOffset = nextOffset;
        }
    }

    private static final class Question {
        final byte[] name;
        final int type;
        final int clazz;
        Question(byte[] name, int type, int clazz) {
            this.name = name;
            this.type = type;
            this.clazz = clazz;
        }
        boolean sameAs(Question other) {
            return type == other.type && clazz == other.clazz && Arrays.equals(name, other.name);
        }
    }
}
