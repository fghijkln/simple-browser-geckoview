package com.cue.simplebrowser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline-only safety and behavior checks; every HTTP request targets a loopback mock. */
public final class ControlledCrawlerSmokeTest {
    private ControlledCrawlerSmokeTest() { }

    public static void main(String[] args) throws Exception {
        testStaticSameOriginCrawlAndRobots();
        testPageCountAndInputBounds();
        testRequestSpacing();
        testShortRobotsCache();
        testRobotsDenialAndHttpRefusal();
        testRateLimitRetryAfterAndNoRetry();
        testChallengeAndAccessWalls();
        testRedirectScopeAndBodyBounds();
        testCancellation();
        System.out.println("PASS: loopback-only controlled crawler mock; robots, same-origin, no-cookie, denial/challenge/429, redirect and byte bounds, and cancellation");
    }

    private static void testStaticSameOriginCrawlAndRobots() throws Exception {
        MockSite site = new MockSite();
        site.put("/robots.txt", 200, "text/plain; charset=utf-8",
                "User-agent: *\nDisallow: /blocked\nDisallow: /private\nAllow: /private/open\nCrawl-delay: 0\n");
        site.put("/seed", 200, "text/html; charset=utf-8",
                "<html><head><title>Seed &amp; source</title><script>hidden JavaScript link <a href='/evil'>x</a></script>"
                        + "<style>invisible</style></head><body><h1>Hello &lt;world&gt;</h1><p>Static first source.</p>"
                        + "<a href='/child'>child</a><a href='/blocked'>blocked</a>"
                        + "<a href='https://external.invalid/secret'>outside</a><a href='javascript:alert(1)'>bad</a></body></html>");
        site.put("/child", 200, "text/plain; charset=utf-8", "Child page has readable plain text.");
        site.put("/plain", 200, "text/plain; charset=utf-8", "Literal <tag> and &amp; stay plain.");
        site.put("/blocked", 200, "text/plain", "Should never be downloaded");
        site.put("/private/open", 200, "text/plain", "Longest matching allow rule");
        try (MockServer server = new MockServer(site)) {
            Capture capture = crawl(server, "/seed");
            check(!capture.finish.canceled && !capture.finish.stopped, "normal crawl should complete");
            check(capture.finish.pages.size() == 2, "only the seed and same-origin child should be returned");
            check(capture.finish.pages.get(0).title.equals("Seed & source"), "HTML title entities should decode");
            check(capture.finish.pages.get(0).summary.contains("Static first source"), "visible static text should be summarized");
            check(!capture.finish.pages.get(0).summary.contains("hidden JavaScript"), "script text must not enter summaries");
            check(site.count("/blocked") == 0 && site.count("/evil") == 0, "robots-disallowed and script-only links must not be fetched");
            check(site.count("/robots.txt") == 1, "robots file must be fetched before the seed page");
            for (Request request : site.requests) {
                String cookie = request.headers.get("Cookie");
                check(cookie == null || cookie.isEmpty(), "no Cookie value may be sent to the crawler mock");
                check(request.headers.get("Authorization") == null, "no Authorization header may be sent");
                check(ControlledCrawler.USER_AGENT.equals(request.headers.get("User-agent")), "crawler identifies itself");
            }
            check(capture.finish.pages.get(1).url.endsWith("/child"), "source URL must be preserved in the result");
            Capture plain = crawl(server, "/plain");
            check(plain.finish.pages.size() == 1 && plain.finish.pages.get(0).summary.contains("<tag> and &amp;"),
                    "text/plain parsing must preserve literal markup-looking text and entities");
        }

        MockSite allowSite = new MockSite();
        allowSite.put("/robots.txt", 200, "text/plain", "User-agent: *\nDisallow: /private\nAllow: /private/open\n");
        allowSite.put("/private/open", 200, "text/plain", "Allowed by the more-specific rule.");
        try (MockServer server = new MockServer(allowSite)) {
            Capture capture = crawl(server, "/private/open");
            check(capture.finish.pages.size() == 1, "more-specific Allow must override Disallow");
        }
    }


    private static void testPageCountAndInputBounds() throws Exception {
        MockSite site = new MockSite();
        site.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        StringBuilder index = new StringBuilder("<html><body>");
        for (int i = 0; i < 10; i++) {
            site.put("/page" + i, 200, "text/plain", "static page " + i);
            index.append("<a href='/page").append(i).append("'>page</a>");
        }
        index.append("</body></html>");
        site.put("/index", 200, "text/html", index.toString());
        try (MockServer server = new MockServer(site)) {
            Capture capture = crawl(server, "/index");
            check(capture.finish.pages.size() == ControlledCrawler.MAX_PAGES,
                    "page result count must respect the fixed MAX_PAGES bound");
            check(site.requests.size() == ControlledCrawler.MAX_PAGES + 1,
                    "including robots.txt, only MAX_PAGES page documents may be requested");
        }
        Capture privateAddress = new Capture();
        new ControlledCrawler().crawl("https://127.0.0.1/", privateAddress);
        check(privateAddress.finish.stopped && privateAddress.finish.message.contains("IP 字面地址"),
                "the production policy must reject local IP-literal input before network access");
        check(privateAddress.finish.pages.isEmpty(), "rejected input must produce no pages");
    }

    private static void testRequestSpacing() throws Exception {
        MockSite site = new MockSite();
        site.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        site.put("/timed", 200, "text/plain", "one short page");
        try (MockServer server = new MockServer(site)) {
            ControlledCrawler crawler = new ControlledCrawler(url -> (java.net.HttpURLConnection) url.openConnection(), uri -> {
                if (!"http".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                        || uri.getPort() != server.port()) throw new IOException("mock policy rejected non-loopback URL");
            }, 100L);
            Capture capture = new Capture();
            crawler.crawl(server.url("/timed"), capture);
            check(capture.finish.pages.size() == 1, "delayed request should complete");
            check(site.requests.size() == 2, "timing check expects robots and one page request");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(site.requests.get(1).receivedAtNanos
                    - site.requests.get(0).receivedAtNanos);
            check(elapsedMs >= 85, "requests should honor a configured minimum interval; measured " + elapsedMs + " ms");
            check(ControlledCrawler.MIN_REQUEST_GAP_MS == 2_000L, "production minimum request gap must remain two seconds");
        }
    }

    private static void testShortRobotsCache() throws Exception {
        MockSite site = new MockSite();
        site.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        site.put("/cached", 200, "text/plain", "fresh page body");
        try (MockServer server = new MockServer(site)) {
            ControlledCrawler.TargetPolicy policy = uri -> {
                if (!"http".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                        || uri.getPort() != server.port()) throw new IOException("mock policy rejected non-loopback URL");
            };
            Capture first = new Capture();
            new ControlledCrawler(url -> (java.net.HttpURLConnection) url.openConnection(), policy, 0L, true)
                    .crawl(server.url("/cached"), first);
            Capture second = new Capture();
            new ControlledCrawler(url -> (java.net.HttpURLConnection) url.openConnection(), policy, 0L, true)
                    .crawl(server.url("/cached"), second);
            check(first.finish.pages.size() == 1 && second.finish.pages.size() == 1,
                    "robots cache must not cache or hide fetched page content");
            check(site.count("/robots.txt") == 1, "successful robots rules should be reused during the short memory TTL");
            check(site.count("/cached") == 2, "page bodies must be fetched fresh on each user-started run");
            check(ControlledCrawler.ROBOTS_CACHE_TTL_MS == 300_000L, "robots rules cache TTL must remain five minutes");
        }
    }

    private static void testRobotsDenialAndHttpRefusal() throws Exception {
        MockSite denied = new MockSite();
        denied.put("/robots.txt", 200, "text/plain", "User-agent: *\nDisallow: /secret\n");
        denied.put("/secret", 200, "text/plain", "not fetched");
        try (MockServer server = new MockServer(denied)) {
            Capture capture = crawl(server, "/secret");
            check(capture.finish.stopped && capture.finish.message.contains("robots.txt"), "disallowed seed must stop");
            check(denied.count("/secret") == 0, "robots-disallowed seed must not be requested");
        }

        MockSite forbidden = new MockSite();
        forbidden.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        forbidden.put("/nope", 403, "text/plain", "Forbidden");
        try (MockServer server = new MockServer(forbidden)) {
            Capture capture = crawl(server, "/nope");
            check(capture.finish.stopped && capture.finish.message.contains("HTTP 403"), "403 must stop without evasion");
            check(forbidden.count("/nope") == 1, "403 is not retried");
        }

        MockSite robots403 = new MockSite();
        robots403.put("/robots.txt", 403, "text/plain", "");
        try (MockServer server = new MockServer(robots403)) {
            Capture capture = crawl(server, "/anything");
            check(capture.finish.stopped && robots403.count("/anything") == 0, "robots 403 must fail closed");
        }
    }

    private static void testRateLimitRetryAfterAndNoRetry() throws Exception {
        MockSite site = new MockSite();
        site.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        site.put("/limited", 429, "text/plain", "slow down", "120");
        try (MockServer server = new MockServer(site)) {
            Capture capture = crawl(server, "/limited");
            check(capture.finish.stopped && capture.finish.message.contains("429"), "429 must stop");
            check(capture.finish.message.contains("120 秒"), "Retry-After seconds should be shown to the user");
            check(site.count("/limited") == 1, "429 must not be automatically retried");
        }

        MockSite unavailable = new MockSite();
        unavailable.put("/robots.txt", 503, "text/plain", "", "30");
        try (MockServer server = new MockServer(unavailable)) {
            Capture capture = crawl(server, "/home");
            check(capture.finish.stopped && capture.finish.message.contains("503"), "robots 503 must stop");
            check(unavailable.count("/home") == 0, "no page fetch follows robots server failure");
        }
    }

    private static void testChallengeAndAccessWalls() throws Exception {
        checkBlockedBody("<html><title>Just a moment</title><div id='challenge-platform'>Verify you are human</div></html>", "反机器人挑战");
        checkBlockedBody("<html><main><div class='paywall'>Subscribe to continue reading</div></main></html>", "付费墙");
        checkBlockedBody("<html><p>Please sign in to continue reading this member-only page.</p></html>", "登录墙");
    }

    private static void checkBlockedBody(String body, String expected) throws Exception {
        MockSite site = new MockSite();
        site.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        site.put("/challenge", 200, "text/html", body);
        try (MockServer server = new MockServer(site)) {
            Capture capture = crawl(server, "/challenge");
            check(capture.finish.stopped && capture.finish.message.contains(expected), "challenge/access wall heuristic should stop: " + expected);
            check(capture.finish.pages.isEmpty(), "challenge/access-wall page should not be returned as a result");
        }
    }

    private static void testRedirectScopeAndBodyBounds() throws Exception {
        MockSite redirect = new MockSite();
        redirect.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        redirect.put("/redirect", 302, "text/plain", "", "", "https://example.org/outside");
        try (MockServer server = new MockServer(redirect)) {
            Capture capture = crawl(server, "/redirect");
            check(capture.finish.stopped && capture.finish.message.contains("跨站重定向"), "cross-origin redirect must stop before contacting destination");
            check(redirect.count("/redirect") == 1, "redirect source requested once");
        }

        MockSite deferred = new MockSite();
        deferred.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        deferred.put("/deferred", 302, "text/plain", "", "30", "/destination");
        deferred.put("/destination", 200, "text/plain", "must not be fetched early");
        try (MockServer server = new MockServer(deferred)) {
            Capture capture = crawl(server, "/deferred");
            check(capture.finish.stopped && capture.finish.message.contains("Retry-After"),
                    "redirect Retry-After should stop before following the target");
            check(deferred.count("/destination") == 0, "redirect target must not be requested before Retry-After");
        }

        MockSite largeRobots = new MockSite();
        largeRobots.put("/robots.txt", 200, "text/plain", "x".repeat(ControlledCrawler.MAX_ROBOTS_BYTES + 1));
        largeRobots.put("/seed", 200, "text/plain", "not reached");
        try (MockServer server = new MockServer(largeRobots)) {
            Capture capture = crawl(server, "/seed");
            check(capture.finish.stopped && capture.finish.message.contains("上限"),
                    "robots.txt above 512 KiB should be refused within the documented parse limit");
            check(largeRobots.count("/seed") == 0, "oversized robots response must stop before page fetch");
        }

        MockSite large = new MockSite();
        large.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        large.put("/large", 200, "text/html", "<p>" + "x".repeat(ControlledCrawler.MAX_BODY_BYTES + 10) + "</p>");
        try (MockServer server = new MockServer(large)) {
            Capture capture = crawl(server, "/large");
            check(capture.finish.stopped && capture.finish.message.contains("上限"), "oversized body should stop");
            check(capture.finish.pages.isEmpty(), "oversized body must not produce a result");
        }
    }

    private static void testCancellation() throws Exception {
        MockSite site = new MockSite();
        site.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        site.put("/slow", 200, "text/plain", "This response is deliberately delayed.", 5_000);
        try (MockServer server = new MockServer(site)) {
            CountDownLatch slowStarted = new CountDownLatch(1);
            server.site.started = slowStarted;
            ControlledCrawler crawler = crawler(server);
            CountDownLatch finished = new CountDownLatch(1);
            List<ControlledCrawler.Finish> finish = Collections.synchronizedList(new ArrayList<>());
            Thread thread = new Thread(() -> crawler.crawl(server.url("/slow"), new ControlledCrawler.Listener() {
                @Override public void onStatus(String status) { }
                @Override public void onPage(ControlledCrawler.Page page) { }
                @Override public void onFinished(ControlledCrawler.Finish result) {
                    finish.add(result);
                    finished.countDown();
                }
            }), "crawler-cancel-test");
            thread.start();
            check(slowStarted.await(3, TimeUnit.SECONDS), "slow loopback endpoint should receive the request");
            crawler.cancel();
            check(finished.await(3, TimeUnit.SECONDS), "cancel should close the active mock request");
            thread.join(1_000);
            check(!thread.isAlive(), "crawler worker should stop after cancel");
            check(finish.size() == 1 && finish.get(0).canceled, "completion should identify cancellation");
        }
    }

    private static Capture crawl(MockServer server, String path) throws Exception {
        ControlledCrawler crawler = crawler(server);
        Capture capture = new Capture();
        crawler.crawl(server.url(path), capture);
        check(capture.finish != null, "listener must receive final status");
        return capture;
    }

    private static ControlledCrawler crawler(MockServer server) {
        return new ControlledCrawler(url -> (java.net.HttpURLConnection) url.openConnection(), uri -> {
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() != server.port()) throw new IOException("mock policy rejected non-loopback URL");
        }, 0L);
    }

    private static final class Capture implements ControlledCrawler.Listener {
        final List<ControlledCrawler.Page> pages = new ArrayList<>();
        ControlledCrawler.Finish finish;
        @Override public void onStatus(String status) { }
        @Override public void onPage(ControlledCrawler.Page page) { pages.add(page); }
        @Override public void onFinished(ControlledCrawler.Finish result) { finish = result; }
    }

    private static final class Request {
        final String path;
        final Map<String, String> headers;
        final long receivedAtNanos = System.nanoTime();
        Request(String path, Map<String, String> headers) { this.path = path; this.headers = headers; }
    }

    private static final class Route {
        final int status;
        final String contentType;
        final String body;
        final String retryAfter;
        final String location;
        final int delayMs;
        Route(int status, String type, String body, String retryAfter, String location, int delayMs) {
            this.status = status; this.contentType = type; this.body = body;
            this.retryAfter = retryAfter; this.location = location; this.delayMs = delayMs;
        }
    }

    private static final class MockSite {
        final Map<String, Route> routes = new ConcurrentHashMap<>();
        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch started;
        void put(String path, int status, String type, String body) { put(path, status, type, body, "", "", 0); }
        void put(String path, int status, String type, String body, String retryAfter) { put(path, status, type, body, retryAfter, "", 0); }
        void put(String path, int status, String type, String body, int delayMs) { put(path, status, type, body, "", "", delayMs); }
        void put(String path, int status, String type, String body, String retryAfter, String location) {
            put(path, status, type, body, retryAfter, location, 0);
        }
        void put(String path, int status, String type, String body, String retryAfter, String location, int delayMs) {
            routes.put(path, new Route(status, type, body, retryAfter, location, delayMs));
        }
        int count(String path) { return (int) requests.stream().filter(request -> request.path.equals(path)).count(); }
    }

    private static final class MockServer implements AutoCloseable {
        final MockSite site;
        final HttpServer server;
        MockServer(MockSite site) throws IOException {
            this.site = site;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }
        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                if (!entry.getValue().isEmpty()) headers.put(entry.getKey(), entry.getValue().get(0));
            }
            site.requests.add(new Request(path, headers));
            if (site.started != null && path.equals("/slow")) site.started.countDown();
            Route route = site.routes.get(path);
            if (route == null) route = new Route(404, "text/plain", "not found", "", "", 0);
            if (!route.location.isEmpty()) exchange.getResponseHeaders().set("Location", route.location);
            if (!route.retryAfter.isEmpty()) exchange.getResponseHeaders().set("Retry-After", route.retryAfter);
            if (route.contentType != null) exchange.getResponseHeaders().set("Content-Type", route.contentType);
            byte[] bytes = route.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(route.status, route.status == 302 || route.status == 301 || route.status == 303
                    || route.status == 307 || route.status == 308 ? -1 : bytes.length);
            if (route.delayMs > 0) {
                try { Thread.sleep(route.delayMs); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            try { if (bytes.length > 0) exchange.getResponseBody().write(bytes); }
            catch (IOException disconnected) { /* Expected when the crawler disconnects after cancel. */ }
            finally { exchange.close(); }
        }
        String url(String path) { return "http://127.0.0.1:" + port() + path; }
        int port() { return server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
