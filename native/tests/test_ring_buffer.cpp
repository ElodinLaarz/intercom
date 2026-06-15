#include "ring_buffer.h"

#include "check.h"

using intercore::RingBuffer;

static void fifo_order() {
  RingBuffer rb(4);
  CHECK(rb.empty());
  CHECK(rb.capacity() == 4);
  rb.push(10);
  rb.push(20);
  rb.push(30);
  CHECK(rb.size() == 3);
  std::int16_t v = 0;
  CHECK(rb.pop(v) && v == 10);
  CHECK(rb.pop(v) && v == 20);
  CHECK(rb.pop(v) && v == 30);
  CHECK(!rb.pop(v));  // empty again
}

static void drop_oldest_on_overflow() {
  RingBuffer rb(3);
  rb.push(1);
  rb.push(2);
  rb.push(3);
  CHECK(rb.full());
  rb.push(4);  // drops 1
  rb.push(5);  // drops 2
  CHECK(rb.overwriteCount() == 2);
  CHECK(rb.size() == 3);
  std::int16_t v = 0;
  CHECK(rb.pop(v) && v == 3);
  CHECK(rb.pop(v) && v == 4);
  CHECK(rb.pop(v) && v == 5);
}

static void steady_one_in_one_out_never_overflows() {
  RingBuffer rb(2);
  std::int16_t v = 0;
  for (int i = 0; i < 1000; ++i) {
    rb.push(static_cast<std::int16_t>(i));
    CHECK(rb.pop(v) && v == static_cast<std::int16_t>(i));
  }
  CHECK(rb.empty());
  CHECK(rb.overwriteCount() == 0);
}

int main() {
  RUN(fifo_order);
  RUN(drop_oldest_on_overflow);
  RUN(steady_one_in_one_out_never_overflows);
  return REPORT();
}
