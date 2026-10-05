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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * A user-started, serial crawler for public HTTPS text pages.
 * It never uses GeckoView sessions, cookies, authentication, JavaScript or a proxy.
 */
final class ControlledCrawler {
    static final String USER_AGENT = "SimpleBrowserCrawler/1.0 (+user-initiated; static text only)";
    static final int MAX_PAGES = 100;
    static final int DEFAULT_BODY_BYTES = 16 * 1_048_576;
    static final int MAX_BODY_BYTES = DEFAULT_BODY_BYTES;
    static final int DEFAULT_QUEUE_LIMIT = 10_000;
    static final long DEFAULT_RATE_LIMIT_WAIT_MS = 30_000L;
    static final int DEFAULT_ROBOTS_BYTES = 524_288;
    static final int MAX_ROBOTS_BYTES = DEFAULT_ROBOTS_BYTES;
    static final int CONNECT_TIMEOUT_MS = 8_000;
    static final int READ_TIMEOUT_MS = 8_000;
    static final int DEFAULT_REDIRECT_LIMIT = 3;
    static final int MAX_REDIRECTS = DEFAULT_REDIRECT_LIMIT;
    static final long MIN_REQUEST_GAP_MS = 1_000L;
    static final long MAX_CRAWL_DURATION_MS = 1_800_000L;
    static final long ROBOTS_CACHE_TTL_MS = 5 * 60_000L;
    private static final int MAX_ROBOTS_CACHE_ENTRIES = 32;
    private static final ConcurrentHashMap<String, CachedRobots> ROBOTS_CACHE = new ConcurrentHashMap<>();

    enum RobotsMode { RESPECT, IGNORE, ASK_EACH_BLOCKED }

    static final class Configuration {
        final int pageLimit;
        final int queueLimit;
        final long totalDurationMs;
        final long perPageBytes;
        final long minimumRequestGapMs;
        final long rateLimitWaitMs;
        final int redirectLimit;
        final long robotsFileBytes;
        final RobotsMode robotsMode;
        final boolean askBeforeCrossSite;
        final boolean askForNonSuccessResponses;

        Configuration(int pageLimit, long totalDurationMs, long perPageBytes,
                      long minimumRequestGapMs, RobotsMode robotsMode,
                      boolean askBeforeCrossSite, boolean askForNonSuccessResponses) {
            this(pageLimit, DEFAULT_QUEUE_LIMIT, totalDurationMs, perPageBytes, minimumRequestGapMs,
                    DEFAULT_RATE_LIMIT_WAIT_MS, DEFAULT_REDIRECT_LIMIT, DEFAULT_ROBOTS_BYTES,
                    robotsMode, askBeforeCrossSite, askForNonSuccessResponses);
        }

        Configuration(int pageLimit, int queueLimit, long totalDurationMs, long perPageBytes,
                      long minimumRequestGapMs, long rateLimitWaitMs, RobotsMode robotsMode,
                      boolean askBeforeCrossSite, boolean askForNonSuccessResponses) {
            this(pageLimit, queueLimit, totalDurationMs, perPageBytes, minimumRequestGapMs,
                    rateLimitWaitMs, DEFAULT_REDIRECT_LIMIT, DEFAULT_ROBOTS_BYTES, robotsMode,
                    askBeforeCrossSite, askForNonSuccessResponses);
        }

        Configuration(int pageLimit, int queueLimit, long totalDurationMs, long perPageBytes,
                      long minimumRequestGapMs, long rateLimitWaitMs, int redirectLimit,
                      long robotsFileBytes, RobotsMode robotsMode,
                      boolean askBeforeCrossSite, boolean askForNonSuccessResponses) {
            if (pageLimit < 0 || queueLimit < 0 || totalDurationMs < 0 || perPageBytes < 0
                    || minimumRequestGapMs < 0 || rateLimitWaitMs < 0 || redirectLimit < 0
                    || robotsFileBytes < 0
                    || robotsMode == null) throw new IllegalArgumentException("Invalid crawler configuration");
            this.pageLimit = pageLimit;
            this.queueLimit = queueLimit;
            this.totalDurationMs = totalDurationMs;
            this.perPageBytes = perPageBytes;
            this.minimumRequestGapMs = minimumRequestGapMs;
            this.rateLimitWaitMs = rateLimitWaitMs;
            this.redirectLimit = redirectLimit;
            this.robotsFileBytes = robotsFileBytes;
            this.robotsMode = robotsMode;
            this.askBeforeCrossSite = askBeforeCrossSite;
            this.askForNonSuccessResponses = askForNonSuccessResponses;
        }

        static Configuration defaults() {
            return new Configuration(MAX_PAGES, DEFAULT_QUEUE_LIMIT, MAX_CRAWL_DURATION_MS,
                    DEFAULT_BODY_BYTES, MIN_REQUEST_GAP_MS, DEFAULT_RATE_LIMIT_WAIT_MS,
                    DEFAULT_REDIRECT_LIMIT, DEFAULT_ROBOTS_BYTES, RobotsMode.RESPECT, false, true);
        }
    }

    static final class Decision {
        final String title;
        final String message;
        final String allowLabel;
        final String denyLabel;
        final String openInBrowserUrl;
        Decision(String title, String message, String allowLabel, String denyLabel) {
            this(title, message, allowLabel, denyLabel, null);
        }
        Decision(String title, String message, String allowLabel, String denyLabel,
                 String openInBrowserUrl) {
            this.title = title;
            this.message = message;
            this.allowLabel = allowLabel;
            this.denyLabel = denyLabel;
            this.openInBrowserUrl = openInBrowserUrl;
        }
    }

    interface Listener {
        void onStatus(String status);
        void onPage(Page page);
        void onFinished(Finish finish);
        default void onDecision(Decision prompt, Consumer<Boolean> decision) { decision.accept(false); }
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
        final boolean serverResponseOnly;

        Page(String url, String title, String summary) {
            this(url, title, summary, false);
        }

        Page(String url, String title, String summary, boolean serverResponseOnly) {
            this.url = url;
            this.title = title;
            this.summary = summary;
            this.serverResponseOnly = serverResponseOnly;
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
        final URI finalUri;

        Response(int status, String contentType, String body, String location, String retryAfter, URI finalUri) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.location = location;
            this.retryAfter = retryAfter;
            this.finalUri = finalUri;
        }
    }

    private static final class CrawlStop extends IOException {
        CrawlStop(String message) { super(message); }
    }

    private final ConnectionOpener opener;
    private final TargetPolicy targetPolicy;
    private final Configuration configuration;
    private final boolean useRobotsCache;
    private final AtomicBoolean canceled = new AtomicBoolean();
    private volatile HttpURLConnection activeConnection;
    private long lastRequestStartedMs;
    private long deadlineAtMs;

    ControlledCrawler() {
        this(url -> (HttpURLConnection) url.openConnection(), ControlledCrawler::validatePublicHttpsTarget,
                Configuration.defaults(), true);
    }

    ControlledCrawler(ConnectionOpener opener, TargetPolicy targetPolicy, long minimumGapMs) {
        this(opener, targetPolicy, new Configuration(MAX_PAGES, MAX_CRAWL_DURATION_MS,
                DEFAULT_BODY_BYTES, minimumGapMs, RobotsMode.RESPECT, false, true), false);
    }

    ControlledCrawler(ConnectionOpener opener, TargetPolicy targetPolicy, long minimumGapMs, boolean useRobotsCache) {
        this(opener, targetPolicy, new Configuration(MAX_PAGES, MAX_CRAWL_DURATION_MS,
                DEFAULT_BODY_BYTES, minimumGapMs, RobotsMode.RESPECT, false, true), useRobotsCache);
    }

    ControlledCrawler(ConnectionOpener opener, TargetPolicy targetPolicy, Configuration configuration) {
        this(opener, targetPolicy, configuration, false);
    }

    ControlledCrawler(ConnectionOpener opener, TargetPolicy targetPolicy, Configuration configuration,
                      boolean useRobotsCache) {
        if (opener == null || targetPolicy == null || configuration == null) {
            throw new IllegalArgumentException("Incomplete crawler configuration");
        }
        this.opener = opener;
        this.targetPolicy = targetPolicy;
        this.configuration = configuration;
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
            long now = System.currentTimeMillis();
            deadlineAtMs = configuration.totalDurationMs == 0 ? 0
                    : configuration.totalDurationMs > Long.MAX_VALUE - now
                    ? Long.MAX_VALUE : now + configuration.totalDurationMs;
            URI seed = parseAndNormalize(seedUrl);
            targetPolicy.validate(seed);
            URI seedOrigin = originOf(seed);
            Set<String> approvedOrigins = new HashSet<>();
            approvedOrigins.add(seedOrigin.toASCIIString());
            Map<String, RobotsPolicy> robotsByOrigin = new HashMap<>();
            Deque<URI> queue = new ArrayDeque<>();
            Set<String> queued = new LinkedHashSet<>();
            queue.add(seed);
            queued.add(seed.toASCIIString());
            long attempted = 0;
            int pageLimit = configuration.pageLimit;
            while (!queue.isEmpty() && (pageLimit == 0
                    || (pages.size() < pageLimit && attempted < pageLimit))) {
                checkCanceled();
                URI pageUri = queue.removeFirst();
                URI pageOrigin = originOf(pageUri);
                String originKey = pageOrigin.toASCIIString();
                if (!approvedOrigins.contains(originKey)) throw new CrawlStop("未确认的跨站目标；未发送请求。");
                RobotsPolicy robots = robotsByOrigin.get(originKey);
                if (robots == null) robots = loadRobots(pageOrigin, approvedOrigins, robotsByOrigin, listener);
                long requestGap = Math.max(configuration.minimumRequestGapMs, robots.crawlDelayMs);
                if (!robots.isAllowed(pathAndQuery(pageUri))
                        && configuration.robotsMode != RobotsMode.IGNORE) {
                    boolean proceed = configuration.robotsMode == RobotsMode.ASK_EACH_BLOCKED
                            && awaitDecision(listener, new Decision("robots.txt 访问规则",
                            "robots.txt 对此路径标记为禁止：" + pageUri.toASCIIString()
                                    + "\n允许只对本次抓取生效；你仍需自行确认已获授权。拒绝则跳过该页。",
                            "本次继续", "跳过"));
                    if (!proceed) {
                        if (attempted == 0) throw new CrawlStop("robots.txt 不允许抓取所提供的起始页面；已停止。可在选项中改为询问或忽略。");
                        listener.onStatus("按当前 robots 策略跳过页面：" + pageUri.toASCIIString());
                        continue;
                    }
                }
                listener.onStatus("读取公开静态页面 " + (attempted + 1) + " / "
                        + (pageLimit == 0 ? "不限" : pageLimit) + "…");
                Response response = requestWithChoices(pageUri, pageOrigin, configuration.perPageBytes,
                        requestGap, approvedOrigins, listener);
                attempted++;
                if (response.location != null) {
                    URI redirected;
                    try { redirected = parseAndNormalize(response.location); }
                    catch (IOException invalid) { throw new CrawlStop("跨站重定向地址无效；已停止。"); }
                    enqueue(queue, queued, redirected, true);
                    continue;
                }
                if (response.status != 200) {
                    if ((response.status == 401 || response.status == 403)
                            && showServerResponseOnly(pageUri, response, listener, pages)) {
                        throw new CrawlStop("仅展示了服务器本次实际返回的拒绝/登录提示；抓取已停止。此抓取器不使用浏览器登录态或 Cookie，无法读取需认证内容。");
                    }
                    String detail = "服务器返回 HTTP " + response.status + "：" + pageUri.toASCIIString()
                            + "。选择继续仅表示处理其余已排队页面，不会重试或访问受保护内容。"
                            + "本抓取器不使用浏览器登录态或 Cookie，需认证内容无法抓取；可自行在浏览器打开。";
                    boolean proceed = configuration.askForNonSuccessResponses
                            && awaitDecision(listener, new Decision("非成功 HTTP 响应", detail,
                            "继续其他页面", "停止抓取"));
                    if (!proceed) throw responseStop(pageUri.toASCIIString(), response);
                    continue;
                }
                if (!isSupportedPageType(response.contentType)) {
                    listener.onStatus("跳过非 HTML/纯文本内容：" + pageUri.toASCIIString());
                    continue;
                }
                String signal = PageSignals.blockingReason(response.body);
                if (signal != null) {
                    if (showServerResponseOnly(pageUri, response, listener, pages)) {
                        throw new CrawlStop("按你的选择，仅展示服务器实际返回的静态文字并停止；这不是受保护内容，也未登录或绕过访问控制。需认证内容无法由此抓取器读取。");
                    }
                    throw new CrawlStop("服务器本次实际返回页疑似包含" + signal
                            + "；未读取受保护内容或尝试绕过。你可自行在浏览器手动打开；此抓取器不会复用登录态/Cookie，不能抓取需认证内容。");
                }
                URI resultUri = response.finalUri == null ? pageUri : response.finalUri;
                CrawlerHtmlParser.Document document = response.contentType.toLowerCase(Locale.ROOT).startsWith("text/plain")
                        ? CrawlerHtmlParser.parsePlain(response.body)
                        : CrawlerHtmlParser.parse(response.body, resultUri.toASCIIString(), configuration.queueLimit);
                String title = document.title.trim().isEmpty() ? resultUri.getHost() : document.title.trim();
                String summary = summarize(document.text);
                if (summary.isEmpty()) {
                    listener.onStatus("页面没有可展示的静态文字，已跳过：" + resultUri.toASCIIString());
                    continue;
                }
                Page page = new Page(resultUri.toASCIIString(), title, summary);
                pages.add(page);
                listener.onPage(page);
                for (String link : document.links) {
                    if (configuration.queueLimit > 0 && queued.size() >= configuration.queueLimit) break;
                    URI candidate;
                    try { candidate = parseAndNormalize(link); }
                    catch (IOException | IllegalArgumentException ignored) { continue; }
                    URI candidateOrigin = originOf(candidate);
                    String candidateOriginKey = candidateOrigin.toASCIIString();
                    if (!approvedOrigins.contains(candidateOriginKey)) {
                        if (!configuration.askBeforeCrossSite) continue;
                        boolean approved = awaitDecision(listener, new Decision("跨站目标确认",
                                "发现页面链接指向另一个站点：" + candidateOriginKey
                                        + "\n继续后仅对本次抓取放行此来源；仍要求 HTTPS 默认端口和公网地址。拒绝则跳过该目标。",
                                "本次允许此站点", "跳过此站点"));
                        if (!approved) continue;
                        approvedOrigins.add(candidateOriginKey);
                    }
                    try { targetPolicy.validate(candidate); }
                    catch (IOException | IllegalArgumentException unsafe) { continue; }
                    String normalized = candidate.toASCIIString();
                    enqueue(queue, queued, candidate, false);
                }
            }
            checkCanceled();
            finishMessage = pages.isEmpty()
                    ? "没有找到可显示的静态文本页面。"
                    : "完成：读取 " + pages.size() + " 个页面；本次页面处理上限 "
                    + (pageLimit == 0 ? "不限" : pageLimit) + "。";
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

    private RobotsPolicy loadRobots(URI origin, Set<String> approvedOrigins,
                                    Map<String, RobotsPolicy> robotsByOrigin, Listener listener)
            throws IOException, InterruptedException {
        String key = origin.toASCIIString();
        if (configuration.robotsMode == RobotsMode.IGNORE) {
            RobotsPolicy unrestricted = RobotsPolicy.allowAll();
            robotsByOrigin.put(key, unrestricted);
            listener.onStatus("按你的选择忽略该站 robots.txt：" + key);
            return unrestricted;
        }
        RobotsPolicy robots = cachedRobots(key);
        if (robots != null) {
            listener.onStatus("使用 5 分钟内存缓存的 robots.txt 规则：" + key);
            robotsByOrigin.put(key, robots);
            return robots;
        }
        listener.onStatus("先读取该站 robots.txt：" + key);
        Response response = requestWithChoices(origin.resolve("/robots.txt"), origin, configuration.robotsFileBytes,
                configuration.minimumRequestGapMs, approvedOrigins, listener);
        if (response.location != null) {
            URI destination = parseAndNormalize(response.location);
            URI destinationOrigin = originOf(destination);
            response = requestWithChoices(destination, destinationOrigin, configuration.robotsFileBytes,
                    configuration.minimumRequestGapMs, approvedOrigins, listener);
        }
        if (response.status == 404 || response.status == 410) {
            robots = RobotsPolicy.allowAll();
        } else if (response.status == 200 && isTextContentType(response.contentType)) {
            robots = RobotsPolicy.parse(response.body);
        } else {
            String message = "无法读取 " + key + " 的 robots.txt（HTTP " + response.status
                    + " 或非文本响应）。你可以选择本次继续且不应用未知规则，或停止。";
            boolean proceed = configuration.robotsMode == RobotsMode.ASK_EACH_BLOCKED
                    && awaitDecision(listener, new Decision("robots.txt 响应异常", message,
                    "本次继续", "停止"));
            if (!proceed) throw responseStop(key + "/robots.txt", response);
            robots = RobotsPolicy.allowAll();
        }
        cacheRobots(key, robots);
        robotsByOrigin.put(key, robots);
        return robots;
    }

    private Response requestWithChoices(URI uri, URI origin, long bodyLimit, long requestGap,
                                        Set<String> approvedOrigins, Listener listener)
            throws IOException, InterruptedException {
        while (true) {
            checkCanceled();
            Response response = requestFollowingSameOriginRedirects(uri, origin, bodyLimit, requestGap,
                    approvedOrigins, listener);
            if (response.status != 429 && response.status != 503) return response;
            long serverWaitMs = retryAfterDelayMs(response.retryAfter);
            long retryWaitMs = Math.max(configuration.rateLimitWaitMs, serverWaitMs);
            long waitSeconds = retryWaitMs / 1000L + (retryWaitMs % 1000L == 0 ? 0 : 1);
            String seconds = retryWaitMs == Long.MAX_VALUE ? "很长时间" : waitSeconds + " 秒";
            String retryAfterText = serverWaitMs > configuration.rateLimitWaitMs
                    ? "服务器 Retry-After 要求更长等待，仍将遵守该值。"
                    : "当前设置的额外等待为 " + (configuration.rateLimitWaitMs / 1000L) + " 秒。";
            boolean retry = awaitDecision(listener, new Decision("服务器限流/暂不可用",
                    "收到 HTTP " + response.status + "。每次重试都必须单独确认；" + retryAfterText
                            + "本次至少等待 " + seconds + "（另受你设定的请求间隔影响）。"
                            + "0 秒表示不增加应用等待，不会自动重试；你仍可取消。",
                    "等待后重试一次", "取消抓取"));
            if (!retry) throw responseStop(uri.toASCIIString(), response);
            waitForRetry(retryWaitMs);
        }
    }

    private boolean awaitDecision(Listener listener, Decision prompt) throws InterruptedException, CrawlStop {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Boolean> answer = new AtomicReference<>(false);
        AtomicBoolean answered = new AtomicBoolean();
        try {
            listener.onDecision(prompt, allowed -> {
                if (answered.compareAndSet(false, true)) {
                    answer.set(Boolean.TRUE.equals(allowed));
                    ready.countDown();
                }
            });
        } catch (RuntimeException callbackFailure) {
            return false;
        }
        while (!ready.await(200, TimeUnit.MILLISECONDS)) checkCanceled();
        checkCanceled();
        return answer.get();
    }

    private void waitForRetry(long requestedMs) throws InterruptedException, CrawlStop {
        long waitMs = Math.max(requestedMs, configuration.minimumRequestGapMs);
        long end = waitMs == Long.MAX_VALUE || System.currentTimeMillis() > Long.MAX_VALUE - waitMs
                ? Long.MAX_VALUE : System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < end) {
            checkCanceled();
            checkDeadline();
            Thread.sleep(Math.min(250L, end - System.currentTimeMillis()));
        }
    }

    private boolean showServerResponseOnly(URI pageUri, Response response, Listener listener,
                                           List<Page> pages) throws InterruptedException, CrawlStop {
        boolean hasText = response.body != null && !response.body.trim().isEmpty()
                && isSupportedPageType(response.contentType);
        String reason = PageSignals.blockingReason(response.body);
        String label = response.status == 401 || response.status == 403
                ? "HTTP " + response.status + " 拒绝/认证提示" : reason;
        boolean display = awaitDecision(listener, new Decision("仅显示服务器实际响应",
                "服务器本次实际返回的是 " + label + "。" + (hasText
                        ? "你可只查看该响应中抽取出的静态文字；不会跟进此页链接、提交表单或尝试登录。"
                        : "响应没有可显示的静态文字。")
                        + "这不代表已取得受保护内容。抓取器不使用浏览器 Cookie/登录态；需认证内容无法抓取。",
                hasText ? "仅显示此响应文字并停止" : "停止", "在浏览器手动打开并停止",
                pageUri.toASCIIString()));
        if (!display) return false;
        if (!hasText) return true;
        CrawlerHtmlParser.Document document = response.contentType.toLowerCase(Locale.ROOT)
                .startsWith("text/plain") ? CrawlerHtmlParser.parsePlain(response.body)
                : CrawlerHtmlParser.parse(response.body, pageUri.toASCIIString(), 0);
        String visible = document.text == null || document.text.trim().isEmpty()
                ? "服务器没有返回可显示的静态文字。" : document.text;
        String title = document.title.trim().isEmpty()
                ? "服务器响应（未认证） · " + pageUri.getHost() : document.title;
        Page page = new Page(pageUri.toASCIIString(), title, visible, true);
        pages.add(page);
        listener.onPage(page);
        return true;
    }

    private boolean enqueue(Deque<URI> queue, Set<String> queued, URI candidate, boolean first) {
        String value = candidate.toASCIIString();
        if (queued.contains(value)) return false;
        if (configuration.queueLimit > 0 && queued.size() >= configuration.queueLimit) return false;
        queued.add(value);
        if (first) queue.addFirst(candidate); else queue.addLast(candidate);
        return true;
    }

    private Response requestFollowingSameOriginRedirects(URI first, URI origin, long bodyLimit,
                                                           long requestGap, Set<String> approvedOrigins,
                                                           Listener listener)
            throws IOException, InterruptedException {
        URI current = first;
        long redirects = 0;
        Set<String> visitedRedirects = new HashSet<>();
        visitedRedirects.add(current.toASCIIString());
        for (;;) {
            checkCanceled();
            checkDeadline();
            targetPolicy.validate(current);
            if (!sameOrigin(origin, current)) throw new CrawlStop("请求目标与当前来源不一致；已停止。");
            waitForRequestSlot(requestGap);
            HttpURLConnection connection = opener.open(current.toURL());
            activeConnection = connection;
            connection.setInstanceFollowRedirects(false);
            long remainingMs = deadlineAtMs == 0 ? Math.max(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
                    : deadlineAtMs - System.currentTimeMillis();
            if (remainingMs <= 0) throw new CrawlStop(timeoutMessage());
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
                String contentType = connection.getContentType();
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || (configuration.redirectLimit > 0
                            && redirects >= configuration.redirectLimit)) {
                        throw new CrawlStop("重定向缺少有效地址或达到你设置的跳数上限 "
                                + (configuration.redirectLimit == 0 ? "不限" : configuration.redirectLimit)
                                + "；已停止。");
                    }
                    if (retryAfter != null && !retryAfter.trim().isEmpty()) {
                        throw new CrawlStop("重定向响应带有 Retry-After；为避免过早跟随，已停止。"
                                + retryAfterHint(retryAfter));
                    }
                    URI next;
                    try { next = parseAndNormalize(current.resolve(location).toASCIIString()); }
                    catch (IOException | IllegalArgumentException invalid) {
                        throw new CrawlStop("重定向目标无效；已停止。");
                    }
                    URI nextOrigin = originOf(next);
                    if (!sameOrigin(origin, next)) {
                        String key = nextOrigin.toASCIIString();
                        if (!approvedOrigins.contains(key)) {
                            boolean allow = configuration.askBeforeCrossSite
                                    && awaitDecision(listener, new Decision("跨站重定向确认",
                                    "服务器将请求重定向到：" + key + "\n这会使抓取器向该站发送新请求。"
                                            + "仍要求 HTTPS 默认端口与公网地址；拒绝则不访问目标。",
                                    "本次允许此站点", "停止跟随"));
                            if (!allow) throw new CrawlStop("用户未批准跨站重定向；目标未被请求：" + key);
                            approvedOrigins.add(key);
                        }
                        targetPolicy.validate(next);
                        return new Response(status, contentType, "", next.toASCIIString(), retryAfter, current);
                    }
                    targetPolicy.validate(next);
                    if (!visitedRedirects.add(next.toASCIIString())) {
                        throw new CrawlStop("检测到重定向循环；为避免重复请求已停止。");
                    }
                    redirects++;
                    current = next;
                    continue;
                }
                String body = "";
                if (status == 200) {
                    long length = connection.getContentLengthLong();
                    if (bodyLimit > 0 && length > bodyLimit) {
                        throw new CrawlStop("响应超过 " + bodyLimit + " 字节上限；已停止。");
                    }
                    try (InputStream input = connection.getInputStream()) {
                        body = readBoundedText(input, bodyLimit, charsetFrom(contentType), deadlineAtMs);
                    }
                } else if (status == 401 || status == 403) {
                    InputStream errorBody = connection.getErrorStream();
                    if (errorBody != null) {
                        long length = connection.getContentLengthLong();
                        if (bodyLimit > 0 && length > bodyLimit) {
                            throw new CrawlStop("拒绝响应正文超过所选字节上限；已停止。");
                        }
                        try (InputStream input = errorBody) {
                            body = readBoundedText(input, bodyLimit, charsetFrom(contentType), deadlineAtMs);
                        }
                    }
                }
                return new Response(status, contentType, body, null, retryAfter, current);
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
        long gap = Math.max(0L, requestedGapMs);
        long remaining = lastRequestStartedMs == 0 ? 0 : gap - (System.currentTimeMillis() - lastRequestStartedMs);
        while (remaining > 0) {
            checkCanceled();
            checkDeadline();
            Thread.sleep(Math.min(remaining, 200L));
            remaining = gap - (System.currentTimeMillis() - lastRequestStartedMs);
        }
    }

    private void checkDeadline() throws CrawlStop {
        if (deadlineAtMs > 0 && System.currentTimeMillis() >= deadlineAtMs) {
            throw new CrawlStop(timeoutMessage());
        }
    }

    private String timeoutMessage() {
        return configuration.totalDurationMs == 0 ? "抓取已取消。"
                : "已达到你设置的抓取总时长上限（" + (configuration.totalDurationMs / 1000L) + " 秒）；已停止。";
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

    private long retryAfterDelayMs(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds >= 0) return seconds > Long.MAX_VALUE / 1000L
                    ? Long.MAX_VALUE : seconds * 1000L;
        } catch (NumberFormatException ignored) { }
        try {
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(trimmed, position);
            if (date != null && position.getIndex() == trimmed.length()) {
                return Math.max(0L, date.getTime() - System.currentTimeMillis());
            }
        } catch (IllegalArgumentException ignored) { }
        return 0L;
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

    private static String readBoundedText(InputStream input, long limit, Charset charset, long deadlineAtMs)
            throws IOException, CrawlStop {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(limit, 16_384L));
        byte[] buffer = new byte[8_192];
        int read;
        while (true) {
            if (deadlineAtMs > 0 && System.currentTimeMillis() >= deadlineAtMs) {
                throw new CrawlStop("抓取超过你设置的总时长；已停止。");
            }
            read = input.read(buffer);
            if (read < 0) break;
            if (limit > 0 && output.size() + (long) read > limit) {
                throw new CrawlStop("响应正文超过你设置的字节上限；已停止。");
            }
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

    static void validatePublicHttpsTarget(URI uri) throws IOException {
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
