#!/usr/bin/env bash
# Regenerates the Kotlin mirror of proto/constants.h. The C++ header is the
# single source of truth (V2_PLAN §4.4); this writes Proto.kt and CI fails if the
# checked-in copy drifts. Pure awk — runs on the runner and on git-bash.
set -eu
cd "$(dirname "$0")/.."

SRC="proto/constants.h"
OUT="app/src/main/kotlin/com/elodin/intercom/proto/Proto.kt"
mkdir -p "$(dirname "$OUT")"

{
  echo "// GENERATED from proto/constants.h by tools/gen-proto.sh — DO NOT EDIT."
  echo "// The C++ header is the single source of truth (V2_PLAN §4.4, landmine #10)."
  echo "package com.elodin.intercom.proto"
  echo
  echo "object Proto {"
  awk '
    function snake(name,   i, c, s) {
      sub(/^k/, "", name)
      s = ""
      for (i = 1; i <= length(name); i++) {
        c = substr(name, i, 1)
        if (c ~ /[A-Z]/ && i > 1) s = s "_"
        s = s toupper(c)
      }
      return s
    }
    /^[ \t]*\/\// { next }   # skip comment lines, incl. the contract examples
    /inline constexpr int[ \t]+k[A-Za-z0-9]+[ \t]*=/ {
      match($0, /k[A-Za-z0-9]+/); nm = substr($0, RSTART, RLENGTH)
      match($0, /=[ \t]*[^;]+/);  v = substr($0, RSTART + 1, RLENGTH - 1); gsub(/[ \t]/, "", v)
      printf "    const val %s: Int = %s\n", snake(nm), v
    }
    /inline constexpr char[ \t]+k[A-Za-z0-9]+\[\][ \t]*=/ {
      match($0, /k[A-Za-z0-9]+/); nm = substr($0, RSTART, RLENGTH)
      match($0, /"[^"]*"/);       v = substr($0, RSTART, RLENGTH)
      printf "    const val %s: String = %s\n", snake(nm), v
    }
  ' "$SRC"
  echo "}"
} > "$OUT"

echo "wrote $OUT"
