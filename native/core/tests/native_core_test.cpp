#include <cassert>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <utility>

#include "camx/android_owners.hpp"
#include "camx/bounded_timestamp_index.hpp"
#include "camx/native_trace_buffer.hpp"
#include "camx/native_buffer_pool.hpp"
#include "camx/resource_counters.hpp"

namespace {

struct TestHandle final {
  int* releases;
};

void ReleaseTestHandle(TestHandle* handle) noexcept {
  ++(*handle->releases);
  delete handle;
}

using TestOwner = camx::UniqueNdkOwner<TestHandle, ReleaseTestHandle>;

}  // namespace

int main() {
  camx::BoundedTimestampIndex<int> index(2U);
  assert(!index.insert(10, 1).has_value());
  assert(!index.insert(20, 2).has_value());
  const auto discarded = index.insert(30, 3);
  assert(discarded.has_value() && discarded.value() == 1);
  assert(!index.take(10).has_value());
  assert(index.take(20).value() == 2);

  bool rejected_zero = false;
  try {
    camx::BoundedTimestampIndex<int> invalid(0U);
  } catch (const std::invalid_argument&) {
    rejected_zero = true;
  }
  assert(rejected_zero);

  camx::NativeTraceBuffer trace(2U);
  trace.push({1, 100, 1});
  trace.push({2, 200, 1});
  trace.push({3, 300, 2});
  const auto snapshot = trace.snapshot();
  assert(snapshot.size() == 2U);
  assert(snapshot[0].code == 2);
  assert(snapshot[1].code == 3);

  camx::ResourceCounters counters;
  counters.add(camx::NativeResource::kImages, 2);
  counters.add(camx::NativeResource::kImages, -3);
  counters.add(camx::NativeResource::kBufferBytes, std::numeric_limits<std::int64_t>::max());
  counters.add(camx::NativeResource::kBufferBytes, 1);
  const auto resources = counters.snapshot();
  assert(resources[static_cast<std::size_t>(camx::NativeResource::kImages)] == 0);
  assert(resources[static_cast<std::size_t>(camx::NativeResource::kBufferBytes)] ==
         std::numeric_limits<std::int64_t>::max());

  camx::NativeBufferPool pool(64U, 2U);
  auto first_buffer = pool.acquire();
  auto second_buffer = pool.acquire();
  assert(first_buffer.has_value());
  assert(second_buffer.has_value());
  assert(!pool.acquire().has_value());
  first_buffer.reset();
  assert(pool.acquire().has_value());

  int releases = 0;
  {
    TestOwner first(new TestHandle{.releases = &releases});
    TestOwner moved(std::move(first));
    assert(!first);
    assert(moved);
    moved.reset(new TestHandle{.releases = &releases});
    assert(releases == 1);
    TestHandle* released = moved.release();
    assert(!moved);
    assert(releases == 1);
    ReleaseTestHandle(released);
    assert(releases == 2);
  }
  assert(releases == 2);
  return 0;
}
