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

#include "tink/jwt/internal/jwt_mac_impl.h"

#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "absl/strings/escaping.h"
#include "tink/jwt/internal/jwt_format.h"
#include "tink/jwt/jwt_mac.h"
#include "tink/jwt/jwt_validator.h"
#include "tink/jwt/raw_jwt.h"
#include "tink/jwt/verified_jwt.h"
#include "tink/subtle/hmac_boringssl.h"
#include "tink/util/constants.h"
#include "tink/util/enums.h"
#include "tink/util/errors.h"
#include "tink/util/protobuf_helper.h"
#include "tink/util/secret_data.h"
#include "tink/util/test_matchers.h"
#include "tink/util/test_util.h"

using ::crypto::tink::test::IsOk;

namespace crypto {
namespace tink {
namespace jwt_internal {

namespace {

util::StatusOr<std::unique_ptr<JwtMac>> CreateJwtMac() {
  std::string key_value;
  if (!absl::WebSafeBase64Unescape(
          "AyM1SysPpbyDfgZld3umj1qzKObwVMkoqQ-EstJQLr_T-1"
          "qS0gZH75aKtMN3Yj0iPS4hcgUuTwjAzZr1Z9CAow",
          &key_value)) {
    return util::Status(util::error::INVALID_ARGUMENT, "failed to parse key");
  }
  crypto::tink::util::StatusOr<std::unique_ptr<Mac>> mac_or =
      subtle::HmacBoringSsl::New(
          util::Enums::ProtoToSubtle(google::crypto::tink::HashType::SHA256),
          32, util::SecretDataFromStringView(key_value));
  if (!mac_or.ok()) {
    return mac_or.status();
  }
  std::unique_ptr<JwtMac> jwt_mac =
      absl::make_unique<JwtMacImpl>(std::move(mac_or.ValueOrDie()), "HS256");
  return jwt_mac;
}

TEST(JwtMacImplTest, CreateAndValidateToken) {
  auto jwt_mac_or = CreateJwtMac();
  ASSERT_THAT(jwt_mac_or.status(), IsOk());
  std::unique_ptr<JwtMac> jwt_mac = std::move(jwt_mac_or.ValueOrDie());

  absl::Time now = absl::Now();
  auto raw_jwt_or = RawJwtBuilder()
                        .SetIssuer("issuer")
                        .SetNotBefore(now - absl::Seconds(300))
                        .SetIssuedAt(now)
                        .SetExpiration(now + absl::Seconds(300))
                        .Build();
  ASSERT_THAT(raw_jwt_or.status(), IsOk());
  RawJwt raw_jwt = raw_jwt_or.ValueOrDie();

  util::StatusOr<std::string> compact_or =
      jwt_mac->ComputeMacAndEncode(raw_jwt);
  ASSERT_THAT(compact_or.status(), IsOk());
  std::string compact = compact_or.ValueOrDie();

  JwtValidator validator = JwtValidatorBuilder().Build();

  util::StatusOr<VerifiedJwt> verified_jwt_or =
      jwt_mac->VerifyMacAndDecode(compact, validator);
  ASSERT_THAT(verified_jwt_or.status(), IsOk());
  auto verified_jwt = verified_jwt_or.ValueOrDie();
  EXPECT_THAT(verified_jwt.GetIssuer(), test::IsOkAndHolds("issuer"));

  JwtValidator validator2 = JwtValidatorBuilder().SetIssuer("unknown").Build();
  EXPECT_FALSE(jwt_mac->VerifyMacAndDecode(compact, validator2).ok());
}

TEST(JwtMacImplTest, ValidateFixedToken) {
  auto jwt_mac_or = CreateJwtMac();
  ASSERT_THAT(jwt_mac_or.status(), IsOk());
  std::unique_ptr<JwtMac> jwt_mac = std::move(jwt_mac_or.ValueOrDie());

  // token that expired in 2011
  std::string compact =
      "eyJ0eXAiOiJKV1QiLA0KICJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJqb2UiLA0KICJleH"
      "AiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ."
      "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
  JwtValidator validator_1970 =
      JwtValidatorBuilder().SetFixedNow(absl::FromUnixSeconds(12345)).Build();

  // verification succeeds because token was valid 1970
  util::StatusOr<VerifiedJwt> verified_jwt_or =
      jwt_mac->VerifyMacAndDecode(compact, validator_1970);
  ASSERT_THAT(verified_jwt_or.status(), IsOk());
  auto verified_jwt = verified_jwt_or.ValueOrDie();
  EXPECT_THAT(verified_jwt.GetIssuer(), test::IsOkAndHolds("joe"));
  EXPECT_THAT(verified_jwt.GetBooleanClaim("http://example.com/is_root"),
              test::IsOkAndHolds(true));

  // verification fails because token is expired
  JwtValidator validator_now = JwtValidatorBuilder().Build();
  EXPECT_FALSE(jwt_mac->VerifyMacAndDecode(compact, validator_now).ok());

  // verification fails because token was modified
  std::string modified_compact =
      "eyJ0eXAiOiJKV1QiLA0KICJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJqb2UiLA0KICJleH"
      "AiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ."
      "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXi";
  EXPECT_FALSE(
      jwt_mac->VerifyMacAndDecode(modified_compact, validator_1970).ok());
}

TEST(JwtMacImplTest, ValidateInvalidTokens) {
  auto jwt_mac_or = CreateJwtMac();
  ASSERT_THAT(jwt_mac_or.status(), IsOk());
  std::unique_ptr<JwtMac> jwt_mac = std::move(jwt_mac_or.ValueOrDie());

  JwtValidator validator = JwtValidatorBuilder().Build();

  EXPECT_FALSE(
      jwt_mac->VerifyMacAndDecode("eyJhbGciOiJIUzI1NiJ9.e30.abc.", validator)
          .ok());
  EXPECT_FALSE(
      jwt_mac->VerifyMacAndDecode("eyJhbGciOiJIUzI1NiJ9?.e30.abc", validator)
          .ok());
  EXPECT_FALSE(
      jwt_mac->VerifyMacAndDecode("eyJhbGciOiJIUzI1NiJ9.e30?.abc", validator)
          .ok());
  EXPECT_FALSE(
      jwt_mac->VerifyMacAndDecode("eyJhbGciOiJIUzI1NiJ9.e30.abc?", validator)
          .ok());
  EXPECT_FALSE(
      jwt_mac->VerifyMacAndDecode("eyJhbGciOiJIUzI1NiJ9.e30", validator).ok());
}

}  // namespace
}  // namespace jwt_internal
}  // namespace tink
}  // namespace crypto
