// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
///////////////////////////////////////////////////////////////////////////////

#ifndef TINK_UTIL_SECRET_DATA_INTERNAL_H_
#define TINK_UTIL_SECRET_DATA_INTERNAL_H_

#include <memory>
#include <type_traits>

// placeholder for sanitization_functions include, please ignore
#include "absl/base/attributes.h"

namespace crypto {
namespace tink {
namespace util {
namespace internal {

inline void SafeZeroMemory(volatile char* ptr, std::size_t size) {
  while (size--) {
    *ptr++ = 0;
  }
}

template <typename T>
struct SanitizingAllocator {
  typedef T value_type;

  SanitizingAllocator() = default;
  template <class U>
  explicit constexpr SanitizingAllocator(
      const SanitizingAllocator<U>&) noexcept {}

  ABSL_MUST_USE_RESULT T* allocate(std::size_t n) {
    return std::allocator<T>().allocate(n);
  }

  void deallocate(T* ptr, std::size_t n) noexcept {
    SafeZeroMemory(reinterpret_cast<char*>(ptr), n * sizeof(T));
    std::allocator<T>().deallocate(ptr, n);
  }

  // Allocator requirements mandate definition of eq and neq operators
  bool operator==(const SanitizingAllocator&) { return true; }
  // bool operator!=(const SanitizingAllocator&) { return false; }
};

template <typename T>
struct SanitizingDeleter {
  void operator()(T* ptr) {
    ptr->~T();  // Invoke destructor. Must do this before sanitize.
    SanitizingAllocator<T>().deallocate(ptr, 1);
  }
};

}  // namespace internal
}  // namespace util
}  // namespace tink
}  // namespace crypto

#endif  // TINK_UTIL_SECRET_DATA_INTERNAL_H_
