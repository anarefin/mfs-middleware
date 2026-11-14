package mfs.mw.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.TokenProperties;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.TokenPair;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.entity.RefreshToken;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The access JWT and the refresh token of a login (spec 18.7, D21): claims, lifetimes, the RS256
 * signature by mw-crypto ({@link CryptoClient} answers with a JWS made here by an in-test RSA key,
 * as mw-crypto would), the refresh token's shape and storage (only its SHA-256), rotation and
 * reuse detection.
 */
class TokenServiceTest {

  static final Instant NOW = Instant.parse("2026-11-14T06:00:00Z");
  static final TokenProperties PROPS =
      new TokenProperties("mw-auth", "mw-app", Duration.ofMinutes(5), Duration.ofDays(30), Duration.ofMinutes(5),
          "http://mw-auth:8080/auth/v1/.well-known/jwks.json");
  static KeyPair rsa;

  final CryptoClient crypto = mock(CryptoClient.class);
  final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
  final TokenService tokens = new TokenService(crypto, refresh, PROPS, Clock.fixed(NOW, ZoneOffset.UTC));

  static final AuthWallet WALLET =
      new AuthWallet("01711000001", "tok", "tokenise-msisdn.v1", "{}", "AGENT", "ACTIVE", true, null, 3);

  @BeforeAll
  static void key() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    rsa = g.generateKeyPair();
  }

  /** mw-crypto's /jws/sign, played by the in-test key: header kid = the key version. */
  @SuppressWarnings("unchecked")
  void cryptoSigns() {
    when(crypto.signJws(eq("JWT_SIGN"), eq("RS256"), any(), any())).thenAnswer(inv -> {
      Map<String, Object> header = inv.getArgument(2);
      Map<String, Object> claims = inv.getArgument(3);
      JWSHeader h = new JWSHeader.Builder(JWSAlgorithm.RS256)
          .type(new JOSEObjectType((String) header.get("typ"))).keyID("jwt-sign.v1").build();
      SignedJWT jwt = new SignedJWT(h, JWTClaimsSet.parse(claims));
      jwt.sign(new RSASSASigner((RSAPrivateKey) rsa.getPrivate()));
      return new CryptoJwsSigned(jwt.serialize(), "jwt-sign.v1", "RS256");
    });
  }

  @Test
  void theAccessTokenCarriesTheWalletDeviceAndMethodsForFiveMinutes() throws Exception {
    cryptoSigns();
    TokenPair pair = tokens.issue(WALLET, "01711000001", "device-0001", List.of("pin", "device"));

    SignedJWT jwt = SignedJWT.parse(pair.accessToken());
    assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) rsa.getPublic()))).isTrue();
    assertThat(jwt.getHeader().getType().getType()).isEqualTo("at+jwt");
    JWTClaimsSet c = jwt.getJWTClaimsSet();
    assertThat(c.getIssuer()).isEqualTo("mw-auth");
    assertThat(c.getAudience()).containsExactly("mw-app");
    assertThat(c.getSubject()).isEqualTo("01711000001");
    assertThat(c.getStringClaim("msisdn")).isEqualTo("01711000001");
    assertThat(c.getStringClaim("role")).isEqualTo("agent");
    assertThat(c.getStringClaim("deviceId")).isEqualTo("device-0001");
    assertThat(c.getStringListClaim("amr")).containsExactly("pin", "device");
    assertThat(c.getStringClaim("sid")).isEqualTo(pair.sessionId());
    assertThat(c.getJWTID()).isNotBlank();
    assertThat(c.getIssueTime().toInstant()).isEqualTo(NOW);
    assertThat(c.getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(300));
    assertThat(pair.expiresIn()).isEqualTo(300);
    assertThat(pair.tokenType()).isEqualTo("Bearer");
  }

  @Test
  void theRoleFollowsTheWalletType() throws Exception {
    cryptoSigns();
    for (var e : Map.of("CUSTOMER", "customer", "MERCHANT", "merchant", "DISTRIBUTOR", "distributor", "DSO", "dso")
        .entrySet()) {
      AuthWallet w = new AuthWallet("01711000001", "t", "k", "{}", e.getKey(), "ACTIVE", true, null, 1);
      String role = SignedJWT.parse(tokens.issue(w, "01711000001", "device-0001", List.of("pin", "device"))
          .accessToken()).getJWTClaimsSet().getStringClaim("role");
      assertThat(role).as(e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void anOrgWalletGetsNoAppToken() {
    AuthWallet org = new AuthWallet("01311000001", "t", "k", "{}", "ORG", "ACTIVE", true, null, 1);
    assertThatThrownBy(() -> tokens.issue(org, "01311000001", "device-0001", List.of("pin", "device")))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-002"));
    verify(crypto, never()).signJws(anyString(), anyString(), any(), any());
  }

  @Test
  void theRefreshTokenIs256RandomBitsStoredOnlyAsItsSha256ForThirtyDays() throws Exception {
    cryptoSigns();
    TokenPair pair = tokens.issue(WALLET, "01711000001", "device-0001", List.of("pin", "device"));

    byte[] raw = Base64.getUrlDecoder().decode(pair.refreshToken());
    assertThat(raw).hasSize(32);
    assertThat(pair.refreshExpiresIn()).isEqualTo(Duration.ofDays(30).toSeconds());
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(refresh).insert(hash.capture(), eq(UUID.fromString(pair.sessionId())), eq("01711000001"),
        eq("device-0001"), eq(NOW.plus(Duration.ofDays(30))), eq(List.of("pin", "device")));
    assertThat(hash.getValue())
        .isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(pair.refreshToken().getBytes(java.nio.charset.StandardCharsets.US_ASCII))))
        .isNotEqualTo(pair.refreshToken());
    assertThat(tokens.issue(WALLET, "01711000001", "device-0001", List.of("pin", "device")).refreshToken())
        .isNotEqualTo(pair.refreshToken());
  }

  @Test
  void aRotatedTokenUsedAgainRevokesItsWholeFamily() {
    UUID family = UUID.randomUUID();
    when(refresh.rotate(anyString(), eq(NOW))).thenReturn(Optional.empty());
    when(refresh.find(anyString())).thenReturn(Optional.of(new RefreshToken(
        "h", family, "01711000001", "device-0001", NOW.plusSeconds(60), NOW.minusSeconds(10), false,
        List.of("pin", "device"))));

    assertThatThrownBy(() -> tokens.rotate("used-token", "device-0001"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-048"));
    verify(refresh).revokeFamily(family);
  }

  @Test
  void anUnknownOrExpiredRefreshTokenIsRefusedWithoutRevokingAnything() {
    when(refresh.rotate(anyString(), eq(NOW))).thenReturn(Optional.empty());
    when(refresh.find(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> tokens.rotate("nobody", "device-0001"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-001"));
    verify(refresh, never()).revokeFamily(any());
  }

  @Test
  void aRefreshTokenOfAnotherDeviceIsRefusedAndRevoked() {
    UUID family = UUID.randomUUID();
    when(refresh.rotate(anyString(), eq(NOW))).thenReturn(Optional.of(new RefreshToken(
        "h", family, "01711000001", "device-0001", NOW.plusSeconds(60), null, false, List.of("pin", "device"))));
    assertThatThrownBy(() -> tokens.rotate("stolen", "device-9999"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-047"));
    verify(refresh).revokeFamily(family);
  }

  @Test
  void rotationKeepsTheSessionAndMethodsAndIssuesANewRefreshToken() {
    UUID family = UUID.randomUUID();
    when(refresh.rotate(anyString(), eq(NOW))).thenReturn(Optional.of(new RefreshToken(
        "h", family, "01711000001", "device-0001", NOW.plusSeconds(60), null, false, List.of("otp", "device"))));
    RefreshToken current = tokens.rotate("current", "device-0001");
    assertThat(current.family()).isEqualTo(family);
    assertThat(current.amr()).containsExactly("otp", "device");
  }
}
