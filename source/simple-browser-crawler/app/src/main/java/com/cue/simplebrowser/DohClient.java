package com.cue.simplebrowser;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A no-fallback RFC 8484 client. Production uses Quad9's hostname and fixed bootstrap IP. */
final class DohClient {
    static final String QUAD9_HOST = "dns.quad9.net";
    static final String QUAD9_PATH = "/dns-query";
    static final String QUAD9_URL = "https://dns.quad9.net/dns-query";
    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int READ_TIMEOUT_MS = 5000;
    static final int MAX_HTTP_HEADER_BYTES = 32768;
    static final int MAX_DNS_BODY_BYTES = 65535;

    interface SocketProtector {
        boolean protect(Socket socket) throws IOException;
    }

    private final String host;
    private final String path;
    private final int port;
    private final InetAddress bootstrapAddress;
    private final SSLSocketFactory sslSocketFactory;
    private final SocketProtector protector;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    DohClient(String host, String path, int port, InetAddress bootstrapAddress,
              SSLSocketFactory sslSocketFactory, SocketProtector protector,
              int connectTimeoutMs, int readTimeoutMs) {
        if (host == null || host.isEmpty() || path == null || !path.startsWith("/") || port < 1 || port > 65535
                || bootstrapAddress == null || sslSocketFactory == null || protector == null) {
            throw new IllegalArgumentException("Incomplete DoH endpoint configuration");
        }
        this.host = host;
        this.path = path;
        this.port = port;
        this.bootstrapAddress = bootstrapAddress;
        this.sslSocketFactory = sslSocketFactory;
        this.protector = protector;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    static DohClient quad9(SocketProtector protector) throws IOException {
        // Numeric bootstrap addresses avoid resolving the DoH hostname through the system resolver.
        InetAddress bootstrap = InetAddress.getByAddress(new byte[] {(byte) 9, 9, 9, 9});
        return new DohClient(QUAD9_HOST, QUAD9_PATH, 443, bootstrap,
                (SSLSocketFactory) SSLSocketFactory.getDefault(), protector,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    byte[] exchange(byte[] query) throws IOException {
        DnsWire.validateQuery(query);
        Socket raw = new Socket();
        SSLSocket tls = null;
        try {
            raw.setSoTimeout(readTimeoutMs);
            if (!protector.protect(raw)) throw new IOException("VpnService.protect() refused the DoH socket");
            raw.connect(new InetSocketAddress(bootstrapAddress, port), connectTimeoutMs);
            tls = (SSLSocket) sslSocketFactory.createSocket(raw, host, port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(readTimeoutMs);
            tls.startHandshake();
            OutputStream output = tls.getOutputStream();
            String headers = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Type: application/dns-message\r\n"
                    + "Accept: application/dns-message\r\n"
                    + "Content-Length: " + query.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(query);
            output.flush();
            byte[] response = readHttpResponse(tls.getInputStream());
            return DnsWire.validateAndMapResponse(query, response);
        } finally {
            if (tls != null) {
                try { tls.close(); } catch (IOException ignored) { }
            } else {
                try { raw.close(); } catch (IOException ignored) { }
            }
        }
    }

    private byte[] readHttpResponse(InputStream input) throws IOException {
        String status = readAsciiLine(input, MAX_HTTP_HEADER_BYTES);
        if (status == null || !status.startsWith("HTTP/1.")) throw new IOException("Invalid DoH HTTP status line");
        String[] statusParts = status.split(" ", 3);
        if (statusParts.length < 2) throw new IOException("Incomplete DoH HTTP status line");
        int statusCode;
        try { statusCode = Integer.parseInt(statusParts[1]); }
        catch (NumberFormatException error) { throw new IOException("Invalid DoH HTTP status code", error); }
        if (statusCode < 200 || statusCode >= 300) throw new IOException("DoH HTTP status " + statusCode);

        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = status.length() + 2;
        while (true) {
            String line = readAsciiLine(input, MAX_HTTP_HEADER_BYTES - headerBytes);
            if (line == null) throw new EOFException("DoH response ended in headers");
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HTTP_HEADER_BYTES) throw new IOException("DoH response headers are excessive");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("Malformed DoH response header");
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (headers.containsKey(key) && !headers.get(key).equalsIgnoreCase(value)) {
                throw new IOException("Conflicting DoH response headers");
            }
            headers.put(key, value);
        }
        String contentType = headers.get("content-type");
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        if (!mediaType.equalsIgnoreCase("application/dns-message")) {
            throw new IOException("DoH response has the wrong content type");
        }
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && !transferEncoding.equalsIgnoreCase("chunked")) {
            throw new IOException("Unsupported DoH transfer encoding");
        }
        if (transferEncoding != null) return readChunked(input);
        String lengthValue = headers.get("content-length");
        if (lengthValue == null) return readToEof(input);
        int length;
        try { length = Integer.parseInt(lengthValue); }
        catch (NumberFormatException error) { throw new IOException("Invalid DoH content length", error); }
        if (length < 12 || length > MAX_DNS_BODY_BYTES) throw new IOException("DoH body length is outside DNS limits");
        byte[] body = readExact(input, length);
        if (input.read() != -1) throw new IOException("Unexpected bytes after DoH response body");
        return body;
    }

    private byte[] readChunked(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readAsciiLine(input, 128);
            if (line == null) throw new EOFException("DoH chunk header is missing");
            int semicolon = line.indexOf(';');
            String sizeText = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
            int size;
            try { size = Integer.parseInt(sizeText, 16); }
            catch (NumberFormatException error) { throw new IOException("Invalid DoH chunk size", error); }
            if (size < 0 || body.size() + (long) size > MAX_DNS_BODY_BYTES) throw new IOException("DoH chunked body is excessive");
            if (size == 0) {
                int trailerBytes = 0;
                while (true) {
                    String trailer = readAsciiLine(input, 8192 - trailerBytes);
                    if (trailer == null) throw new EOFException("DoH trailers are truncated");
                    trailerBytes += trailer.length() + 2;
                    if (trailerBytes > 8192) throw new IOException("DoH trailers are excessive");
                    if (trailer.isEmpty()) break;
                }
                break;
            }
            body.write(readExact(input, size));
            if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid DoH chunk terminator");
        }
        if (body.size() < 12) throw new IOException("DoH DNS body is truncated");
        return body.toByteArray();
    }

    private byte[] readToEof(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (body.size() + (long) count > MAX_DNS_BODY_BYTES) throw new IOException("DoH response body is excessive");
            body.write(buffer, 0, count);
        }
        if (body.size() < 12) throw new IOException("DoH DNS body is truncated");
        return body.toByteArray();
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) throw new EOFException("DoH response body is truncated");
            offset += read;
        }
        return data;
    }

    private static String readAsciiLine(InputStream input, int maximumBytes) throws IOException {
        if (maximumBytes <= 0) throw new IOException("DoH HTTP headers are excessive");
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean sawCr = false;
        while (line.size() <= maximumBytes) {
            int value = input.read();
            if (value < 0) return line.size() == 0 && !sawCr ? null : throwEof();
            if (sawCr) {
                if (value != '\n') throw new IOException("Malformed HTTP line ending");
                return new String(line.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (value == '\r') sawCr = true;
            else if (value == '\n' || value == 0 || value > 0x7f) throw new IOException("Invalid HTTP header byte");
            else line.write(value);
        }
        throw new IOException("DoH HTTP line is excessive");
    }

    private static String throwEof() throws EOFException { throw new EOFException("Truncated HTTP line"); }
}
