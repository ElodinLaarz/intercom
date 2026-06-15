#include "seq_filter.h"

#include "check.h"

using intercore::SeqFilter;

static void in_order_accepts() {
  SeqFilter f(7);
  CHECK(f.accept(7, 1));
  CHECK(f.accept(7, 2));
  CHECK(f.accept(7, 3));
  CHECK(f.high() == 3);
}

static void rejects_old_and_duplicate() {
  SeqFilter f(7);
  CHECK(f.accept(7, 10));
  CHECK(!f.accept(7, 10));  // duplicate
  CHECK(!f.accept(7, 9));   // old
  CHECK(f.accept(7, 11));   // newer
}

static void rejects_wrong_epoch() {
  SeqFilter f(7);
  CHECK(f.accept(7, 5));
  CHECK(!f.accept(8, 6));  // newer seq but stale epoch
  CHECK(!f.accept(6, 6));
  CHECK(f.high() == 5);  // high-water untouched by rejected frames
}

static void survives_32bit_wrap() {
  const std::uint32_t near = 0xFFFFFFFEu;
  SeqFilter f(1);
  CHECK(f.accept(1, near));      // 0xFFFFFFFE
  CHECK(f.accept(1, near + 1));  // 0xFFFFFFFF
  CHECK(f.accept(1, near + 2));  // wraps to 0x00000000
  CHECK(f.accept(1, near + 3));  // 0x00000001
  CHECK(!f.accept(1, near));     // now "old" across the wrap
  CHECK(f.high() == (near + 3));  // == 1u
}

int main() {
  RUN(in_order_accepts);
  RUN(rejects_old_and_duplicate);
  RUN(rejects_wrong_epoch);
  RUN(survives_32bit_wrap);
  return REPORT();
}
