package com.cue.simplebrowser;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** No public resolver is contacted; all HTTPS traffic binds to local loopback. */
public final class DnsLocalMockTest {
    private enum Mode { OK, CHUNKED, BAD_QUESTION, MALFORMED, HTTP_ERROR, BAD_CONTENT_TYPE, DELAY }
    private static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.OK);
    private static final AtomicInteger requests = new AtomicInteger();
    private static volatile byte[] capturedBody;
    private static volatile String capturedMethod;
    private static volatile String capturedContentType;
    private static volatile String capturedAccept;
    private static volatile boolean headerFailure;

    private DnsLocalMockTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: DnsLocalMockTest <test-keystore.p12>");
        Path keyPath = Path.of(args[0]);
        SSLContext serverTls = tlsContext(keyPath, "changeit".toCharArray(), true);
        SSLContext clientTls = tlsContext(keyPath, "changeit".toCharArray(), false);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "local-mock-doh");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/dns-query", exchange -> {
            requests.incrementAndGet();
            capturedMethod = exchange.getRequestMethod();
            capturedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            capturedAccept = exchange.getRequestHeaders().getFirst("Accept");
            capturedBody = readAll(exchange.getRequestBody(), 65535);
            headerFailure = !"POST".equals(capturedMethod)
                    || capturedContentType == null || !capturedContentType.toLowerCase(Locale.ROOT).startsWith("application/dns-message")
                    || capturedAccept == null || !capturedAccept.toLowerCase(Locale.ROOT).startsWith("application/dns-message");
            Mode mode = MODE.get();
            if (mode == Mode.DELAY) {
                try { Thread.sleep(900L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            byte[] body = capturedBody == null ? new byte[0] : capturedBody;
            byte[] response = makeResponse(body, mode == Mode.BAD_QUESTION);
            int status = mode == Mode.HTTP_ERROR ? 503 : 200;
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", mode == Mode.BAD_CONTENT_TYPE
                    ? "application/dns-message-fake" : "application/dns-message");
            if (mode == Mode.CHUNKED) {
                exchange.sendResponseHeaders(status, 0);
            } else {
                if (mode == Mode.MALFORMED && response.length > 2) response = Arrays.copyOf(response, response.length - 2);
                exchange.sendResponseHeaders(status, response.length);
            }
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
            catch (IOException ignored) { /* expected after client timeout */ }
        });
        server.start();
        int port = server.getAddress().getPort();
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        AtomicInteger protectCalls = new AtomicInteger();
        AtomicInteger plaintextFallbacks = new AtomicInteger();
        DohClient.SocketProtector protector = socket -> {
            protectCalls.incrementAndGet();
            check(!socket.isConnected(), "protect must run before connect");
            return true;
        };
        try {
            byte[] query = makeQuery(0x1234, "www.example.test");
            DohClient okClient = client(port, loopback, (SSLSocketFactory) clientTls.getSocketFactory(), protector, 1000, 1000);
            byte[] mapped = okClient.exchange(query);
            check((mapped[0] & 0xff) == 0x12 && (mapped[1] & 0xff) == 0x34, "response ID must map to the original query ID");
            check((mapped[2] & 0x80) != 0, "response QR bit must be set");
            check((mapped[7] & 0xff) == 2, "multiple answers must be retained");
            check((mapped[11] & 0xff) == 1, "EDNS additional record must be retained");
            check(!headerFailure, "RFC 8484 POST headers must be present");
            check(Arrays.equals(query, capturedBody), "POST body must be the exact binary DNS query including EDNS");
            pass("binary RFC 8484 POST, headers, ID remap, multiple records and EDNS");

            MODE.set(Mode.CHUNKED);
            byte[] chunked = okClient.exchange(query);
            check(chunked.length > 30, "chunked DoH response must be decoded");
            pass("chunked HTTP response");

            MODE.set(Mode.BAD_QUESTION);
            expectFailure(() -> okClient.exchange(query), "wrong DNS question must fail closed");
            pass("mismatched response question fail-closed");

            MODE.set(Mode.MALFORMED);
            expectFailure(() -> okClient.exchange(query), "truncated DNS response must fail closed");
            pass("malformed/truncated response fail-closed");

            MODE.set(Mode.HTTP_ERROR);
            expectFailure(() -> okClient.exchange(query), "non-2xx HTTP response must fail closed");
            pass("HTTP error fail-closed");

            MODE.set(Mode.BAD_CONTENT_TYPE);
            expectFailure(() -> okClient.exchange(query), "wrong DoH media type must fail closed");
            pass("deceptive DoH content-type prefix fail-closed");

            MODE.set(Mode.DELAY);
            DohClient timeoutClient = client(port, loopback, (SSLSocketFactory) clientTls.getSocketFactory(), protector, 300, 250);
            expectFailure(() -> timeoutClient.exchange(query), "DoH timeout must fail closed");
            pass("DoH timeout fail-closed");
            Thread.sleep(950L);

            MODE.set(Mode.OK);
            DohClient badCertificateClient = client(port, loopback,
                    (SSLSocketFactory) SSLSocketFactory.getDefault(), protector, 1000, 1000);
            int beforeBadCert = requests.get();
            expectFailure(() -> badCertificateClient.exchange(query), "untrusted certificate must fail closed");
            check(requests.get() == beforeBadCert, "invalid certificate must be rejected before an HTTP/DNS request is sent");
            pass("bad certificate and hostname-verification path fail-closed");

            DohClient badHostnameClient = new DohClient("wrong-host.local", "/dns-query", port, loopback,
                    (SSLSocketFactory) clientTls.getSocketFactory(), protector, 1000, 1000);
            int beforeBadHostname = requests.get();
            expectFailure(() -> badHostnameClient.exchange(query), "hostname mismatch must fail closed");
            check(requests.get() == beforeBadHostname, "hostname mismatch must be rejected before an HTTP/DNS request is sent");
            pass("trusted certificate with the wrong hostname fail-closed");

            byte[] malformedQuery = Arrays.copyOf(query, query.length - 1);
            int protectionsBeforeMalformed = protectCalls.get();
            int requestsBeforeMalformed = requests.get();
            expectFailure(() -> okClient.exchange(malformedQuery), "malformed query must fail before network");
            check(protectCalls.get() == protectionsBeforeMalformed, "malformed query must not open a socket");
            check(requests.get() == requestsBeforeMalformed, "malformed query must not reach the mock server");
            pass("malformed request rejected before socket creation");

            MODE.set(Mode.OK);
            testPacketFamiliesAndRoutes(query, okClient);
            check(plaintextFallbacks.get() == 0, "cleartext DNS fallback counter must remain zero");
            check(requests.get() > 0, "the mock HTTPS endpoint should have received test traffic");
            System.out.println("PASS: plaintext DNS fallback attempts = " + plaintextFallbacks.get());
            System.out.println("PASS: external/Quad9 queries = 0 (all mock traffic used loopback)");
            System.out.println("PASS: total mock HTTPS requests = " + requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static DohClient client(int port, InetAddress loopback, SSLSocketFactory factory,
                                    DohClient.SocketProtector protector, int connectTimeout, int readTimeout) {
        return new DohClient("localhost", "/dns-query", port, loopback, factory,
                protector, connectTimeout, readTimeout);
    }

    private static SSLContext tlsContext(Path keyPath, char[] password, boolean server) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyPath)) { store.load(input, password); }
        SSLContext context = SSLContext.getInstance("TLS");
        if (server) {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, password);
            context.init(kmf.getKeyManagers(), null, null);
        } else {
            KeyStore trustedCertificates = KeyStore.getInstance(KeyStore.getDefaultType());
            trustedCertificates.load(null, null);
            trustedCertificates.setCertificateEntry("local-mock-doh", store.getCertificate("mockdoh"));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustedCertificates);
            context.init(null, tmf.getTrustManagers(), null);
        }
        return context;
    }

    private static byte[] makeQuery(int id, String domain) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.write((id >>> 8) & 0xff); message.write(id & 0xff);
        message.write(0x01); message.write(0x00); // RD
        message.write(0); message.write(1); // QDCOUNT
        message.write(0); message.write(0); // ANCOUNT
        message.write(0); message.write(0); // NSCOUNT
        message.write(0); message.write(1); // ARCOUNT
        writeName(message, domain);
        write16(message, 1); write16(message, 1); // A / IN
        message.write(0); write16(message, 41); write16(message, 1232);
        message.write(0); message.write(0); message.write(0); message.write(0);
        write16(message, 6); // option length
        write16(message, 65001); write16(message, 2); message.write(0x12); message.write(0x34);
        return message.toByteArray();
    }

    private static byte[] makeResponse(byte[] query, boolean wrongQuestion) throws IOException {
        DnsWire.validateQuery(query);
        int questionEnd = DnsWire.questionEnd(query);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xbe); out.write(0xef); // deliberately different ID; client maps it back
        out.write(0x81); out.write(0x80); // QR, RD, RA
        out.write(0); out.write(1); // QDCOUNT
        out.write(0); out.write(2); // ANCOUNT
        out.write(0); out.write(0); // NSCOUNT
        out.write(0); out.write(1); // ARCOUNT (OPT)
        if (wrongQuestion) writeName(out, "other.invalid");
        else out.write(query, 12, questionEnd - 12 - 4);
        if (wrongQuestion) { write16(out, 1); write16(out, 1); }
        else out.write(query, questionEnd - 4, 4);
        out.write(0xc0); out.write(0x0c); // compressed owner pointer to the question
        write16(out, 1); write16(out, 1); write32(out, 60); write16(out, 4);
        out.write(192); out.write(0); out.write(2); out.write(1);
        out.write(0xc0); out.write(0x0c);
        write16(out, 28); write16(out, 1); write32(out, 60); write16(out, 16);
        byte[] address = new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        out.write(address);
        out.write(0); write16(out, 41); write16(out, 1232); write32(out, 0); write16(out, 0);
        return out.toByteArray();
    }

    private static void testPacketFamiliesAndRoutes(byte[] query, DohClient dohClient) throws Exception {
        byte[] ipv4 = makeUdpPacket(false, query);
        DnsPacketCodec.Packet parsed4 = DnsPacketCodec.parse(ipv4, ipv4.length);
        check(parsed4.ipVersion == DnsPacketCodec.IPV4 && parsed4.protocol == DnsPacketCodec.UDP,
                "IPv4 UDP DNS packet must parse");
        byte[] reply4 = DnsPacketCodec.buildUdpResponse(parsed4, makeResponse(query, false));
        DnsPacketCodec.Packet parsedReply4 = DnsPacketCodec.parse(reply4, reply4.length);
        check(parsedReply4.sourcePort == 53 && parsedReply4.destinationPort == 53000, "IPv4 UDP reply ports must reverse");
        check(validIpv4HeaderChecksum(reply4), "IPv4 reply header checksum must validate");
        check(validTransportChecksum(reply4, DnsPacketCodec.IPV4, DnsPacketCodec.UDP), "IPv4 UDP pseudoheader checksum must validate");
        pass("IPv4 DNS packet and checksummed UDP reply");

        byte[] ipv6 = makeUdpPacket(true, query);
        DnsPacketCodec.Packet parsed6 = DnsPacketCodec.parse(ipv6, ipv6.length);
        check(parsed6.ipVersion == DnsPacketCodec.IPV6 && parsed6.protocol == DnsPacketCodec.UDP,
                "IPv6 UDP DNS packet must parse");
        byte[] reply6 = DnsPacketCodec.buildUdpResponse(parsed6, makeResponse(query, false));
        DnsPacketCodec.Packet parsedReply6 = DnsPacketCodec.parse(reply6, reply6.length);
        check(parsedReply6.sourcePort == 53 && parsedReply6.destinationPort == 53000, "IPv6 UDP reply ports must reverse");
        check(validTransportChecksum(reply6, DnsPacketCodec.IPV6, DnsPacketCodec.UDP), "IPv6 UDP checksum must validate");
        pass("IPv6 DNS packet, EDNS and checksummed UDP reply");

        byte[] oversized = makeLargeResponse(query, 100);
        check(oversized.length > DnsPacketCodec.MAX_TUN_MTU, "oversized fixture must exceed the TUN MTU");
        byte[] truncated = DnsWire.truncateForUdp(oversized, DnsPacketCodec.MAX_TUN_MTU - 48);
        check(truncated.length <= DnsPacketCodec.MAX_TUN_MTU - 48, "UDP response must fit IPv6 MTU budget");
        check((truncated[2] & 0x02) != 0 && truncated[6] == 0 && truncated[7] == 0,
                "oversized UDP answer must return a TC response without partial records");
        DnsWire.validateAndMapResponse(query, truncated);
        pass("oversized DoH response is truncated safely with TC for TCP retry");

        for (boolean ipv6Tcp : new boolean[] {false, true}) {
            byte[] tcpQueryPacket = makeDnsTcpPacket(ipv6Tcp, query);
            DnsPacketCodec.Packet tcpQuery = DnsPacketCodec.parse(tcpQueryPacket, tcpQueryPacket.length);
            check(tcpQuery.protocol == DnsPacketCodec.TCP && tcpQuery.destinationPort == 53,
                    "TCP/53 DNS packet must parse for both address families");
            check(tcpQuery.tcpMss == 1200, "TCP MSS option must be decoded for response segmentation");
            int dnsLength = ((tcpQuery.payload[0] & 0xff) << 8) | (tcpQuery.payload[1] & 0xff);
            byte[] framedQuery = Arrays.copyOfRange(tcpQuery.payload, 2, tcpQuery.payload.length);
            check(dnsLength == framedQuery.length, "TCP DNS two-byte message length must be parsed");
            DnsWire.validateQuery(framedQuery);
            byte[] tcpDnsResponse = dohClient.exchange(framedQuery);
            ByteArrayOutputStream responseFrame = new ByteArrayOutputStream();
            write16(responseFrame, tcpDnsResponse.length); responseFrame.write(tcpDnsResponse);
            byte[] tcpReply = DnsPacketCodec.buildTcpResponse(tcpQuery, 0x10203040,
                    tcpQuery.sequence + tcpQuery.payload.length, DnsPacketCodec.TCP_ACK | DnsPacketCodec.TCP_PSH,
                    responseFrame.toByteArray());
            DnsPacketCodec.Packet parsedTcpReply = DnsPacketCodec.parse(tcpReply, tcpReply.length);
            check(parsedTcpReply.sourcePort == 53 && parsedTcpReply.destinationPort == 53001,
                    "TCP DNS response ports must reverse");
            check(validTransportChecksum(tcpReply, ipv6Tcp ? DnsPacketCodec.IPV6 : DnsPacketCodec.IPV4,
                    DnsPacketCodec.TCP), "TCP response checksum must validate");
        }
        pass("IPv4/IPv6 DNS-over-TCP framing, local DoH exchange and checksummed replies");

        InetAddress ordinaryWeb = InetAddress.getByAddress(new byte[] {1, 1, 1, 1});
        check(!DnsRoutingPolicy.isExactDnsRoute(ordinaryWeb), "ordinary webpage destinations must not match any route");
        check(DnsRoutingPolicy.isExactDnsRoute(DnsRoutingPolicy.parseLiteral("9.9.9.9")), "Quad9 DNS IP must be routed");
        check(DnsRoutingPolicy.isExactDnsRoute(DnsRoutingPolicy.parseLiteral("2620:fe::fe")), "Quad9 IPv6 DNS IP must be routed");
        byte[] webTcp = makeTcpPacket(ordinaryWeb.getAddress(), InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34}).getAddress(), 443);
        DnsPacketCodec.Packet ordinaryTcp = DnsPacketCodec.parse(webTcp, webTcp.length);
        check(ordinaryTcp.protocol == DnsPacketCodec.TCP && ordinaryTcp.destinationPort == 443,
                "ordinary webpage TCP must remain non-DNS");
        check(!DnsRoutingPolicy.isExactDnsRoute(InetAddress.getByAddress(ordinaryTcp.destination)),
                "ordinary webpage TCP destination must stay outside the selective route set");
        check(DnsRoutingPolicy.DNS_SERVERS.size() == 4, "only exact Quad9 DNS hosts should be routed");
        pass("ordinary webpage TCP destination remains outside exact DNS host routes");
    }

    private static byte[] makeUdpPacket(boolean ipv6, byte[] query) throws Exception {
        int ipLength = ipv6 ? 40 : 20;
        byte[] packet = new byte[ipLength + 8 + query.length];
        if (ipv6) {
            packet[0] = 0x60;
            put16(packet, 4, 8 + query.length);
            packet[6] = (byte) DnsPacketCodec.UDP;
            packet[7] = 64;
            byte[] source = InetAddress.getByName("2001:db8::2").getAddress();
            byte[] destination = DnsRoutingPolicy.parseLiteral("2620:fe::fe").getAddress();
            System.arraycopy(source, 0, packet, 8, 16);
            System.arraycopy(destination, 0, packet, 24, 16);
        } else {
            packet[0] = 0x45; put16(packet, 2, packet.length); put16(packet, 6, 0x4000);
            packet[8] = 64; packet[9] = (byte) DnsPacketCodec.UDP;
            byte[] source = new byte[] {(byte) 192, 0, 2, 20};
            byte[] destination = DnsRoutingPolicy.parseLiteral("9.9.9.9").getAddress();
            System.arraycopy(source, 0, packet, 12, 4); System.arraycopy(destination, 0, packet, 16, 4);
            put16(packet, 10, checksum(packet, 0, 20));
        }
        int u = ipLength;
        put16(packet, u, 53000); put16(packet, u + 2, 53); put16(packet, u + 4, 8 + query.length);
        System.arraycopy(query, 0, packet, u + 8, query.length);
        return packet;
    }

    private static byte[] makeTcpPacket(byte[] source, byte[] destination, int destinationPort) {
        byte[] packet = new byte[40];
        packet[0] = 0x45; put16(packet, 2, packet.length); packet[8] = 64; packet[9] = (byte) DnsPacketCodec.TCP;
        System.arraycopy(source, 0, packet, 12, 4); System.arraycopy(destination, 0, packet, 16, 4);
        put16(packet, 20, 53001); put16(packet, 22, destinationPort); packet[32] = 0x50; packet[33] = 0x02;
        put16(packet, 34, 65535); put16(packet, 10, checksum(packet, 0, 20));
        return packet;
    }

    private static byte[] makeDnsTcpPacket(boolean ipv6, byte[] query) throws Exception {
        int ipLength = ipv6 ? 40 : 20;
        byte[] framed = new byte[query.length + 2];
        put16(framed, 0, query.length);
        System.arraycopy(query, 0, framed, 2, query.length);
        byte[] packet = new byte[ipLength + 24 + framed.length];
        if (ipv6) {
            packet[0] = 0x60; put16(packet, 4, packet.length - 40);
            packet[6] = (byte) DnsPacketCodec.TCP; packet[7] = 64;
            byte[] source = InetAddress.getByName("2001:db8::2").getAddress();
            byte[] destination = DnsRoutingPolicy.parseLiteral("2620:fe::fe").getAddress();
            System.arraycopy(source, 0, packet, 8, 16); System.arraycopy(destination, 0, packet, 24, 16);
        } else {
            packet[0] = 0x45; put16(packet, 2, packet.length); packet[8] = 64; packet[9] = (byte) DnsPacketCodec.TCP;
            System.arraycopy(new byte[] {(byte) 192, 0, 2, 20}, 0, packet, 12, 4);
            System.arraycopy(DnsRoutingPolicy.parseLiteral("9.9.9.9").getAddress(), 0, packet, 16, 4);
            put16(packet, 10, checksum(packet, 0, 20));
        }
        int tcp = ipLength;
        put16(packet, tcp, 53001); put16(packet, tcp + 2, 53);
        put32(packet, tcp + 4, 0x55667788); put32(packet, tcp + 8, 0x11223345);
        packet[tcp + 12] = 0x60; packet[tcp + 13] = (byte) (DnsPacketCodec.TCP_ACK | DnsPacketCodec.TCP_PSH);
        put16(packet, tcp + 14, 65535);
        packet[tcp + 20] = 2; packet[tcp + 21] = 4; put16(packet, tcp + 22, 1200);
        System.arraycopy(framed, 0, packet, tcp + 24, framed.length);
        return packet;
    }

    private static byte[] makeLargeResponse(byte[] query, int answerCount) throws IOException {
        int questionEnd = DnsWire.questionEnd(query);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(query[0]); out.write(query[1]); out.write(0x81); out.write(0x80);
        write16(out, 1); write16(out, answerCount); write16(out, 0); write16(out, 0);
        out.write(query, 12, questionEnd - 12);
        for (int i = 0; i < answerCount; i++) {
            out.write(0xc0); out.write(0x0c); write16(out, 16); write16(out, 1); write32(out, 60); write16(out, 100);
            out.write(99);
            for (int j = 1; j < 100; j++) out.write(i + j);
        }
        return out.toByteArray();
    }

    private static boolean validIpv4HeaderChecksum(byte[] packet) {
        return checksum(packet, 0, (packet[0] & 0x0f) * 4) == 0;
    }

    private static boolean validTransportChecksum(byte[] packet, int version, int protocol) {
        int ipOffset = version == DnsPacketCodec.IPV4 ? 20 : 40;
        int length = packet.length - ipOffset;
        long sum = 0;
        if (version == DnsPacketCodec.IPV4) {
            sum = addWords(sum, packet, 12, 8); sum += protocol; sum += length;
        } else {
            sum = addWords(sum, packet, 8, 32); sum += (length >>> 16) & 0xffff; sum += length & 0xffff; sum += protocol;
        }
        sum = addWords(sum, packet, ipOffset, length);
        return fold(sum) == 0xffff;
    }

    private static int checksum(byte[] bytes, int offset, int length) { return (~fold(addWords(0, bytes, offset, length))) & 0xffff; }
    private static long addWords(long sum, byte[] bytes, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i + 1 < end; i += 2) sum += ((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff);
        if ((length & 1) != 0) sum += (bytes[end - 1] & 0xff) << 8;
        return sum;
    }
    private static int fold(long sum) { while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16); return (int) sum & 0xffff; }
    private static void writeName(ByteArrayOutputStream out, String domain) {
        for (String label : domain.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII); out.write(bytes.length); out.write(bytes, 0, bytes.length);
        }
        out.write(0);
    }
    private static void write16(ByteArrayOutputStream out, int value) { out.write((value >>> 8) & 0xff); out.write(value & 0xff); }
    private static void write32(ByteArrayOutputStream out, int value) { out.write((value >>> 24) & 0xff); out.write((value >>> 16) & 0xff); out.write((value >>> 8) & 0xff); out.write(value & 0xff); }
    private static void put16(byte[] bytes, int offset, int value) { bytes[offset] = (byte) (value >>> 8); bytes[offset + 1] = (byte) value; }
    private static void put32(byte[] bytes, int offset, int value) { bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16); bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value; }
    private static byte[] readAll(InputStream input, int max) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
        while ((count = input.read(buffer)) != -1) { if (result.size() + count > max) throw new IOException("mock request too large"); result.write(buffer, 0, count); }
        return result.toByteArray();
    }
    private static void expectFailure(CheckedRunnable call, String message) throws Exception {
        boolean failed = false; try { call.run(); } catch (IOException expected) { failed = true; }
        check(failed, message);
    }
    private static void pass(String name) { System.out.println("PASS: " + name); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private interface CheckedRunnable { void run() throws Exception; }
}
