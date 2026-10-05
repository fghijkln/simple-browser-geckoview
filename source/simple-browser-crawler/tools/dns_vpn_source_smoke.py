#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
service = (root / "app/src/main/java/com/cue/simplebrowser/DnsVpnService.java").read_text(encoding="utf-8")
doh = (root / "app/src/main/java/com/cue/simplebrowser/DohClient.java").read_text(encoding="utf-8")
codec = (root / "app/src/main/java/com/cue/simplebrowser/DnsPacketCodec.java").read_text(encoding="utf-8")
activity = (root / "app/src/main/java/com/cue/simplebrowser/MainActivity.java").read_text(encoding="utf-8")
route_policy = (root / "app/src/main/java/com/cue/simplebrowser/DnsRoutingPolicy.java").read_text(encoding="utf-8")
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
strings = (root / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
webrtc = (root / "app/src/main/java/com/cue/simplebrowser/WebRtcProtectionPolicy.java").read_text(encoding="utf-8")

checks = {
    "VpnService limits VPN to this package": ".addAllowedApplication(getPackageName())" in service,
    "VPN builder has no IPv4/IPv6 default route": 'addRoute("0.0.0.0", 0)' not in service and 'addRoute("::", 0)' not in service,
    "only four exact Quad9 DNS addresses are in the route policy": all(x in route_policy for x in ("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")),
    "routes use exact host prefixes": "return address.indexOf(':') >= 0 ? 128 : 32;" in route_policy,
    "TUN reader ignores non-DNS destination ports": "if (packet.destinationPort != 53) continue;" in service,
    "TCP DNS half-close remains alive until the DoH response is sent": "session.clientFinReceived = true;" in service and "if (session.clientFinReceived && !session.serverFinSent)" in service,
    "TCP responses respect peer MSS and bound segment allocation": "Math.min(DnsPacketCodec.TCP_SEGMENT_SIZE, session.peerMss)" in service and "MAX_TCP_RESPONSE_SEGMENTS" in service,
    "TCP MSS options are parsed and length-checked": "if (kind == 2)" in codec and "Invalid TCP option length" in codec,
    "DoH socket is protected before connecting": doh.index("protector.protect(raw)") < doh.index("raw.connect("),
    "DoH TLS hostname verification is enabled": 'setEndpointIdentificationAlgorithm("HTTPS")' in doh,
    "DoH bootstrap is numeric and does not use hostname resolution": "InetAddress.getByAddress" in doh and "InetAddress.getByName" not in doh,
    "only RFC 8484 binary POST is used": '"POST " + path' in doh and '"Content-Type: application/dns-message' in doh and '"Accept: application/dns-message' in doh,
    "no URL query-string DNS transport is used": "?dns=" not in doh,
    "DNS failure path explicitly avoids cleartext fallback": "DoH failed closed; no DNS fallback was attempted" in service and "TCP DNS DoH failed closed; no DNS fallback was attempted" in service,
    "VPN consent state and inactive state are surfaced": "REQUEST_DNS_VPN_CONSENT" in activity and "dns_vpn_inactive" in strings,
    "experimental and unverified resolver-path caveat is visible": ("DNS 路径未验证" in strings or "解析路径未验证" in service) and "实验原型" in strings,
    "Quad9 disclosure is visible": "查询会交给 Quad9 处理" in strings,
    "existing WebRTC protection policy remains": "privacy.webrtc.protection.enabled" in webrtc,
    "VpnService is declared with the required bind permission": "android.permission.BIND_VPN_SERVICE" in manifest and "android.net.VpnService" in manifest,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
if failed:
    raise SystemExit("Source smoke checks failed: " + "; ".join(failed))
