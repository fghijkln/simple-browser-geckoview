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
        testUserConfigurablePoliciesAndUnlimited();
        testUnlimitedPagesQueueAndParser();
        testUserChoicesForRobotsCrossSiteAndResponses();
        testConfirmedRateLimitWaitAndCancel();
        testZeroApplicationWaitStillRequiresEachConfirmation();
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
            Capture capture = crawl(server, "/index", new ControlledCrawler.Configuration(
                    4, 10_000L, 64 * 1024L, 0L, ControlledCrawler.RobotsMode.RESPECT, false, true));
            check(capture.finish.pages.size() == 4,
                    "user-selected page count must be applied instead of the old fixed default");
            check(site.requests.size() == 5,
                    "including robots.txt, only the configured number of page documents may be requested");
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
            check(ControlledCrawler.MIN_REQUEST_GAP_MS == 1_000L, "production default request gap must remain one second");
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

    private static void testUserConfigurablePoliciesAndUnlimited() {
        ControlledCrawler.Configuration defaults = ControlledCrawler.Configuration.defaults();
        check(defaults.pageLimit == 100 && defaults.queueLimit == 10_000
                        && defaults.totalDurationMs == 1_800_000L
                        && defaults.perPageBytes == 16L * 1_048_576L
                        && defaults.minimumRequestGapMs == 1_000L
                        && defaults.rateLimitWaitMs == 30_000L
                        && defaults.redirectLimit == 3
                        && defaults.robotsFileBytes == 512L * 1024L,
                "crawler defaults must be raised to bounded but reasonably high values");
        check(defaults.robotsMode == ControlledCrawler.RobotsMode.RESPECT
                        && !defaults.askBeforeCrossSite && defaults.askForNonSuccessResponses,
                "safe robots/cross-site defaults and non-success prompts remain explicit");
        ControlledCrawler.Configuration unlimited = new ControlledCrawler.Configuration(
                0, 0, 0L, 0L, 0L, 0L, 0, 0L,
                ControlledCrawler.RobotsMode.IGNORE, true, true);
        check(unlimited.pageLimit == 0 && unlimited.queueLimit == 0 && unlimited.perPageBytes == 0
                        && unlimited.robotsFileBytes == 0 && unlimited.redirectLimit == 0
                        && unlimited.rateLimitWaitMs == 0,
                "0 removes application caps for pages, queue, page/robots bytes, redirects, and added retry wait");
        check(unlimited.totalDurationMs == 0 && unlimited.minimumRequestGapMs == 0,
                "0 duration and delay settings are represented as user-selected unlimited/no-extra-delay");
        boolean rejected = false;
        try {
            new ControlledCrawler.Configuration(-1, 0L, 0L, 0L,
                    ControlledCrawler.RobotsMode.RESPECT, false, true);
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "negative configuration values are rejected");
    }

    private static void testUnlimitedPagesQueueAndParser() throws Exception {
        MockSite site = new MockSite();
        StringBuilder index = new StringBuilder("<html><body>");
        for (int i = 0; i < 105; i++) {
            site.put("/p" + i, 200, "text/plain", "page " + i);
            index.append("<a href='/p").append(i).append("'>page ").append(i).append("</a>");
        }
        index.append("</body></html>");
        site.put("/index", 200, "text/html", index.toString());
        try (MockServer server = new MockServer(site)) {
            ControlledCrawler.Configuration unlimited = new ControlledCrawler.Configuration(
                    0, 0, 0L, 0L, 0L, 0L, 0, 0L,
                    ControlledCrawler.RobotsMode.IGNORE, false, true);
            Capture capture = crawl(server, "/index", unlimited);
            check(!capture.finish.stopped && capture.finish.pages.size() == 106,
                    "0 page and queue limits must allow crawling beyond the old 100-page default");
            check(site.count("/p104") == 1, "unlimited queue must retain URLs discovered after the default page count");
        }

        StringBuilder longHtml = new StringBuilder("<html><body>");
        for (int i = 0; i < 900; i++) {
            longHtml.append("<p>visible text before every link which exceeds the display summary threshold</p>")
                    .append("<a href='/late").append(i).append("'>late</a>");
        }
        longHtml.append("</body></html>");
        CrawlerHtmlParser.Document parsed = CrawlerHtmlParser.parse(longHtml.toString(),
                "https://public.example/index", 0);
        check(parsed.text.length() <= 12_000 && parsed.links.size() == 900
                        && parsed.links.get(899).endsWith("/late899"),
                "summary display remains short while the parser scans links beyond its text-summary boundary");
    }

    private static void testUserChoicesForRobotsCrossSiteAndResponses() throws Exception {
        MockSite ignoredSite = new MockSite();
        ignoredSite.put("/seed", 200, "text/plain", "robots ignored by explicit user selection");
        try (MockServer server = new MockServer(ignoredSite)) {
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    1, 10_000L, 1024L, 0L, ControlledCrawler.RobotsMode.IGNORE, false, true);
            Capture capture = crawl(server, "/seed", config);
            check(capture.finish.pages.size() == 1 && ignoredSite.count("/robots.txt") == 0,
                    "IGNORE mode must skip robots.txt network request only when explicitly selected");
        }

        MockSite askedSite = new MockSite();
        askedSite.put("/robots.txt", 200, "text/plain", "User-agent: *\nDisallow: /secret\n");
        askedSite.put("/secret", 200, "text/plain", "chosen after user confirmation");
        try (MockServer server = new MockServer(askedSite)) {
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    1, 10_000L, 1024L, 0L, ControlledCrawler.RobotsMode.ASK_EACH_BLOCKED, false, true);
            Capture capture = crawl(server, "/secret", config, true);
            check(capture.finish.pages.size() == 1 && askedSite.count("/secret") == 1,
                    "ASK mode proceeds to a robots-disallowed path only after an explicit per-path approval");
            check(capture.decisions.size() == 1 && capture.decisions.get(0).title.contains("robots.txt"),
                    "robots exception is described in a dedicated user decision");
        }

        MockSite sourceSite = new MockSite();
        sourceSite.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        sourceSite.put("/start", 200, "text/html", "<p>source text</p><a href='DEST/child'>cross site</a>");
        MockSite destinationSite = new MockSite();
        destinationSite.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        destinationSite.put("/child", 200, "text/plain", "destination text");
        try (MockServer destination = new MockServer(destinationSite);
             MockServer source = new MockServer(sourceSite)) {
            sourceSite.put("/start", 200, "text/html", "<p>source text</p><a href='http://127.0.0.1:"
                    + destination.port() + "/child'>cross site</a>");
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    2, 10_000L, 1024L, 0L, ControlledCrawler.RobotsMode.RESPECT, true, true);
            ControlledCrawler crawler = new ControlledCrawler(url ->
                    (java.net.HttpURLConnection) url.openConnection(), uri -> {
                if (!"http".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()))
                    throw new IOException("mock policy rejected non-loopback URL");
            }, config);
            Capture capture = new Capture(true);
            crawler.crawl(source.url("/start"), capture);
            check(capture.finish.pages.size() == 2 && destinationSite.count("/child") == 1,
                    "cross-site link is crawled only after the explicit site approval");
            check(capture.decisions.stream().anyMatch(item -> item.title.contains("跨站")),
                    "new origin requires a clear per-origin choice");
        }

        MockSite rejectedCrossSite = new MockSite();
        rejectedCrossSite.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        try (MockServer destination = new MockServer(destinationSite);
             MockServer source = new MockServer(rejectedCrossSite)) {
            rejectedCrossSite.put("/start", 200, "text/html", "<p>source text</p><a href='http://127.0.0.1:"
                    + destination.port() + "/child'>cross site</a>");
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    2, 10_000L, 1024L, 0L, ControlledCrawler.RobotsMode.RESPECT, true, true);
            ControlledCrawler crawler = new ControlledCrawler(url ->
                    (java.net.HttpURLConnection) url.openConnection(), uri -> { }, config);
            Capture capture = new Capture(false);
            crawler.crawl(source.url("/start"), capture);
            check(capture.finish.pages.size() == 1 && destinationSite.count("/child") == 1,
                    "declined cross-site destination is not requested");
        }

        MockSite responseSite = new MockSite();
        responseSite.put("/seed", 200, "text/html", "<p>seed readable text</p><a href='/forbidden'>next</a>");
        responseSite.put("/forbidden", 404, "text/plain", "not found");
        try (MockServer server = new MockServer(responseSite)) {
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    2, 10_000L, 1024L, 0L, ControlledCrawler.RobotsMode.IGNORE, false, true);
            Capture capture = crawl(server, "/seed", config, true);
            check(!capture.finish.stopped && capture.finish.pages.size() == 1,
                    "confirmed non-success choice continues the remaining queue without retrying the rejected page");
            check(responseSite.count("/forbidden") == 1
                            && capture.decisions.stream().anyMatch(item -> item.title.contains("非成功 HTTP")),
                    "normal 403 response gets one explicit continue-or-stop decision");
        }
    }

    private static void testConfirmedRateLimitWaitAndCancel() throws Exception {
        MockSite site = new MockSite();
        site.put("/limited", 429, "text/plain", "slow down", "0");
        try (MockServer server = new MockServer(site)) {
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    1, 0L, 1024L, 0L, ControlledCrawler.RobotsMode.IGNORE, false, true);
            ControlledCrawler crawler = crawler(server, config);
            CountDownLatch decisionSeen = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            List<ControlledCrawler.Finish> finishes = Collections.synchronizedList(new ArrayList<>());
            List<ControlledCrawler.Decision> decisions = Collections.synchronizedList(new ArrayList<>());
            Thread thread = new Thread(() -> crawler.crawl(server.url("/limited"), new ControlledCrawler.Listener() {
                @Override public void onStatus(String status) { }
                @Override public void onPage(ControlledCrawler.Page page) { }
                @Override public void onFinished(ControlledCrawler.Finish result) {
                    finishes.add(result);
                    finished.countDown();
                }
                @Override public void onDecision(ControlledCrawler.Decision prompt, java.util.function.Consumer<Boolean> answer) {
                    decisions.add(prompt);
                    answer.accept(true);
                    decisionSeen.countDown();
                }
            }), "crawler-429-user-confirmation");
            thread.start();
            check(decisionSeen.await(3, TimeUnit.SECONDS), "429 must ask for a per-request decision");
            check(decisions.get(0).message.contains("30 秒")
                            && decisions.get(0).message.contains("单独确认"),
                    "confirmed retry notice states the minimum long wait and per-request requirement");
            Thread.sleep(100L);
            crawler.cancel();
            check(finished.await(3, TimeUnit.SECONDS), "user can cancel during the long rate-limit backoff");
            thread.join(1_000L);
            check(finishes.size() == 1 && finishes.get(0).canceled,
                    "cancel during Retry-After wait finishes as canceled");
            check(site.count("/limited") == 1, "no rapid repeat request is issued during confirmed 30-second backoff");
        }
    }

    private static void testZeroApplicationWaitStillRequiresEachConfirmation() throws Exception {
        MockSite site = new MockSite();
        site.put("/limited", 429, "text/plain", "slow down", "0");
        try (MockServer server = new MockServer(site)) {
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    1, 0, 0L, 0L, 0L, 0L, 3, 512L * 1024L,
                    ControlledCrawler.RobotsMode.IGNORE, false, true);
            ControlledCrawler crawler = crawler(server, config);
            List<ControlledCrawler.Decision> decisions = new ArrayList<>();
            List<ControlledCrawler.Finish> finishes = new ArrayList<>();
            crawler.crawl(server.url("/limited"), new ControlledCrawler.Listener() {
                @Override public void onStatus(String status) { }
                @Override public void onPage(ControlledCrawler.Page page) { }
                @Override public void onFinished(ControlledCrawler.Finish result) { finishes.add(result); }
                @Override public void onDecision(ControlledCrawler.Decision prompt,
                                                 java.util.function.Consumer<Boolean> answer) {
                    decisions.add(prompt);
                    answer.accept(decisions.size() == 1);
                }
            });
            check(decisions.size() == 2 && site.count("/limited") == 2,
                    "a repeated 429 is requested only after two separate user decisions");
            check(decisions.get(0).message.contains("0 秒")
                            && decisions.get(0).message.contains("单独确认"),
                    "zero application-added wait is explicit but still requires confirmation");
            check(finishes.size() == 1 && finishes.get(0).stopped,
                    "declining the second retry stops the crawl without another request");
        }
    }

    private static void testChallengeAndAccessWalls() throws Exception {
        checkBlockedBody("<html><title>Just a moment</title><div id='challenge-platform'>Verify you are human</div></html>", "反机器人挑战");
        checkBlockedBody("<html><main><div class='paywall'>Subscribe to continue reading</div></main></html>", "付费墙");
        checkBlockedBody("<html><p>Please sign in to continue reading this member-only page.</p></html>", "登录墙");
        testUserMayOnlyDisplayActualChallengeResponse();
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

    private static void testUserMayOnlyDisplayActualChallengeResponse() throws Exception {
        String body = "<html><body><h1>Verify you are human</h1><p>Server says challenge required.</p>"
                + "<a href='/protected'>protected continuation</a></body></html>";
        MockSite challenge = new MockSite();
        challenge.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        challenge.put("/challenge", 200, "text/html", body);
        challenge.put("/protected", 200, "text/plain", "must not be fetched");
        try (MockServer server = new MockServer(challenge)) {
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    1, 0L, 0L, 0L, ControlledCrawler.RobotsMode.RESPECT, false, true);
            Capture capture = crawl(server, "/challenge", config, true);
            check(capture.finish.stopped && capture.finish.pages.size() == 1
                            && capture.finish.pages.get(0).serverResponseOnly
                            && capture.finish.pages.get(0).summary.contains("Server says challenge required"),
                    "user choice may display only static text actually returned by a challenge page");
            check(challenge.count("/protected") == 0,
                    "displaying a CAPTCHA response does not follow links or claim protected content");
        }

        MockSite forbidden = new MockSite();
        forbidden.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        forbidden.put("/private", 403, "text/html", "<html><body><h1>Forbidden</h1>"
                + "<p>Sign in is required.</p><a href='/member'>member body</a></body></html>");
        forbidden.put("/member", 200, "text/plain", "must not be fetched");
        try (MockServer server = new MockServer(forbidden)) {
            ControlledCrawler.Configuration config = new ControlledCrawler.Configuration(
                    1, 0L, 0L, 0L, ControlledCrawler.RobotsMode.RESPECT, false, true);
            Capture capture = crawl(server, "/private", config, true);
            check(capture.finish.stopped && capture.finish.pages.size() == 1
                            && capture.finish.pages.get(0).serverResponseOnly
                            && capture.finish.pages.get(0).summary.contains("Sign in is required"),
                    "403 preview is labeled as the actual unauthenticated server response");
            check(forbidden.count("/member") == 0,
                    "the crawler does not follow a link from an HTTP 403 response preview");
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

        MockSite chain = new MockSite();
        chain.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        for (int i = 0; i < 5; i++) chain.put("/r" + i, 302, "text/plain", "", "", "/r" + (i + 1));
        chain.put("/r5", 200, "text/plain", "redirected after five same-origin hops");
        try (MockServer server = new MockServer(chain)) {
            Capture finite = crawl(server, "/r0");
            check(finite.finish.stopped && finite.finish.message.contains("跳数上限"),
                    "default finite redirect setting is honored");
            ControlledCrawler.Configuration unlimitedRedirects = new ControlledCrawler.Configuration(
                    1, 10_000, 0L, 0L, 0L, 0L, 0, 512L * 1024L,
                    ControlledCrawler.RobotsMode.RESPECT, false, true);
            Capture unlimited = crawl(server, "/r0", unlimitedRedirects);
            check(!unlimited.finish.stopped && unlimited.finish.pages.size() == 1
                            && unlimited.finish.pages.get(0).summary.contains("five same-origin hops"),
                    "0 redirects removes the application hop cap for a non-cyclic same-origin chain");
        }
        MockSite loop = new MockSite();
        loop.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        loop.put("/loop-a", 302, "text/plain", "", "", "/loop-b");
        loop.put("/loop-b", 302, "text/plain", "", "", "/loop-a");
        try (MockServer server = new MockServer(loop)) {
            ControlledCrawler.Configuration unlimitedRedirects = new ControlledCrawler.Configuration(
                    1, 10_000, 0L, 0L, 0L, 0L, 0, 512L * 1024L,
                    ControlledCrawler.RobotsMode.RESPECT, false, true);
            Capture capture = crawl(server, "/loop-a", unlimitedRedirects);
            check(capture.finish.stopped && capture.finish.message.contains("重定向循环")
                            && loop.count("/loop-a") == 1 && loop.count("/loop-b") == 1,
                    "unlimited redirect setting still detects an exact URI loop without repeated requests");
        }

        MockSite largeRobots = new MockSite();
        largeRobots.put("/robots.txt", 200, "text/plain", "User-agent: *\n"
                + "x".repeat(ControlledCrawler.DEFAULT_ROBOTS_BYTES + 1));
        largeRobots.put("/seed", 200, "text/plain", "not reached");
        try (MockServer server = new MockServer(largeRobots)) {
            Capture capture = crawl(server, "/seed");
            check(capture.finish.stopped && capture.finish.message.contains("上限"),
                    "robots.txt above the default 512 KiB setting should be refused");
            check(largeRobots.count("/seed") == 0, "oversized robots response must stop before page fetch");
            ControlledCrawler.Configuration unlimitedRobotsBytes = new ControlledCrawler.Configuration(
                    1, 10_000, 0L, 0L, 0L, 0L, 3, 0L,
                    ControlledCrawler.RobotsMode.RESPECT, false, true);
            Capture unlimited = crawl(server, "/seed", unlimitedRobotsBytes);
            check(!unlimited.finish.stopped && unlimited.finish.pages.size() == 1,
                    "0 robots bytes removes the default robots-file body limit");
        }

        MockSite redirectedRobots = new MockSite();
        redirectedRobots.put("/robots.txt", 302, "text/plain", "", "", "/robots-large");
        redirectedRobots.put("/robots-large", 200, "text/plain", "User-agent: *\n"
                + "x".repeat(ControlledCrawler.DEFAULT_ROBOTS_BYTES + 1));
        redirectedRobots.put("/seed", 200, "text/plain", "page after redirected robots response");
        try (MockServer server = new MockServer(redirectedRobots)) {
            ControlledCrawler.Configuration unlimitedRobotsBytes = new ControlledCrawler.Configuration(
                    1, 10_000, 0L, 0L, 0L, 0L, 3, 0L,
                    ControlledCrawler.RobotsMode.RESPECT, false, true);
            Capture unlimited = crawl(server, "/seed", unlimitedRobotsBytes);
            check(!unlimited.finish.stopped && unlimited.finish.pages.size() == 1,
                    "0 robots bytes must also apply after robots.txt redirects");
        }

        MockSite large = new MockSite();
        large.put("/robots.txt", 200, "text/plain", "User-agent: *\n");
        large.put("/large", 200, "text/html", "<p>" + "x".repeat(ControlledCrawler.MAX_BODY_BYTES + 10) + "</p>");
        try (MockServer server = new MockServer(large)) {
            Capture capture = crawl(server, "/large");
            check(capture.finish.stopped && capture.finish.message.contains("上限"), "oversized body should stop");
            check(capture.finish.pages.isEmpty(), "oversized body must not produce a result");
            Capture unlimited = crawl(server, "/large", new ControlledCrawler.Configuration(
                    1, 0L, 0L, 0L, ControlledCrawler.RobotsMode.IGNORE, false, true));
            check(!unlimited.finish.stopped && unlimited.finish.pages.size() == 1,
                    "0 per-page bytes removes the default 16 MiB response body limit");
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

    private static Capture crawl(MockServer server, String path, ControlledCrawler.Configuration config)
            throws Exception {
        return crawl(server, path, config, false);
    }

    private static Capture crawl(MockServer server, String path, ControlledCrawler.Configuration config,
                                 boolean acceptChoices) throws Exception {
        ControlledCrawler crawler = crawler(server, config);
        Capture capture = new Capture(acceptChoices);
        crawler.crawl(server.url(path), capture);
        check(capture.finish != null, "listener must receive final status");
        return capture;
    }

    private static ControlledCrawler crawler(MockServer server) {
        return crawler(server, new ControlledCrawler.Configuration(
                ControlledCrawler.MAX_PAGES, ControlledCrawler.MAX_CRAWL_DURATION_MS,
                ControlledCrawler.DEFAULT_BODY_BYTES, 0L, ControlledCrawler.RobotsMode.RESPECT, false, true));
    }

    private static ControlledCrawler crawler(MockServer server, ControlledCrawler.Configuration config) {
        return new ControlledCrawler(url -> (java.net.HttpURLConnection) url.openConnection(), uri -> {
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() != server.port()) throw new IOException("mock policy rejected non-loopback URL");
        }, config);
    }

    private static final class Capture implements ControlledCrawler.Listener {
        final List<ControlledCrawler.Page> pages = new ArrayList<>();
        final List<ControlledCrawler.Decision> decisions = new ArrayList<>();
        final boolean acceptChoices;
        ControlledCrawler.Finish finish;
        Capture() { this(false); }
        Capture(boolean acceptChoices) { this.acceptChoices = acceptChoices; }
        @Override public void onStatus(String status) { }
        @Override public void onPage(ControlledCrawler.Page page) { pages.add(page); }
        @Override public void onFinished(ControlledCrawler.Finish result) { finish = result; }
        @Override public void onDecision(ControlledCrawler.Decision prompt,
                                        java.util.function.Consumer<Boolean> decision) {
            decisions.add(prompt);
            decision.accept(acceptChoices);
        }
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
