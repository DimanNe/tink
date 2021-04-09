// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.testing;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.crypto.tink.internal.KeyTemplateProtoConverter;
import com.google.crypto.tink.jwt.JwtEcdsaSignKeyManager;
import com.google.crypto.tink.jwt.JwtHmacKeyManager;
import com.google.crypto.tink.jwt.JwtMacConfig;
import com.google.crypto.tink.jwt.JwtSignatureConfig;
import com.google.crypto.tink.proto.testing.JwtClaimValue;
import com.google.crypto.tink.proto.testing.JwtGrpc;
import com.google.crypto.tink.proto.testing.JwtSignRequest;
import com.google.crypto.tink.proto.testing.JwtSignResponse;
import com.google.crypto.tink.proto.testing.JwtToken;
import com.google.crypto.tink.proto.testing.JwtValidator;
import com.google.crypto.tink.proto.testing.JwtVerifyRequest;
import com.google.crypto.tink.proto.testing.JwtVerifyResponse;
import com.google.crypto.tink.proto.testing.KeysetGenerateRequest;
import com.google.crypto.tink.proto.testing.KeysetGenerateResponse;
import com.google.crypto.tink.proto.testing.KeysetGrpc;
import com.google.crypto.tink.proto.testing.KeysetPublicRequest;
import com.google.crypto.tink.proto.testing.KeysetPublicResponse;
import com.google.crypto.tink.proto.testing.NullValue;
import com.google.crypto.tink.proto.testing.StringValue;
import com.google.crypto.tink.proto.testing.Timestamp;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Instant;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class JwtServiceImplTest {
  private Server server;
  private ManagedChannel channel;
  KeysetGrpc.KeysetBlockingStub keysetStub;
  JwtGrpc.JwtBlockingStub jwtStub;

  @Before
  public void setUp() throws Exception {
    JwtMacConfig.register();
    JwtSignatureConfig.register();

    String serverName = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder
        .forName(serverName)
        .directExecutor()
        .addService(new KeysetServiceImpl())
        .addService(new JwtServiceImpl())
        .build()
        .start();
    channel = InProcessChannelBuilder
        .forName(serverName)
        .directExecutor()
        .build();
    keysetStub = KeysetGrpc.newBlockingStub(channel);
    jwtStub = JwtGrpc.newBlockingStub(channel);
  }

  @After
  public void tearDown() throws Exception {
    assertThat(channel.shutdown().awaitTermination(5, SECONDS)).isTrue();
    assertThat(server.shutdown().awaitTermination(5, SECONDS)).isTrue();
  }

  private static KeysetGenerateResponse generateKeyset(
      KeysetGrpc.KeysetBlockingStub keysetStub, byte[] template) {
    KeysetGenerateRequest genRequest =
        KeysetGenerateRequest.newBuilder().setTemplate(ByteString.copyFrom(template)).build();
    return keysetStub.generate(genRequest);
  }

  private static KeysetPublicResponse publicKeyset(
      KeysetGrpc.KeysetBlockingStub keysetStub, byte[] privateKeyset) {
    KeysetPublicRequest request =
        KeysetPublicRequest.newBuilder()
            .setPrivateKeyset(ByteString.copyFrom(privateKeyset))
            .build();
    return keysetStub.public_(request);
  }

  private JwtToken generateToken(String audience, Instant expiration) {
    return JwtToken.newBuilder()
        .setIssuer(StringValue.newBuilder().setValue("issuer"))
        .addAudiences(audience)
        .addAudiences(audience + "2")
        .setJwtId(StringValue.newBuilder().setValue("123abc"))
        .putCustomClaims("boolean", JwtClaimValue.newBuilder().setBoolValue(true).build())
        .putCustomClaims(
            "null", JwtClaimValue.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
        .putCustomClaims("number", JwtClaimValue.newBuilder().setNumberValue(123.456).build())
        .putCustomClaims("string", JwtClaimValue.newBuilder().setStringValue("foo").build())
        .putCustomClaims(
            "json_array",
            JwtClaimValue.newBuilder()
                .setJsonArrayValue("[123,\"value\",null,[],{\"a\":42}]")
                .build())
        .putCustomClaims(
            "json_object",
            JwtClaimValue.newBuilder().setJsonObjectValue("{\"a\":[null,{\"b\":42}]}").build())
        .setExpiration(Timestamp.newBuilder().setSeconds(expiration.getEpochSecond()))
        .build();
  }

  @Test
  public void jwtComputeVerifyMac_success() throws Exception {
    byte[] template = KeyTemplateProtoConverter.toByteArray(JwtHmacKeyManager.hs256Template());
    KeysetGenerateResponse keysetResponse = generateKeyset(keysetStub, template);
    assertThat(keysetResponse.getErr()).isEmpty();
    byte[] keyset = keysetResponse.getKeyset().toByteArray();

    JwtToken token = generateToken("audience", Instant.now().plusSeconds(100));

    JwtSignRequest signRequest =
        JwtSignRequest.newBuilder().setKeyset(ByteString.copyFrom(keyset)).setRawJwt(token).build();
    JwtSignResponse signResponse = jwtStub.computeMacAndEncode(signRequest);
    assertThat(signResponse.getErr()).isEmpty();

    JwtValidator validator =
        JwtValidator.newBuilder()
            .setAudience(StringValue.newBuilder().setValue("audience"))
            .setNow(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
            .build();
    JwtVerifyRequest verifyRequest =
        JwtVerifyRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(keyset))
            .setSignedCompactJwt(signResponse.getSignedCompactJwt())
            .setValidator(validator)
            .build();

    JwtVerifyResponse verifyResponse = jwtStub.verifyMacAndDecode(verifyRequest);
    assertThat(verifyResponse.getErr()).isEmpty();
    assertThat(verifyResponse.getVerifiedJwt()).isEqualTo(token);
  }

  @Test
  public void jwtEmptyTokenComputeVerifyMac_success() throws Exception {
    byte[] template = KeyTemplateProtoConverter.toByteArray(JwtHmacKeyManager.hs256Template());
    KeysetGenerateResponse keysetResponse = generateKeyset(keysetStub, template);
    assertThat(keysetResponse.getErr()).isEmpty();
    byte[] keyset = keysetResponse.getKeyset().toByteArray();

    JwtToken token = JwtToken.getDefaultInstance();

    JwtSignRequest signRequest =
        JwtSignRequest.newBuilder().setKeyset(ByteString.copyFrom(keyset)).setRawJwt(token).build();
    JwtSignResponse signResponse = jwtStub.computeMacAndEncode(signRequest);
    assertThat(signResponse.getErr()).isEmpty();

    JwtValidator validator = JwtValidator.getDefaultInstance();
    JwtVerifyRequest verifyRequest =
        JwtVerifyRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(keyset))
            .setSignedCompactJwt(signResponse.getSignedCompactJwt())
            .setValidator(validator)
            .build();

    JwtVerifyResponse verifyResponse = jwtStub.verifyMacAndDecode(verifyRequest);
    assertThat(verifyResponse.getErr()).isEmpty();
    assertThat(verifyResponse.getVerifiedJwt()).isEqualTo(token);
  }

  @Test
  public void publicKeySignVerify_success() throws Exception {
    byte[] template = KeyTemplateProtoConverter.toByteArray(
        JwtEcdsaSignKeyManager.jwtES256Template());
    KeysetGenerateResponse keysetResponse = generateKeyset(keysetStub, template);
    assertThat(keysetResponse.getErr()).isEmpty();
    byte[] privateKeyset = keysetResponse.getKeyset().toByteArray();

    KeysetPublicResponse pubResponse = publicKeyset(keysetStub, privateKeyset);
    assertThat(pubResponse.getErr()).isEmpty();
    byte[] publicKeyset = pubResponse.getPublicKeyset().toByteArray();

    JwtToken token = generateToken("audience", Instant.now().plusSeconds(100));

    JwtSignRequest signRequest =
        JwtSignRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(privateKeyset))
            .setRawJwt(token)
            .build();
    JwtSignResponse signResponse = jwtStub.publicKeySignAndEncode(signRequest);
    assertThat(signResponse.getErr()).isEmpty();

    JwtValidator validator =
        JwtValidator.newBuilder()
            .setAudience(StringValue.newBuilder().setValue("audience"))
            .setNow(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
            .build();
    JwtVerifyRequest verifyRequest =
        JwtVerifyRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(publicKeyset))
            .setSignedCompactJwt(signResponse.getSignedCompactJwt())
            .setValidator(validator)
            .build();

    JwtVerifyResponse verifyResponse = jwtStub.publicKeyVerifyAndDecode(verifyRequest);
    assertThat(verifyResponse.getErr()).isEmpty();
    assertThat(verifyResponse.getVerifiedJwt()).isEqualTo(token);
  }

  @Test
  public void signFailsOnBadKeyset() throws Exception {
    byte[] badKeyset = "bad keyset".getBytes(UTF_8);

    JwtToken token = generateToken("audience", Instant.now().plusSeconds(100));
    JwtSignRequest signRequest =
        JwtSignRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(badKeyset))
            .setRawJwt(token)
            .build();
    JwtSignResponse signResponse = jwtStub.computeMacAndEncode(signRequest);
    assertThat(signResponse.getErr()).isNotEmpty();
  }

  @Test
  public void verifyFailsWhenExpired() throws Exception {
    byte[] template = KeyTemplateProtoConverter.toByteArray(JwtHmacKeyManager.hs256Template());
    KeysetGenerateResponse keysetResponse = generateKeyset(keysetStub, template);
    assertThat(keysetResponse.getErr()).isEmpty();
    byte[] keyset = keysetResponse.getKeyset().toByteArray();

    JwtToken token = generateToken("audience", Instant.now().plusSeconds(-10));

    JwtSignRequest signRequest =
        JwtSignRequest.newBuilder().setKeyset(ByteString.copyFrom(keyset)).setRawJwt(token).build();
    JwtSignResponse signResponse = jwtStub.computeMacAndEncode(signRequest);
    assertThat(signResponse.getErr()).isEmpty();

    JwtValidator validator =
        JwtValidator.newBuilder()
            .setAudience(StringValue.newBuilder().setValue("audience"))
            .setNow(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
            .build();
    JwtVerifyRequest verifyRequest =
        JwtVerifyRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(keyset))
            .setSignedCompactJwt(signResponse.getSignedCompactJwt())
            .setValidator(validator)
            .build();

    JwtVerifyResponse verifyResponse = jwtStub.verifyMacAndDecode(verifyRequest);
    assertThat(verifyResponse.getErr()).isNotEmpty();
  }

  @Test
  public void verifyFailsWithWrongAudience() throws Exception {
    byte[] template = KeyTemplateProtoConverter.toByteArray(JwtHmacKeyManager.hs256Template());
    KeysetGenerateResponse keysetResponse = generateKeyset(keysetStub, template);
    assertThat(keysetResponse.getErr()).isEmpty();
    byte[] keyset = keysetResponse.getKeyset().toByteArray();

    JwtToken token = generateToken("wrong_audience", Instant.now().plusSeconds(100));

    JwtSignRequest signRequest =
        JwtSignRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(keyset))
            .setRawJwt(token)
            .build();
    JwtSignResponse signResponse = jwtStub.computeMacAndEncode(signRequest);
    assertThat(signResponse.getErr()).isEmpty();

    JwtValidator validator =
        JwtValidator.newBuilder()
            .setAudience(StringValue.newBuilder().setValue("audience"))
            .setNow(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
            .build();
    JwtVerifyRequest verifyRequest =
        JwtVerifyRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(keyset))
            .setSignedCompactJwt(signResponse.getSignedCompactJwt())
            .setValidator(validator)
            .build();

    JwtVerifyResponse verifyResponse = jwtStub.verifyMacAndDecode(verifyRequest);
    assertThat(verifyResponse.getErr()).isNotEmpty();
  }

  @Test
  public void verifyFailsWithWrongKey() throws Exception {
    byte[] template = KeyTemplateProtoConverter.toByteArray(JwtHmacKeyManager.hs256Template());

    KeysetGenerateResponse keysetResponse = generateKeyset(keysetStub, template);
    assertThat(keysetResponse.getErr()).isEmpty();
    byte[] keyset = keysetResponse.getKeyset().toByteArray();

    JwtToken token = generateToken("audience", Instant.now().plusSeconds(100));

    JwtSignRequest signRequest =
        JwtSignRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(keyset))
            .setRawJwt(token)
            .build();
    JwtSignResponse signResponse = jwtStub.computeMacAndEncode(signRequest);
    assertThat(signResponse.getErr()).isEmpty();

    KeysetGenerateResponse wrongKeysetResponse = generateKeyset(keysetStub, template);
    assertThat(wrongKeysetResponse.getErr()).isEmpty();
    byte[] wrongKeyset = wrongKeysetResponse.getKeyset().toByteArray();

    JwtValidator validator =
        JwtValidator.newBuilder()
            .setAudience(StringValue.newBuilder().setValue("audience"))
            .build();
    JwtVerifyRequest verifyRequest =
        JwtVerifyRequest.newBuilder()
            .setKeyset(ByteString.copyFrom(wrongKeyset))
            .setSignedCompactJwt(signResponse.getSignedCompactJwt())
            .setValidator(validator)
            .build();

    JwtVerifyResponse verifyResponse = jwtStub.verifyMacAndDecode(verifyRequest);
    assertThat(verifyResponse.getErr()).isNotEmpty();
  }
}
