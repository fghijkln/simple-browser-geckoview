# Simple Browser v0.6 (app version 2.9.0 / versionCode 19) — Release Notes Draft

**Status: local candidate only; not published.** Source is based on the public [`v0.5` tag / commit `26a28a72d10a7907e8a995c8f2cf03568765af5d`](https://github.com/fghijkln/simple-browser-geckoview/tree/26a28a72d10a7907e8a995c8f2cf03568765af5d). The local APKs use the same Android debug certificate as v0.5, not a public-store release key. **No Android device testing has been performed.**

## What changed

### Privilege checks report findings and let the user choose

- App permission, capability, AppOp, device-management, accessibility, notification-listener, and process/environment checks remain. Non-fatal findings—such as unreviewed permissions, extra capabilities/AppOps, or unreadable state—are listed in a bilingual notice on each fresh launch instead of independently blocking startup. The user must choose Continue or Exit; Settings can reopen the notice.
- Genuine crashes, corrupted app context, or inability to initialize safely remain fatal. Other app-owned checks were not removed.
- The notice reminds users of risk; **it does not change legal responsibility or authorization status**.

### USB/ADB DevTools and in-app Console default to available

- USB/ADB DevTools is enabled by default through GeckoView's Runtime API and can be turned off in Settings. This does not enable Wi-Fi/LAN debugging or add Android permissions. Device discovery/connectivity has not been tested.
- The in-app Console is available by default. Installing its WebExtension still requires an explicit explanation/acceptance of its all-HTTP/HTTPS-sites host permission. Capture is limited to the selected GeckoSession's top-level site page and only while the panel is open.
- It displays scalar `console.log`/`warn`/`error` argument values, uncaught errors, unhandled rejections, stacks, and paths. **Log text may contain credentials, personal information, or other sensitive page content; page scripts can also forge log entries.** Ordinary objects and DOM are not expanded. The Console does not additionally read cookies, DOM/forms, request/response bodies, page URLs, or wall-clock time; no general JavaScript bridge/native eval is added.
- Logs remain in process memory only. Developer settings offer **500 / 1,000 / 2,500 / 5,000 entries or Unlimited** (default 5,000); **16,384 / 65,536 UTF-16 code units per entry/argument or Unlimited** (default 16,384); and **15 / 60 / 120 events per second or Unlimited** (default 60). There is no separate fixed argument-count cap. A finite character choice limits the combined message and each argument at that selected value. Selecting Unlimited requires a risk confirmation. Changing buffer capacity clears current logs; closing the panel clears the buffer and uninstalls the extension.

### Android runtime permission requests for camera, microphone, and location

When a page requests camera, microphone, or location, the app invokes Android's runtime permission request for permissions already present in its Manifest allowlist. If Android grants app permission, the browser still asks for separate per-site consent. Denying either step denies the site request. No Android permission was added.

### Crawler limits have editable finite values and an Unlimited choice

| Setting | Default | Meaning of `0` / Unlimited |
|---|---:|---|
| Page count | 100 | No app-configured page-count cap |
| Pending URL queue | 10,000 | No app-configured queue-count cap |
| Total crawl duration | 30 minutes | No app-configured total-duration cap |
| Response body per page | 16 MiB | No app-configured per-page byte cap |
| `robots.txt` response body | 512 KiB | No app-configured robots-body byte cap, including a redirect target |
| Same-origin redirect hops | 3 | No hop-count cap; an exact-URI loop still stops |
| Extra minimum request gap | 1 second | No added app gap; `Crawl-delay` is still honored when applicable |
| App-added 429/503 wait | 30 seconds | No extra app wait; the server's `Retry-After` is still honored |
| Robots policy | Respect | Can respect, ask per disallowed path, or ignore |

All settings can be adjusted in the crawler dialog. Cross-site targets are off by default; enabling them prompts for each new origin/cross-site redirect. Ordinary non-success HTTP responses can be configured for an explicit per-response choice before continuing to other queued URLs. A traffic, authorization, site-rules, privacy, and third-party-load warning appears before every crawl.

Crawling is explicitly user-started, foreground, and serial. Every 429/503 retry needs a separate confirmation; setting the added wait to zero does not skip the server's `Retry-After`. Only static HTML/plain text actually returned by the server is parsed; browser cookies/login sessions are not used. For CAPTCHA, login/paywall, or 401/403 responses, the user may choose to display only the static text actually returned or open the URL manually in the browser and stop the crawl. The crawler does not follow links from that response, auto-login, solve challenges, bypass access controls, or fetch authenticated content.

There is no background crawling, concurrent flooding, retry storm, CAPTCHA solving, login/paywall bypass, cookie/session theft, IP rotation, proxy deception, or UA/TLS/fingerprint spoofing. The crawler uses Android Java HTTP(S) and system DNS; it does not inherit GeckoView's Quad9 TRR-only / DNS-only VPN route.

## What “Unlimited” means—and technical boundaries that remain

`0` / Unlimited removes the corresponding **app-configured cap**; it does not promise infinite resources or success. Response data is processed using Java byte arrays/strings and queue/results are kept in memory. Android heap, system allocation, Java integer/string addressing, and available memory are objective limits. Unlimited crawls/logs may exhaust memory, cause ANR/crashes, or slow the device. Console row/event counts also use Java collections/integer representations and remain memory-bound. The crawler retains 8-second connect/read timeouts, so a slow response can still fail. The displayed text summary is limited to about 12,000 characters per page; this limits summary presentation, not the configured response download size, and HTML link scanning does not stop at the summary boundary.

Users must independently verify access authorization, site terms/robots rules, privacy duties, and effects on third-party services. This notice reminds users of risk; it does not change legal responsibility or authorization status.

## v0.5 compatibility, migration, and rollback

- In-place upgrade retains the v0.5 browser profile, history, bookmarks, site permissions, cookie/profile isolation, and private data path; no destructive migration is performed.
- Unset debugging preferences in older profiles use the v0.6 defaults. Explicit user on/off choices remain respected. Missing crawler settings receive the table defaults. The notice is shown at startup; users can disable debugging/Console, change crawler options, or exit.
- No Manifest permission was added. DNS/ETP/WebRTC defaults, user-storage paths, and VPN/network allowlists were not changed.
- Back up important data before rollback. Android generally prevents direct downgrade; uninstalling and reinstalling v0.5 deletes private app data. English resources cover the risk notice and affected features only; other legacy UI may remain in Simplified Chinese.

## Verification status

Final results for `bash tools/run-crawler-local-mock.sh`, `bash tools/run-geckoview-local-tests.sh`, `lintRelease`, and both ABI Release builds are recorded in [REPORT.md](/workspace/simple-browser-unrestricted-v0.6/REPORT.md). Tests used source checks and loopback mocks only; **no Android device installation/runtime test** was performed. Camera/microphone/location dialogs, ADB DevTools, the real Gecko WebExtension host-permission UI, full session restoration, and real-site behavior remain unverified.

> This notice reminds users of risks; it does not change legal responsibility or authorization status.
