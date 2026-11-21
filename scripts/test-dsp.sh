#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_binary=$(mktemp)
trap 'rm -f "$test_binary"' EXIT
"${CXX:-c++}" -std=c++17 -O3 -Wall -Wextra -Werror -Iapp/src/main/cpp \
  app/src/main/cpp/dsp.cpp scripts/dsp_test.cpp -o "$test_binary"
"$test_binary"
