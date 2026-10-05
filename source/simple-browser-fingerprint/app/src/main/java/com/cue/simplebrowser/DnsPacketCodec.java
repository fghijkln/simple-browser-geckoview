package com.cue.simplebrowser;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Minimal DNS-only IPv4/IPv6 packet codec; non-DNS packets are never forwarded. */
final class DnsPacketCodec {
    static final int IPV4 = 4;
    static final int IPV6 = 6;
    static final int UDP = 17;
    static final int TCP = 6;
    static final int TCP_FIN = 0x01;
    static final int TCP_SYN = 0x02;
    static final int TCP_RST = 0x04;
    static final int TCP_PSH = 0x08;
    static final int TCP_ACK = 0x10;
    static final int MAX_TUN_MTU = 1500;
    static final int TCP_SEGMENT_SIZE = 1200;

    private DnsPacketCodec() { }

    static Packet parse(byte[] bytes, int length) throws IOException {
        if (bytes == null || length < 1 || length > bytes.length) throw new IOException("Empty IP packet");
        int version = (bytes[0] >>> 4) & 0x0f;
        int offset;
        int end;
        int protocol;
        byte[] source;
        byte[] destination;
        if (version == IPV4) {
            if (length < 20) throw new IOException("Truncated IPv4 header");
            int headerLength = (bytes[0] & 0x0f) * 4;
            int totalLength = u16(bytes, 2);
            if (headerLength < 20 || totalLength < headerLength || totalLength > length) throw new IOException("Invalid IPv4 length");
            int fragments = u16(bytes, 6);
            if ((fragments & 0x3fff) != 0) throw new IOException("Fragmented IPv4 packets are not accepted");
            protocol = bytes[9] & 0xff;
            source = Arrays.copyOfRange(bytes, 12, 16);
            destination = Arrays.copyOfRange(bytes, 16, 20);
            offset = headerLength;
            end = totalLength;
        } else if (version == IPV6) {
            if (length < 40) throw new IOException("Truncated IPv6 header");
            int payloadLength = u16(bytes, 4);
            if (payloadLength == 0 || 40 + payloadLength > length) throw new IOException("Invalid IPv6 payload length");
            source = Arrays.copyOfRange(bytes, 8, 24);
            destination = Arrays.copyOfRange(bytes, 24, 40);
            end = 40 + payloadLength;
            protocol = bytes[6] & 0xff;
            offset = 40;
            int extensionCount = 0;
            while (protocol == 0 || protocol == 43 || protocol == 60 || protocol == 51 || protocol == 44) {
                if (++extensionCount > 8) throw new IOException("Too many IPv6 extension headers");
                if (protocol == 44) throw new IOException("Fragmented IPv6 packets are not accepted");
                if (offset + 2 > end) throw new IOException("Truncated IPv6 extension header");
                int next = bytes[offset] & 0xff;
                int extLength = protocol == 51 ? ((bytes[offset + 1] & 0xff) + 2) * 4
                        : ((bytes[offset + 1] & 0xff) + 1) * 8;
                if (extLength < 8 || offset + extLength > end) throw new IOException("Invalid IPv6 extension length");
                protocol = next;
                offset += extLength;
            }
        } else {
            throw new IOException("Unsupported IP version");
        }
        if (protocol != UDP && protocol != TCP) throw new IOException("Unsupported IP protocol");
        if (offset + (protocol == UDP ? 8 : 20) > end) throw new IOException("Truncated transport header");
        int sourcePort = u16(bytes, offset);
        int destinationPort = u16(bytes, offset + 2);
        if (protocol == UDP) {
            int udpLength = u16(bytes, offset + 4);
            if (udpLength < 8 || offset + udpLength > end) throw new IOException("Invalid UDP length");
            return new Packet(version, UDP, source, destination, sourcePort, destinationPort,
                    Arrays.copyOfRange(bytes, offset + 8, offset + udpLength), 0, 0, 0, 0);
        }
        int tcpHeaderLength = ((bytes[offset + 12] >>> 4) & 0x0f) * 4;
        if (tcpHeaderLength < 20 || offset + tcpHeaderLength > end) throw new IOException("Invalid TCP header length");
        int sequence = i32(bytes, offset + 4);
        int acknowledgement = i32(bytes, offset + 8);
        int flags = bytes[offset + 13] & 0xff;
        int tcpMss = 0;
        for (int option = offset + 20; option < offset + tcpHeaderLength;) {
            int kind = bytes[option] & 0xff;
            if (kind == 0) break;
            if (kind == 1) { option++; continue; }
            if (option + 2 > offset + tcpHeaderLength) throw new IOException("Truncated TCP option");
            int optionLength = bytes[option + 1] & 0xff;
            if (optionLength < 2 || option + optionLength > offset + tcpHeaderLength) {
                throw new IOException("Invalid TCP option length");
            }
            if (kind == 2) {
                if (optionLength != 4) throw new IOException("Invalid TCP MSS option");
                tcpMss = u16(bytes, option + 2);
            }
            option += optionLength;
        }
        return new Packet(version, TCP, source, destination, sourcePort, destinationPort,
                Arrays.copyOfRange(bytes, offset + tcpHeaderLength, end), sequence, acknowledgement, flags, tcpMss);
    }

    static byte[] buildUdpResponse(Packet query, byte[] dnsMessage) throws IOException {
        if (query.protocol != UDP || query.destinationPort != 53) throw new IOException("Not a DNS UDP request");
        int ipHeaderLength = query.ipVersion == IPV4 ? 20 : 40;
        byte[] dns = DnsWire.truncateForUdp(dnsMessage, MAX_TUN_MTU - ipHeaderLength - 8);
        int udpLength = 8 + dns.length;
        byte[] packet = new byte[ipHeaderLength + udpLength];
        if (query.ipVersion == IPV4) {
            packet[0] = 0x45;
            put16(packet, 2, packet.length);
            put16(packet, 4, 0);
            put16(packet, 6, 0x4000);
            packet[8] = 64;
            packet[9] = (byte) UDP;
            System.arraycopy(query.destination, 0, packet, 12, 4);
            System.arraycopy(query.source, 0, packet, 16, 4);
            put16(packet, 10, checksum(packet, 0, 20));
        } else {
            packet[0] = 0x60;
            put16(packet, 4, udpLength);
            packet[6] = (byte) UDP;
            packet[7] = 64;
            System.arraycopy(query.destination, 0, packet, 8, 16);
            System.arraycopy(query.source, 0, packet, 24, 16);
        }
        int udpOffset = ipHeaderLength;
        put16(packet, udpOffset, query.destinationPort);
        put16(packet, udpOffset + 2, query.sourcePort);
        put16(packet, udpOffset + 4, udpLength);
        System.arraycopy(dns, 0, packet, udpOffset + 8, dns.length);
        int sum = transportChecksum(packet, query.ipVersion, query.destination, query.source,
                udpOffset, udpLength, UDP);
        put16(packet, udpOffset + 6, sum == 0 ? 0xffff : sum);
        return packet;
    }

    static byte[] buildTcpResponse(Packet incoming, int sequence, int acknowledgement, int flags,
                                   byte[] payload) throws IOException {
        if (incoming.protocol != TCP) throw new IOException("Not a TCP packet");
        byte[] data = payload == null ? new byte[0] : payload;
        int ipHeaderLength = incoming.ipVersion == IPV4 ? 20 : 40;
        int tcpOffset = ipHeaderLength;
        int tcpLength = 20 + data.length;
        byte[] packet = new byte[ipHeaderLength + tcpLength];
        if (incoming.ipVersion == IPV4) {
            packet[0] = 0x45;
            put16(packet, 2, packet.length);
            put16(packet, 4, 0);
            put16(packet, 6, 0x4000);
            packet[8] = 64;
            packet[9] = (byte) TCP;
            System.arraycopy(incoming.destination, 0, packet, 12, 4);
            System.arraycopy(incoming.source, 0, packet, 16, 4);
            put16(packet, 10, checksum(packet, 0, 20));
        } else {
            packet[0] = 0x60;
            put16(packet, 4, tcpLength);
            packet[6] = (byte) TCP;
            packet[7] = 64;
            System.arraycopy(incoming.destination, 0, packet, 8, 16);
            System.arraycopy(incoming.source, 0, packet, 24, 16);
        }
        put16(packet, tcpOffset, incoming.destinationPort);
        put16(packet, tcpOffset + 2, incoming.sourcePort);
        put32(packet, tcpOffset + 4, sequence);
        put32(packet, tcpOffset + 8, acknowledgement);
        packet[tcpOffset + 12] = 0x50;
        packet[tcpOffset + 13] = (byte) flags;
        put16(packet, tcpOffset + 14, 65535);
        System.arraycopy(data, 0, packet, tcpOffset + 20, data.length);
        put16(packet, tcpOffset + 16,
                transportChecksum(packet, incoming.ipVersion, incoming.destination, incoming.source,
                        tcpOffset, tcpLength, TCP));
        return packet;
    }

    static InetAddress address(byte[] bytes) throws UnknownHostException {
        return InetAddress.getByAddress(bytes);
    }

    static final class Packet {
        final int ipVersion;
        final int protocol;
        final byte[] source;
        final byte[] destination;
        final int sourcePort;
        final int destinationPort;
        final byte[] payload;
        final int sequence;
        final int acknowledgement;
        final int tcpFlags;
        final int tcpMss;
        Packet(int ipVersion, int protocol, byte[] source, byte[] destination, int sourcePort,
               int destinationPort, byte[] payload, int sequence, int acknowledgement, int tcpFlags, int tcpMss) {
            this.ipVersion = ipVersion;
            this.protocol = protocol;
            this.source = source;
            this.destination = destination;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.payload = payload;
            this.sequence = sequence;
            this.acknowledgement = acknowledgement;
            this.tcpFlags = tcpFlags;
            this.tcpMss = tcpMss;
        }
        boolean isDns() { return destinationPort == 53; }
        String flowKey() {
            return ipVersion + ":" + hex(source) + ":" + sourcePort + ":" + hex(destination) + ":" + destinationPort;
        }
        private static String hex(byte[] data) {
            StringBuilder out = new StringBuilder(data.length * 2);
            for (byte value : data) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        }
    }

    private static int transportChecksum(byte[] packet, int version, byte[] source, byte[] destination,
                                        int offset, int length, int protocol) {
        long sum = 0;
        if (version == IPV4) {
            sum = addWords(sum, source, 0, source.length);
            sum = addWords(sum, destination, 0, destination.length);
            sum += protocol;
            sum += length;
        } else {
            sum = addWords(sum, source, 0, source.length);
            sum = addWords(sum, destination, 0, destination.length);
            sum += (length >>> 16) & 0xffff;
            sum += length & 0xffff;
            sum += protocol;
        }
        sum = addWords(sum, packet, offset, length);
        return finishChecksum(sum);
    }

    private static int checksum(byte[] packet, int offset, int length) {
        return finishChecksum(addWords(0, packet, offset, length));
    }

    private static long addWords(long sum, byte[] data, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i + 1 < end; i += 2) sum += ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
        if ((length & 1) != 0) sum += (data[end - 1] & 0xff) << 8;
        return sum;
    }

    private static int finishChecksum(long sum) {
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }

    private static int u16(byte[] data, int at) { return ((data[at] & 0xff) << 8) | (data[at + 1] & 0xff); }
    private static int i32(byte[] data, int at) { return (data[at] << 24) | ((data[at + 1] & 0xff) << 16) | ((data[at + 2] & 0xff) << 8) | (data[at + 3] & 0xff); }
    private static void put16(byte[] data, int at, int value) { data[at] = (byte) (value >>> 8); data[at + 1] = (byte) value; }
    private static void put32(byte[] data, int at, int value) { data[at] = (byte) (value >>> 24); data[at + 1] = (byte) (value >>> 16); data[at + 2] = (byte) (value >>> 8); data[at + 3] = (byte) value; }
}
