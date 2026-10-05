#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
RESULTS="$ROOT/test-results"
mkdir -p "$RESULTS"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [[ -x /home/ubuntu/.local/jdks/temurin-25.0.4.1+1/bin/keytool ]]; then
  export JAVA_HOME=/home/ubuntu/.local/jdks/temurin-25.0.4.1+1
  export PATH="$JAVA_HOME/bin:$PATH"
fi
KEYSTORE="$TMP/mock-doh.p12"
keytool -genkeypair -noprompt -alias mockdoh -keyalg RSA -keysize 2048 -validity 2 \
  -dname 'CN=localhost, OU=Local Test, O=DNS Proxy Test, C=ZZ' \
  -ext 'SAN=dns:localhost,ip:127.0.0.1' \
  -storetype PKCS12 -keystore "$KEYSTORE" -storepass changeit -keypass changeit \
  >"$RESULTS/dns-local-keystore.log" 2>&1

python3 tools/dns_vpn_source_smoke.py | tee "$RESULTS/dns-source-smoke.log"
mkdir -p "$TMP/classes"
javac --add-modules jdk.httpserver -d "$TMP/classes" \
  app/src/main/java/com/cue/simplebrowser/DnsWire.java \
  app/src/main/java/com/cue/simplebrowser/DnsPacketCodec.java \
  app/src/main/java/com/cue/simplebrowser/DnsRoutingPolicy.java \
  app/src/main/java/com/cue/simplebrowser/DohClient.java \
  tools/DnsLocalMockTest.java
java --add-modules jdk.httpserver -cp "$TMP/classes" \
  com.cue.simplebrowser.DnsLocalMockTest "$KEYSTORE" \
  | tee "$RESULTS/dns-local-mock-test.log"
