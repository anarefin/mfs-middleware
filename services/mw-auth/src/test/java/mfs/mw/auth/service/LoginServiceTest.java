package mfs.mw.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.AppTypePolicy;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.SignedRequest;
import mfs.mw.auth.dto.TokenPair;
import mfs.mw.auth.entity.AuthDevice;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.DeviceRepository;
import mfs.mw.auth.repository.DeviceSessionStore;
import mfs.mw.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Login of an organisation wallet (core type ORG). Only a wallet the seeder marked as a partner user
 * gets an app token, and that token's role is partner. The session uses the partner app type.
 */
class LoginServiceTest {

  static final AppTypePolicy POLICY = new AppTypePolicy(
      new AppTypePolicy.Settings(Duration.ofMinutes(5), 1, 1, Duration.ofMinutes(5), 5, Duration.ofHours(24)),
      Map.of("PARTNER", new AppTypePolicy.Settings(null, null, null, Duration.ofMinutes(9), null, null)));
  static KeyPair rsa;
  static String deviceKey;

  final CryptoClient crypto = mock(CryptoClient.class);
  final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
  final TokenService tokens =
      new TokenService(crypto, refreshTokens, TokenServiceTest.PROPS, POLICY, Clock.fixed(TokenServiceTest.NOW, ZoneOffset.UTC));
  final WalletAccess wallets = mock(WalletAccess.class);
  final DeviceRepository devices = mock(DeviceRepository.class);
  final SignedRequestCheck signed = mock(SignedRequestCheck.class);
  final DeviceSessionStore sessions = mock(DeviceSessionStore.class);
  final LoginService login = new LoginService(
      wallets, devices, signed, mock(PinService.class), mock(PinVerifier.class), mock(AuditTrail.class),
      mock(org.springframework.transaction.support.TransactionTemplate.class), mock(DeviceOtpService.class), tokens,
      refreshTokens, mock(CredentialRepository.class), sessions, POLICY, mock(PinNotices.class),
      Clock.fixed(TokenServiceTest.NOW, ZoneOffset.UTC));

  @BeforeAll
  static void keys() throws Exception {
    KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
    rsaGen.initialize(2048);
    rsa = rsaGen.generateKeyPair();
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    deviceKey = Base64.getEncoder().encodeToString(ec.generateKeyPair().getPublic().getEncoded());
  }

  @SuppressWarnings("unchecked")
  void cryptoSigns() {
    when(crypto.signJws(eq("JWT_SIGN"), eq("RS256"), any(), any())).thenAnswer(inv -> {
      Map<String, Object> header = inv.getArgument(2);
      Map<String, Object> claims = inv.getArgument(3);
      JWSHeader h = new JWSHeader.Builder(JWSAlgorithm.RS256)
          .type(new JOSEObjectType((String) header.get("typ"))).keyID("jwt-sign.v1").build();
      SignedJWT jwt = new SignedJWT(h, com.nimbusds.jwt.JWTClaimsSet.parse(claims));
      jwt.sign(new RSASSASigner((RSAPrivateKey) rsa.getPrivate()));
      return new CryptoJwsSigned(jwt.serialize(), "jwt-sign.v1", "RS256");
    });
  }

  @Test
  void aMarkedOrganisationWalletLogsInAsAPartner() throws Exception {
    cryptoSigns();
    AuthWallet marked = new AuthWallet("01412000001", "t", "k", "{}", "ORG", "ACTIVE", true, null, 1, "PARTNER");
    when(wallets.byMsisdn("01412000001")).thenReturn(Optional.of(marked));
    when(wallets.hasPin("01412000001")).thenReturn(true);
    when(devices.find("device-0001")).thenReturn(Optional.of(
        new AuthDevice("device-0001", "01412000001", deviceKey, TokenServiceTest.NOW, null, 1)));
    when(sessions.admit(eq("01412000001"), eq("device-0001"), any(), anyInt(), eq(TokenServiceTest.NOW)))
        .thenReturn(List.of());

    TokenPair pair = login.login("01412000001", new EncryptedPin("pin-transport.v1", "Y2lwaGVy"), null, signed());

    SignedJWT jwt = SignedJWT.parse(pair.accessToken());
    assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) rsa.getPublic()))).isTrue();
    assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("partner");
    assertThat(pair.idleTimeoutSeconds()).isEqualTo(Duration.ofMinutes(9).toSeconds());
    verify(sessions).admit(eq("01412000001"), eq("device-0001"), eq(Duration.ofMinutes(9)), eq(1), eq(TokenServiceTest.NOW));
  }

  @Test
  void anUnmarkedOrganisationWalletGetsNoAppToken() {
    AuthWallet org = new AuthWallet("01312000001", "t", "k", "{}", "ORG", "ACTIVE", true, null, 1);
    when(wallets.byMsisdn("01312000001")).thenReturn(Optional.of(org));

    assertThatThrownBy(() -> login.login("01312000001", new EncryptedPin("pin-transport.v1", "Y2lwaGVy"), null, signed()))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-002"));
    verify(crypto, never()).signJws(anyString(), anyString(), any(), any());
  }

  private static SignedRequest signed() {
    return new SignedRequest("device-0001", "POST", "/auth/v1/sessions", "ab", "1", "nonce-0123456789abcdef", "c2ln");
  }
}
