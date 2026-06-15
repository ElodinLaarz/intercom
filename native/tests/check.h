#pragma once

#include <cstdio>

// Tiny assert harness — no gtest dependency, so CI stays hermetic (no network
// fetch for a test framework on a runner whose net access we don't want to
// depend on). A failed CHECK prints file:line and is counted; REPORT() returns
// non-zero if any failed, which ctest reports as a test failure.

namespace check_detail {
inline int& failures() {
  static int f = 0;
  return f;
}
}  // namespace check_detail

#define CHECK(cond)                                                  \
  do {                                                               \
    if (!(cond)) {                                                   \
      std::fprintf(stderr, "CHECK failed: %s\n  at %s:%d\n", #cond,  \
                   __FILE__, __LINE__);                              \
      ++check_detail::failures();                                    \
    }                                                                \
  } while (0)

#define RUN(testfn)            \
  do {                         \
    std::printf("- %s\n", #testfn); \
    testfn();                  \
  } while (0)

#define REPORT()                                                            \
  (check_detail::failures() == 0                                            \
       ? (std::printf("OK\n"), 0)                                           \
       : (std::fprintf(stderr, "%d failure(s)\n", check_detail::failures()), \
          1))
