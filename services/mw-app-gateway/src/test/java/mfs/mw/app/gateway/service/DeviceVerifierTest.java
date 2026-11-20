package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.DeviceBindingProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.BoundDevice;
import mfs.mw.app.gateway.dto.LiveSession;
import mfs.mw.app.gateway.dto.SignedRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.repository.DeviceGateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The device key cache against a rebinding (security review of ticket 68, fixed in ticket 69): the
 * gateway caches a bound device's wallet and key ({@code devkey:{deviceId}}, 5 min), but mw-auth's
 * session answer carries the current key's fingerprint, so once the session confirmation is asked
 * again (at most {@code session-cache-ttl} later) a cached key of a replaced binding is dropped and
 * the device fetched again: the old key no longer signs anything, and a device moved to another
 * wallet no longer serves this one. mw-auth and Redis are in-memory fakes; a fresh confirmation is
 * what the 10 s cache expiring gives.
 */
class DeviceVerifierTest {

  static final String WALLET = "01711000001";
  static final AppUser USER = new AppUser(WALLET, "CUSTOMER_APP", "CUSTOMER");

  /** mw-auth: bound devices and live sessions. */
  final Map<String, BoundDevice> bound = new HashMap<>();
  final Set<String> live = new HashSet<>();
  /** Redis: the gate's cached keys and session confirmations (no TTL: tests expire them by hand). */
  final Map<String, DeviceGateStore.CachedKey> keys = new HashMap<>();
  final Map<String, String> confirmed = new HashMap<>();
  int deviceLookups;

  final AuthClient auth = mock(AuthClient.class);

  @BeforeEach
  void mwAuth() {
    when(auth.device(anyString())).thenAnswer(i -> {
      deviceLookups++;
      return Optional.ofNullable(bound.get(i.<String>getArgument(0)));
    });
    when(auth.session(anyString(), anyString())).thenAnswer(i -> {
      String walletId = i.getArgument(0);
      String deviceId = i.getArgument(1);
      BoundDevice d = bound.get(deviceId);
      if (d == null || !d.walletId().equals(walletId) || !live.contains(walletId + ":" + deviceId)) {
        return Optional.empty();
      }
      return Optional.of(new LiveSession(walletId, deviceId, 300, DeviceVerifier.fingerprint(d.publicKey()), d.boundAt(), 1L));
    });
  }

  final DeviceGateStore store = new DeviceGateStore(null) {
    @Override
    public Optional<CachedKey> deviceKey(String deviceId) {
      return Optional.ofNullable(keys.get(deviceId));
    }

    @Override
    public void putDeviceKey(String deviceId, CachedKey key, Duration ttl) {
      keys.put(deviceId, key);
    }

    @Override
    public void evictDeviceKey(String deviceId) {
      keys.remove(deviceId);
    }

    @Override
    public Optional<String> sessionConfirmed(String walletId, String deviceId) {
      return Optional.ofNullable(confirmed.get(walletId + ":" + deviceId));
    }

    @Override
    public void confirmSession(String walletId, String deviceId, String fingerprint, Duration ttl) {
      confirmed.put(walletId + ":" + deviceId, fingerprint);
    }

    @Override
    public boolean firstUse(String deviceId, String nonce, Duration ttl) {
      return true;
    }
  };

  final DeviceBindingProperties props = new DeviceBindingProperties(
      true, Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofSeconds(10));
  DeviceVerifier verifier;

  @BeforeEach
  void verifier() {
    verifier = new DeviceVerifier(auth, store, props, Clock.systemUTC());
  }

  static KeyPair ec() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  static String b64(KeyPair k) {
    return Base64.getEncoder().encodeToString(k.getPublic().getEncoded());
  }

  static SignedRequest signed(String deviceId, KeyPair key) throws Exception {
    String ts = Long.toString(Instant.now().toEpochMilli());
    String nonce = "n-" + UUID.randomUUID();
    String bodyHash = RequestSignatures.sha256Hex("{}".getBytes(StandardCharsets.UTF_8));
    String canonical = String.join("\n", "POST", "/app/v1/transactions", bodyHash, ts, nonce, deviceId);
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(key.getPrivate());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    return new SignedRequest(deviceId, "POST", "/app/v1/transactions", bodyHash, ts, nonce,
        Base64.getEncoder().encodeToString(s.sign()));
  }

  void bind(String deviceId, String wallet, KeyPair key) {
    bound.put(deviceId, new BoundDevice(deviceId, wallet, b64(key), Instant.now()));
    live.add(wallet + ":" + deviceId);
  }

  /** What the session confirmation cache (10 s) expiring does. */
  void confirmationExpires() {
    confirmed.clear();
  }

  @Test
  void aKeyReplacedAtMwAuthIsDroppedAtTheNextSessionConfirmation() throws Exception {
    String device = "dev-" + UUID.randomUUID();
    KeyPair oldKey = ec();
    bind(device, WALLET, oldKey);
    verifier.verifySigned(USER, signed(device, oldKey));
    assertThat(keys.get(device).publicKey()).isEqualTo(b64(oldKey));

    KeyPair newKey = ec();
    bind(device, WALLET, newKey); // rebinding the same device id with a new key, session open again
    confirmationExpires();

    assertThatThrownBy(() -> verifier.verifySigned(USER, signed(device, oldKey)))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-004"));
    assertThat(keys.get(device).publicKey()).as("the cached key was replaced").isEqualTo(b64(newKey));
    assertThatCode(() -> verifier.verifySigned(USER, signed(device, newKey))).doesNotThrowAnyException();
  }

  @Test
  void aDeviceMovedToAnotherWalletNoLongerServesThisOne() throws Exception {
    String device = "dev-" + UUID.randomUUID();
    KeyPair key = ec();
    bind(device, WALLET, key);
    verifier.checkSession(USER, device);

    bind(device, "01711000099", key);
    live.remove(WALLET + ":" + device);
    confirmationExpires();

    assertThatThrownBy(() -> verifier.checkSession(USER, device)).isInstanceOf(MwException.class);
    assertThat(keys).as("the stale binding is evicted").doesNotContainKey(device);
    assertThatThrownBy(() -> verifier.checkSession(USER, device))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-003"));
  }

  @Test
  void aConfirmedSessionWithTheSameKeyAsksNothing() throws Exception {
    String device = "dev-" + UUID.randomUUID();
    KeyPair key = ec();
    bind(device, WALLET, key);
    verifier.checkSession(USER, device);
    verifier.checkSession(USER, device);
    assertThat(deviceLookups).isEqualTo(1);
  }
}
