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

#include "tink/util/statusor.h"

#include <memory>

#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "absl/memory/memory.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "tink/util/status.h"
#include "tink/util/test_matchers.h"

namespace crypto {
namespace tink {
namespace util {
namespace {

using ::crypto::tink::test::IsOk;
using ::testing::Eq;
using ::testing::Not;
using ::testing::Pointee;

TEST(StatusOrTest, ConvertOkToAbsl) {
  StatusOr<int> instance = 1;

  absl::StatusOr<int> converted = instance;
  ASSERT_TRUE(converted.ok());
  EXPECT_EQ(*converted, 1);
}

TEST(StatusOrTest, ConvertErrorToAbsl) {
  StatusOr<int> instance{
      Status(error::Code::INVALID_ARGUMENT, "Error message")};

  absl::StatusOr<int> converted = instance;
  ASSERT_FALSE(converted.ok());
  EXPECT_EQ(converted.status().code(), absl::StatusCode::kInvalidArgument);
  EXPECT_EQ(converted.status().message(), "Error message");
}

TEST(StatusOrTest, ConvertUncopyableToAbsl) {
  StatusOr<std::unique_ptr<int>> instance = absl::make_unique<int>(1);

  absl::StatusOr<std::unique_ptr<int>> converted = std::move(instance);
  ASSERT_TRUE(converted.ok());
  EXPECT_THAT(*converted, Pointee(Eq(1)));
}

class NoDefaultConstructor {
 public:
  explicit NoDefaultConstructor(int i) {}

  NoDefaultConstructor() = delete;
  NoDefaultConstructor(const NoDefaultConstructor&) = default;
  NoDefaultConstructor& operator=(const NoDefaultConstructor&) =
      default;
  NoDefaultConstructor(NoDefaultConstructor&&) = default;
  NoDefaultConstructor& operator=(NoDefaultConstructor&&) = default;
};

// Tests that we can construct a StatusOr<T> even if there is no default
// constructor for T.
TEST(StatusOrTest, WithNoDefaultConstructor) {
  StatusOr<NoDefaultConstructor> value = NoDefaultConstructor(13);
  StatusOr<NoDefaultConstructor> error =
      Status(error::Code::INVALID_ARGUMENT, "Error message");
}

// This tests that when we assign to something which is previously an error,
// we create a new optional inside the StatusOr, and do not try to assign to
// the value of the optional instead.
TEST(StatusOrTest, AssignToErrorStatus) {
  StatusOr<std::string> error_initially =
      Status(error::Code::INVALID_ARGUMENT, "Error message");
  ASSERT_THAT(error_initially.status(), Not(IsOk()));
  StatusOr<std::string> ok_initially = std::string("Hi");
  error_initially = ok_initially;
  ASSERT_THAT(error_initially.status(), IsOk());
  ASSERT_THAT(error_initially.ValueOrDie(), Eq("Hi"));
}

// This tests that when we assign to something which is previously an error and
// at the same time use the implicit conversion operator, we create a new
// optional inside the StatusOr, and do not try to assign to the value of the
// optional instead.
TEST(StatusOrTest, AssignToErrorStatusImplicitConvertible) {
  StatusOr<std::string> error_initially =
      Status(error::Code::INVALID_ARGUMENT, "Error message");
  ASSERT_THAT(error_initially.status(), Not(IsOk()));
  StatusOr<char const*> ok_initially = "Hi";
  error_initially = ok_initially;
  ASSERT_THAT(error_initially.status(), IsOk());
  ASSERT_THAT(error_initially.ValueOrDie(), Eq("Hi"));
}

TEST(StatusOrTest, MoveOutMoveOnly) {
  StatusOr<std::unique_ptr<int>> status_or_unique_ptr_int =
      absl::make_unique<int>(10);
  std::unique_ptr<int> ten = std::move(status_or_unique_ptr_int.ValueOrDie());
  ASSERT_THAT(*ten, Eq(10));
}

}  // namespace

}  // namespace util
}  // namespace tink
}  // namespace crypto
