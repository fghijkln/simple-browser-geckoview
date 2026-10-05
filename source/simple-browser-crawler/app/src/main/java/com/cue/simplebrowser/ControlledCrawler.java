package com.cue.simplebrowser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, user-started crawler for public, same-origin HTTPS text pages.
 * It never uses GeckoView sessions, cookies, authentication, JavaScript or a proxy.
 */
final class ControlledCrawler {
    static final String USER_AGENT = "SimpleBrowserCrawler/1.0 (+user-initiated; static text only)";
    static final int MAX_PAGES = 4;
    static final int MAX_BODY_BYTES = 1_048_576;
    static final int MAX_ROBOTS_BYTES = 524_288;
    static final int MAX_LINKS = 12;
    static final int CONNECT_TIMEOUT_MS = 8_000;
    static final int READ_TIMEOUT_MS = 8_000;
    static final int MAX_REDIRECTS = 3;
    static final long MIN_REQUEST_GAP_MS = 2_000L;
    static final long MAX_CRAWL_DELAY_MS = 30_000L;
    static final long MAX_CRAWL_DURATION_MS = 90_000L;
    static final long ROBOTS_CACHE_TTL_MS = 5 * 60_000L;
    private static final int MAX_ROBOTS_CACHE_ENTRIES = 32;
    private static final ConcurrentHashMap<String, CachedRobots> ROBOTS_CACHE = new ConcurrentHashMap<>();

    interface Listener {
        void onStatus(String status);
        void onPage(Page page);
        void onFinished(Finish finish);
    }

    interface ConnectionOpener {
        HttpURLConnection open(URL url) throws IOException;
    }

    interface TargetPolicy {
        void validate(URI uri) throws IOException;
    }

    static final class Page {
        final String url;
        final String title;
        final String summary;

        Page(String url, String title, String summary) {
            this.url = url;
            this.title = title;
            this.summary = summary;
        }
    }

    static final class Finish {
        final boolean canceled;
        final boolean stopped;
        final String message;
        final List<Page> pages;

        Finish(boolean canceled, boolean stopped, String message, List<Page> pages) {
            this.canceled = canceled;
            this.stopped = stopped;
            this.message = message;
            this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
        }
    }

    private static final class CachedRobots {
        final RobotsPolicy policy;
        final long expiresAtMs;
        CachedRobots(RobotsPolicy policy, long expiresAtMs) {
            this.policy = policy;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final class Response {
        final int status;
        final String contentType;
        final String body;
        final String location;
        final String retryAfter;

        Response(int status, String contentType, String body, String location, String retryAfter) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.location = location;
            this.retryAfter = retryAfter;
        }
    }

    private static final class CrawlStop extends IOException {
        CrawlStop(String message) { super(message); }
    }

    private final ConnectionOpener opener;
    private final TargetPolicy targetPolicy;
    private final long minimumGapMs;
    private final boolean useRobotsCache;
    private final AtomicBoolean canceled = new AtomicBoolean();
    private volatile HttpURLConnection activeConnection;
    private long lastRequestStartedMs;
    private long deadlineAtMs;

    ControlledCrawler() {
        this(url -> (HttpURLConnection) url.openConnection(), ControlledCrawler::validatePublicHttpsTarget,
                MIN_REQUEST_GAP_MS, true);
    }

    ControlledCrawler(ConnectionOpener opener, TargetPolicy targetPolicy, long minimumGapMs) {
        this(opener, targetPolicy, minimumGapMs, false);
    }

    ControlledCrawler(ConnectionOpener opener, TargetPolicy targetPolicy, long minimumGapMs, boolean useRobotsCache) {
        if (opener == null || targetPolicy == null || minimumGapMs < 0) {
            throw new IllegalArgumentException("Incomplete crawler configuration");
        }
        this.opener = opener;
        this.targetPolicy = targetPolicy;
        this.minimumGapMs = minimumGapMs;
        this.useRobotsCache = useRobotsCache;
    }

    void cancel() {
        canceled.set(true);
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    void crawl(String seedUrl, Listener listener) {
        ArrayList<Page> pages = new ArrayList<>();
        boolean stopped = false;
        String finishMessage;
        try {
            if (listener == null) throw new CrawlStop("没有可用的结果接收器。");
            checkCanceled();
            deadlineAtMs = System.currentTimeMillis() + MAX_CRAWL_DURATION_MS;
            URI seed = parseAndNormalize(seedUrl);
            targetPolicy.validate(seed);
            URI origin = originOf(seed);
            String robotsKey = origin.toASCIIString();
            RobotsPolicy robots = cachedRobots(robotsKey);
            if (robots == null) {
                listener.onStatus("先读取同站 robots.txt，检查访问规则…");
                URI robotsUri = origin.resolve("/robots.txt");
                Response robotsResponse = requestFollowingSameOriginRedirects(robotsUri, origin, MAX_ROBOTS_BYTES);
                if (robotsResponse.status == 404 || robotsResponse.status == 410) {
                    robots = RobotsPolicy.allowAll();
                    cacheRobots(robotsKey, robots);
                } else if (robotsResponse.status == 200) {
                    if (!isTextContentType(robotsResponse.contentType)) {
                        throw new CrawlStop("robots.txt 不是可读文本；为避免猜测站点规则，已停止。");
                    }
                    robots = RobotsPolicy.parse(robotsResponse.body);
                    cacheRobots(robotsKey, robots);
                } else {
                    throw responseStop("robots.txt", robotsResponse);
                }
            } else {
                listener.onStatus("使用 5 分钟内存缓存的同站 robots.txt 规则…");
            }
            if (robots.crawlDelayMs > MAX_CRAWL_DELAY_MS) {
                throw new CrawlStop("站点声明的 Crawl-delay 超过 30 秒；本次抓取停止，不会缩短该间隔。");
            }
            long requestGap = Math.max(minimumGapMs, robots.crawlDelayMs);
            Deque<URI> queue = new ArrayDeque<>();
            Set<String> queued = new LinkedHashSet<>();
            queue.add(seed);
            queued.add(seed.toASCIIString());
            int attempted = 0;
            while (!queue.isEmpty() && pages.size() < MAX_PAGES && attempted < MAX_PAGES) {
                checkCanceled();
                URI pageUri = queue.removeFirst();
                if (!robots.isAllowed(pathAndQuery(pageUri))) {
                    if (attempted == 0) throw new CrawlStop("robots.txt 不允许抓取所提供的起始页面；已停止。");
                    listener.onStatus("跳过 robots.txt 禁止的页面：" + pageUri.toASCIIString());
                    continue;
                }
                listener.onStatus("读取公开静态页面 " + (attempted + 1) + " / " + MAX_PAGES + "…");
                Response response = requestFollowingSameOriginRedirects(pageUri, origin, MAX_BODY_BYTES, requestGap);
                attempted++;
                if (response.status == 404 || response.status == 410) {
                    listener.onStatus("页面返回 HTTP " + response.status + "，已跳过。");
                    continue;
                }
                if (response.status != 200) throw responseStop(pageUri.toASCIIString(), response);
                if (!isSupportedPageType(response.contentType)) {
                    listener.onStatus("跳过非 HTML/纯文本内容：" + pageUri.toASCIIString());
                    continue;
                }
                String signal = PageSignals.blockingReason(response.body);
                if (signal != null) throw new CrawlStop(signal + "；没有尝试绕过。");
                CrawlerHtmlParser.Document document = response.contentType.toLowerCase(Locale.ROOT).startsWith("text/plain")
                        ? CrawlerHtmlParser.parsePlain(response.body)
                        : CrawlerHtmlParser.parse(response.body, pageUri.toASCIIString());
                String title = document.title.trim().isEmpty() ? pageUri.getHost() : document.title.trim();
                String summary = summarize(document.text);
                if (summary.isEmpty()) {
                    listener.onStatus("页面没有可展示的静态文字，已跳过：" + pageUri.toASCIIString());
                    continue;
                }
                Page page = new Page(pageUri.toASCIIString(), title, summary);
                pages.add(page);
                listener.onPage(page);
                for (String link : document.links) {
                    if (queued.size() >= MAX_LINKS + 1) break;
                    URI candidate;
                    try {
                        candidate = parseAndNormalize(link);
                        targetPolicy.validate(candidate);
                    } catch (IOException | IllegalArgumentException ignored) {
                        continue;
                    }
                    if (!sameOrigin(origin, candidate)) continue;
                    String normalized = candidate.toASCIIString();
                    if (queued.add(normalized)) queue.addLast(candidate);
                }
            }
            checkCanceled();
            finishMessage = pages.isEmpty()
                    ? "没有找到可显示的静态文本页面。"
                    : "完成：读取 " + pages.size() + " 个页面；仅限同一来源站点，最多 " + MAX_PAGES + " 页。";
        } catch (CrawlStop stop) {
            stopped = true;
            finishMessage = stop.getMessage();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            canceled.set(true);
            finishMessage = "抓取已取消。";
        } catch (IOException | RuntimeException error) {
            finishMessage = error.getMessage() == null ? "抓取失败；没有执行任何规避操作。" : error.getMessage();
        } finally {
            HttpURLConnection connection = activeConnection;
            activeConnection = null;
            if (connection != null) connection.disconnect();
        }
        if (listener != null) {
            boolean wasCanceled = canceled.get();
            listener.onFinished(new Finish(wasCanceled, stopped && !wasCanceled, finishMessage, pages));
        }
    }

    private Response requestFollowingSameOriginRedirects(URI first, URI origin, int bodyLimit)
            throws IOException, InterruptedException {
        return requestFollowingSameOriginRedirects(first, origin, bodyLimit, minimumGapMs);
    }

    private Response requestFollowingSameOriginRedirects(URI first, URI origin, int bodyLimit, long requestGap)
            throws IOException, InterruptedException {
        URI current = first;
        for (int redirects = 0; ; redirects++) {
            checkCanceled();
            targetPolicy.validate(current);
            if (!sameOrigin(origin, current)) throw new CrawlStop("发现跨站重定向；为限制抓取范围已停止。");
            waitForRequestSlot(requestGap);
            HttpURLConnection connection = opener.open(current.toURL());
            activeConnection = connection;
            connection.setInstanceFollowRedirects(false);
            long remainingMs = deadlineAtMs - System.currentTimeMillis();
            if (remainingMs <= 0) throw new CrawlStop("抓取超过 90 秒总时限；已停止。");
            connection.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remainingMs));
            connection.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remainingMs));
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "text/html, application/xhtml+xml, text/plain;q=0.9, */*;q=0.1");
            connection.setRequestProperty("Accept-Encoding", "identity");
            // This connection is independent of GeckoView; no browser cookie jar or auth header is attached.
            connection.setRequestProperty("Cookie", "");
            lastRequestStartedMs = System.currentTimeMillis();
            try {
                int status = connection.getResponseCode();
                String retryAfter = connection.getHeaderField("Retry-After");
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects >= MAX_REDIRECTS) {
                        throw new CrawlStop("重定向缺少有效地址或超过 3 跳；已停止。");
                    }
                    if (retryAfter != null && !retryAfter.trim().isEmpty()) {
                        throw new CrawlStop("重定向响应带有 Retry-After；为避免过早跟随，已停止。"
                                + retryAfterHint(retryAfter));
                    }
                    URI next;
                    try {
                        next = parseAndNormalize(current.resolve(location).toASCIIString());
                    } catch (IllegalArgumentException invalid) {
                        throw new CrawlStop("重定向目标无效；已停止。");
                    }
                    if (!sameOrigin(origin, next)) throw new CrawlStop("发现跨站重定向；为限制抓取范围已停止。");
                    targetPolicy.validate(next);
                    current = next;
                    continue;
                }
                String contentType = connection.getContentType();
                String body = "";
                if (status == 200) {
                    long length = connection.getContentLengthLong();
                    if (length > bodyLimit) throw new CrawlStop("响应超过 " + bodyLimit + " 字节上限；已停止。");
                    try (InputStream input = connection.getInputStream()) {
                        body = readBoundedText(input, bodyLimit, charsetFrom(contentType), deadlineAtMs);
                    }
                }
                return new Response(status, contentType, body, null, retryAfter);
            } finally {
                connection.disconnect();
                activeConnection = null;
            }
        }
    }

    private RobotsPolicy cachedRobots(String key) {
        if (!useRobotsCache) return null;
        CachedRobots cached = ROBOTS_CACHE.get(key);
        if (cached == null) return null;
        if (cached.expiresAtMs <= System.currentTimeMillis()) {
            ROBOTS_CACHE.remove(key, cached);
            return null;
        }
        return cached.policy;
    }

    private void cacheRobots(String key, RobotsPolicy policy) {
        if (!useRobotsCache) return;
        if (ROBOTS_CACHE.size() >= MAX_ROBOTS_CACHE_ENTRIES && !ROBOTS_CACHE.containsKey(key)) {
            String evict = ROBOTS_CACHE.keySet().stream().findFirst().orElse(null);
            if (evict != null) ROBOTS_CACHE.remove(evict);
        }
        ROBOTS_CACHE.put(key, new CachedRobots(policy, System.currentTimeMillis() + ROBOTS_CACHE_TTL_MS));
    }

    private void waitForRequestSlot(long requestedGapMs) throws InterruptedException, CrawlStop {
        checkCanceled();
        long gap = Math.max(minimumGapMs, requestedGapMs);
        long remaining = lastRequestStartedMs == 0 ? 0 : gap - (System.currentTimeMillis() - lastRequestStartedMs);
        while (remaining > 0) {
            checkCanceled();
            if (deadlineAtMs > 0 && System.currentTimeMillis() >= deadlineAtMs)
                throw new CrawlStop("抓取超过 90 秒总时限；已停止。");
            Thread.sleep(Math.min(remaining, 200L));
            remaining = gap - (System.currentTimeMillis() - lastRequestStartedMs);
        }
    }

    private void checkCanceled() throws CrawlStop {
        if (canceled.get() || Thread.currentThread().isInterrupted()) throw new CrawlStop("抓取已取消。");
    }

    private static CrawlStop responseStop(String page, Response response) {
        String retry = retryAfterHint(response.retryAfter);
        if (response.status == 401 || response.status == 403) {
            return new CrawlStop("HTTP " + response.status + " 拒绝访问（" + page + "）；已停止，没有登录、伪装或绕过。");
        }
        if (response.status == 429) {
            return new CrawlStop("HTTP 429 限流（" + page + "）；已停止，不会自动重试。" + retry);
        }
        if (response.status == 503) {
            return new CrawlStop("HTTP 503 服务暂不可用（" + page + "）；已停止。" + retry);
        }
        if (response.status >= 500) return new CrawlStop("HTTP " + response.status + " 服务端错误（" + page + "）；已停止。");
        if (response.status >= 300 && response.status < 400) return new CrawlStop("HTTP " + response.status + " 重定向无法安全跟随（" + page + "）；已停止。");
        return new CrawlStop("无法读取 " + page + "：HTTP " + response.status + "；已停止。");
    }

    private static String retryAfterHint(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds >= 0) return "站点提供 Retry-After: " + seconds + " 秒；请稍后再由你决定是否重试。";
        } catch (NumberFormatException ignored) { }
        try {
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(trimmed, position);
            if (date != null && position.getIndex() == trimmed.length()) {
                long seconds = Math.max(0L, (date.getTime() - System.currentTimeMillis() + 999L) / 1_000L);
                return "站点提供 Retry-After；建议至少等待约 " + seconds + " 秒后再决定是否重试。";
            }
        } catch (IllegalArgumentException ignored) { }
        return "站点提供 Retry-After 信号；请稍后再由你决定是否重试。";
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isTextContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/plain");
    }

    private static boolean isSupportedPageType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/html") || lower.startsWith("application/xhtml+xml")
                || lower.startsWith("text/plain");
    }

    private static Charset charsetFrom(String contentType) {
        if (contentType != null) {
            Matcher matcher = Pattern.compile("(?i)charset\\s*=\\s*[\\\"]?([^;\\\"\\s]+)").matcher(contentType);
            if (matcher.find()) {
                try { return Charset.forName(matcher.group(1).trim()); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String readBoundedText(InputStream input, int limit, Charset charset, long deadlineAtMs)
            throws IOException, CrawlStop {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 16_384));
        byte[] buffer = new byte[8_192];
        int read;
        while (true) {
            if (System.currentTimeMillis() >= deadlineAtMs) throw new CrawlStop("抓取超过 90 秒总时限；已停止。");
            read = input.read(buffer);
            if (read < 0) break;
            if (output.size() + (long) read > limit) throw new CrawlStop("响应正文超过字节上限；已停止。");
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), charset);
    }

    private static String summarize(String text) {
        String normalized = text == null ? "" : text.replaceAll("[\\p{Z}\\s]+", " ").trim();
        if (normalized.length() > 240) normalized = normalized.substring(0, 239).trim() + "…";
        return normalized;
    }

    private static URI parseAndNormalize(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty() || raw.length() > 4_096) throw new CrawlStop("请输入一个 HTTPS 公开页面网址。");
        try {
            URI parsed = new URI(raw.trim()).normalize();
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null || parsed.getUserInfo() != null || parsed.getFragment() != null) {
                throw new CrawlStop("网址必须是没有用户名、密码或片段标识的 HTTPS 页面网址。");
            }
            // URI.normalize() preserves existing percent-encoding; rebuilding from raw components
            // with the decoded-component constructor would double-escape percent sequences.
            return parsed;
        } catch (CrawlStop stop) {
            throw stop;
        } catch (Exception invalid) {
            throw new CrawlStop("网址格式无效；请使用标准 HTTPS 页面链接。");
        }
    }

    private static URI originOf(URI uri) throws IOException {
        try { return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/", null, null); }
        catch (Exception invalid) { throw new CrawlStop("无法确定页面来源站点。"); }
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }

    private static void validatePublicHttpsTarget(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new CrawlStop("此版本只抓取 HTTPS 页面；不会降级到明文 HTTP。");
        if (effectivePort(uri) != 443) throw new CrawlStop("只允许 HTTPS 默认端口 443，以限制抓取范围。");
        String host = uri.getHost();
        if (host == null || host.isEmpty() || host.indexOf(':') >= 0 || host.matches("(?i)^[0-9.]+$")) {
            throw new CrawlStop("不接受 IP 字面地址；请提供公开网站的主机名。");
        }
        InetAddress[] addresses;
        try { addresses = InetAddress.getAllByName(host); }
        catch (IOException error) { throw new CrawlStop("无法解析网站主机名；已停止，未回退到其他解析策略。"); }
        if (addresses.length == 0) throw new CrawlStop("网站主机名没有可用地址；已停止。");
        for (InetAddress address : addresses) {
            if (isNonPublicAddress(address)) {
                throw new CrawlStop("网站解析到本机、私有或特殊用途地址；为避免访问本地网络已停止。");
            }
        }
    }

    private static boolean isNonPublicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff, b = bytes[1] & 0xff, c = bytes[2] & 0xff;
            return a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 0 || b == 168 || (b == 88 && c == 99)))
                    || (a == 192 && b == 0 && c == 2)
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)) )
                    || (a == 203 && b == 0 && c == 113);
        }
        if (bytes.length == 16) {
            if ((bytes[0] & 0xfe) == 0xfc || (bytes[0] == 0x20 && bytes[1] == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) || isIpv4MappedNonPublic(bytes)) return true;
        }
        return false;
    }

    private static boolean isIpv4MappedNonPublic(byte[] bytes) {
        if (bytes.length != 16) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        if ((bytes[10] & 0xff) != 0xff || (bytes[11] & 0xff) != 0xff) return false;
        byte[] ipv4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        try { return isNonPublicAddress(InetAddress.getByAddress(ipv4)); }
        catch (IOException impossible) { return true; }
    }

    private static URI parseRobotsTarget(URI base, String value) throws IOException {
        try { return parseAndNormalize(base.resolve(value).toASCIIString()); }
        catch (IllegalArgumentException invalid) { throw new CrawlStop("robots.txt 中的链接格式无效；已停止。"); }
    }

    private static final class RobotsPolicy {
        private static final class Rule {
            final boolean allow;
            final String pattern;
            Rule(boolean allow, String pattern) { this.allow = allow; this.pattern = pattern; }
        }
        private final List<Rule> rules;
        final long crawlDelayMs;

        private RobotsPolicy(List<Rule> rules, long crawlDelayMs) {
            this.rules = rules;
            this.crawlDelayMs = crawlDelayMs;
        }

        static RobotsPolicy allowAll() { return new RobotsPolicy(Collections.emptyList(), 0); }

        static RobotsPolicy parse(String text) throws CrawlStop {
            ArrayList<List<Rule>> groups = new ArrayList<>();
            ArrayList<String> agents = new ArrayList<>();
            ArrayList<Rule> currentRules = new ArrayList<>();
            ArrayList<List<Rule>> matching = new ArrayList<>();
            ArrayList<List<Rule>> wildcards = new ArrayList<>();
            boolean sawDirective = false;
            long crawlDelay = 0;
            String[] lines = text.split("\\r?\\n", -1);
            for (String rawLine : lines) {
                String line = rawLine.split("#", 2)[0].trim();
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if ("user-agent".equals(key)) {
                    if (sawDirective) {
                        groups.add(currentRules);
                        if (agents.stream().anyMatch(agent -> agent.toLowerCase(Locale.ROOT).startsWith("simplebrowsercrawler"))) matching.add(currentRules);
                        else if (agents.stream().anyMatch(agent -> agent.equals("*"))) wildcards.add(currentRules);
                        agents.clear();
                        currentRules = new ArrayList<>();
                        sawDirective = false;
                    }
                    if (!value.isEmpty()) agents.add(value.toLowerCase(Locale.ROOT));
                    continue;
                }
                if (agents.isEmpty()) continue;
                if ("allow".equals(key) || "disallow".equals(key)) {
                    sawDirective = true;
                    if (!value.isEmpty()) currentRules.add(new Rule("allow".equals(key), value));
                } else if ("crawl-delay".equals(key)) {
                    sawDirective = true;
                    try {
                        double seconds = Double.parseDouble(value);
                        if (seconds >= 0 && Double.isFinite(seconds)) crawlDelay = Math.max(crawlDelay, (long) Math.ceil(seconds * 1_000d));
                    } catch (NumberFormatException ignored) { }
                }
            }
            if (!agents.isEmpty()) {
                groups.add(currentRules);
                if (agents.stream().anyMatch(agent -> agent.toLowerCase(Locale.ROOT).startsWith("simplebrowsercrawler"))) matching.add(currentRules);
                else if (agents.stream().anyMatch(agent -> agent.equals("*"))) wildcards.add(currentRules);
            }
            List<List<Rule>> selected = matching.isEmpty() ? wildcards : matching;
            ArrayList<Rule> selectedRules = new ArrayList<>();
            for (List<Rule> group : selected) selectedRules.addAll(group);
            return new RobotsPolicy(selectedRules, crawlDelay);
        }

        boolean isAllowed(String path) {
            int bestLength = -1;
            boolean allowed = true;
            for (Rule rule : rules) {
                if (!robotsPatternMatches(path, rule.pattern)) continue;
                int length = rule.pattern.replace("*", "").replace("$", "").length();
                if (length > bestLength || (length == bestLength && rule.allow)) {
                    bestLength = length;
                    allowed = rule.allow;
                }
            }
            return allowed;
        }

        private static boolean robotsPatternMatches(String path, String pattern) {
            boolean end = pattern.endsWith("$");
            String source = end ? pattern.substring(0, pattern.length() - 1) : pattern;
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (ch == '*') regex.append(".*");
                else regex.append(Pattern.quote(String.valueOf(ch)));
            }
            if (end) regex.append('$'); else regex.append(".*");
            try { return Pattern.compile(regex.toString()).matcher(path).matches(); }
            catch (RuntimeException invalid) { return false; }
        }
    }

    private static final class PageSignals {
        private static final Pattern[] CAPTCHA = new Pattern[] {
                Pattern.compile("(?is)captcha|cf-chl-|challenge-platform|verify you are human|robot check|are you a robot"),
                Pattern.compile("(?is)<title[^>]*>[^<]*(?:just a moment|security check)[^<]*</title>")
        };
        private static final Pattern[] ACCESS_WALL = new Pattern[] {
                Pattern.compile("(?is)sign in to continue|log in to continue|please sign in to read|subscribe to continue|subscription required|members only|paywall")
        };

        static String blockingReason(String html) {
            if (matches(CAPTCHA, html)) return "页面看起来包含 CAPTCHA 或反机器人挑战";
            if (matches(ACCESS_WALL, html)) return "页面看起来有登录墙、订阅墙或付费墙";
            return null;
        }

        private static boolean matches(Pattern[] patterns, String value) {
            if (value == null) return false;
            for (Pattern pattern : patterns) if (pattern.matcher(value).find()) return true;
            return false;
        }
    }
}
