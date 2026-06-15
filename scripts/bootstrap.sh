#!/usr/bin/env bash
# Preflight check. Verifies the toolchain a build needs and FAILS LOUD with an
# actionable message (V2_PLAN rule 7). Run it after cloning; CI runs it after
# installing the NDK. Build output is never piped through tail/head — that
# masked v1's native build failures (landmine #11).
set -u

FAILED=0
ok()   { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; FAILED=1; }

echo "intercom bootstrap — preflight"

# --- JDK 17+ ---
if command -v java >/dev/null 2>&1; then
  JV=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
  if [ "${JV:-0}" -ge 17 ] 2>/dev/null; then ok "JDK $JV (>= 17)"
  else fail "JDK 17+ required, found '${JV:-none}'"; fi
else
  fail "java not found — install a JDK 17+"
fi

# --- Android SDK ---
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
  SDK=$(sed -nE 's/^sdk\.dir=(.*)/\1/p' local.properties | head -1)
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then ok "Android SDK at $SDK"
else fail "Android SDK not found (set ANDROID_HOME or local.properties sdk.dir)"; fi

# --- NDK (version is single-sourced from app/build.gradle.kts) ---
NDK_VER=$(sed -nE 's/.*ndkVersion = "([^"]+)".*/\1/p' app/build.gradle.kts | head -1)
if [ -n "$SDK" ] && [ -n "$NDK_VER" ] && [ -d "$SDK/ndk/$NDK_VER" ]; then ok "NDK $NDK_VER"
else fail "NDK ${NDK_VER:-?} missing — install:  sdkmanager \"ndk;${NDK_VER:-<ver>}\""; fi

# --- cmake (SDK-bundled 3.22.1, or a system cmake) ---
if [ -n "$SDK" ] && [ -x "$SDK/cmake/3.22.1/bin/cmake" ]; then ok "cmake 3.22.1 (SDK)"
elif command -v cmake >/dev/null 2>&1; then ok "cmake $(cmake --version | head -1 | awk '{print $3}') (system)"
else fail "cmake missing — install:  sdkmanager \"cmake;3.22.1\""; fi

# --- v2 has no vendored codec submodule (ADPCM, not Opus) ---
if [ -f .gitmodules ]; then fail ".gitmodules present — v2 has no submodules (ADPCM needs no vendored codec); remove it"
else ok "no git submodules (expected — ADPCM, not vendored Opus)"; fi

echo
if [ "$FAILED" -ne 0 ]; then
  echo "bootstrap FAILED — fix the items above before building."
  exit 1
fi
echo "bootstrap OK"
