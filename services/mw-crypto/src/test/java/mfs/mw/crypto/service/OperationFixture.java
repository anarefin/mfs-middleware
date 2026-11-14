package mfs.mw.crypto.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.spec.MGF1ParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.crypto.client.FakeCryptoProvider;
import mfs.mw.crypto.config.OperationAuthzProperties;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.dto.EncryptedPin;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.repository.KeyRepository;

/**
 * The operation services over the provider SPI without an HSM: {@link FakeCryptoProvider} holds the
 * keys, a mocked {@link KeyRepository} the metadata the test declares with {@link #key}.
 */
final class OperationFixture {

  static final Instant T0 = Instant.parse("2026-11-14T08:00:00Z");

  final FakeCryptoProvider hsm = new FakeCryptoProvider();
  final KeyRepository repo = mock(KeyRepository.class);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final HsmKeys keys = new HsmKeys(repo, List.of(hsm), meters);
  /** Allows every caller: the per-operation rules are tested in controller/OperationAuthzTest. */
  final CallerPolicy callers =
      new CallerPolicy(new OperationAuthzProperties(null, null)) {
        @Override
        public void requirePin(String operation) {}

        @Override
        public void requirePurpose(String purpose, String operation) {}
      };
  private final List<CryptoKey> rows = new ArrayList<>();

  OperationFixture() {
    when(repo.findVersions(anyString()))
        .thenAnswer(i -> rows.stream().filter(k -> k.keyId().equals(i.getArgument(0))).toList());
    when(repo.findByPurpose(anyString()))
        .thenAnswer(i -> rows.stream().filter(k -> k.purpose().equals(i.getArgument(0))).toList());
  }

  /** Declares a key version and generates its material in the fake HSM. */
  OperationFixture key(String keyId, int version, String purpose, KeyState state) {
    KeyPurpose p = KeyPurpose.parse(purpose);
    CryptoKey k = new CryptoKey(keyId, version, purpose, p.algorithm().name(), "pkcs11",
        CryptoKey.kid(keyId, version), state, T0, null);
    rows.add(k);
    hsm.generateKey(k.hsmLabel(), p.algorithm());
    return this;
  }

  /** The channel's PIN block (RSA-OAEP SHA-256, MGF1-SHA-256) under transport key version {@code kid}. */
  EncryptedPin encrypt(String kid, String pin) {
    return new EncryptedPin(kid, Base64.getEncoder().encodeToString(oaep(kid, pin.getBytes(StandardCharsets.US_ASCII))));
  }

  byte[] oaep(String kid, byte[] clear) {
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
      c.init(Cipher.ENCRYPT_MODE, hsm.publicKey(keys.resolve(kid, HsmKeys.Use.CHECK, p -> true, "test").label()),
          new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      return c.doFinal(clear);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
