#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.smoke-classes"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/app/src/main/java/com/ucastbridge/protocol/Base64Compat.java" \
  "$ROOT/app/src/main/java/com/ucastbridge/protocol/LegacyUcastProtocol.java" \
  "$ROOT/app/src/main/java/com/ucastbridge/network/UdpBoxClient.java" \
  "$ROOT/app/src/main/java/com/ucastbridge/network/RangeHttpServer.java" \
  "$ROOT/app/src/test/java/com/ucastbridge/protocol/ProtocolSmokeTest.java" \
  "$ROOT/app/src/test/java/com/ucastbridge/protocol/RangeHttpServerSmokeTest.java"
java -cp "$OUT" com.ucastbridge.protocol.ProtocolSmokeTest
java -cp "$OUT" com.ucastbridge.protocol.RangeHttpServerSmokeTest
rm -rf "$OUT"
