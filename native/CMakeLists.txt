# Host build of the codec-agnostic native core + its tests. Built on the CI
# runner with the SDK cmake and run via ctest — this is the "native host-built
# C++ tests" M0 deliverable (V2_PLAN s5/s6). The same headers under core/ are
# compiled into the Android .so by app/src/main/cpp/CMakeLists.txt.
cmake_minimum_required(VERSION 3.18)
project(intercore_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

enable_testing()

add_executable(test_ring_buffer tests/test_ring_buffer.cpp)
target_include_directories(test_ring_buffer PRIVATE core tests)

add_executable(test_seq_filter tests/test_seq_filter.cpp)
target_include_directories(test_seq_filter PRIVATE core tests)

add_test(NAME ring_buffer COMMAND test_ring_buffer)
add_test(NAME seq_filter COMMAND test_seq_filter)
