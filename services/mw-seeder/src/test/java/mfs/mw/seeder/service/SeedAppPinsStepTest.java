package mfs.mw.seeder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.seeder.client.AuthSeeds;
import mfs.mw.seeder.dto.PinKeyView;
import mfs.mw.seeder.dto.SeedManifest;
import mfs.mw.seeder.dto.SeedPinsRequest;
import mfs.mw.seeder.dto.SeedPinsResult;
import mfs.mw.seeder.exception.RemoteCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * seed-app-pins (ticket 69): every app wallet (the seeded CUSTOMER, AGENT and MERCHANT wallets plus
 * the extra ones of the realm imports' app users) gets the dev app PIN at mw-auth, encrypted as an app
 * would encrypt it (RSA-OAEP SHA-256 under the PIN transport key of {@code GET /auth/v1/pin-key}), so
 * mw-auth never sees it in clear. Idempotent; a wallet mw-auth has not projected yet makes the step
 * retry; the manifest names the PIN's {@code deploy/.env} variable, never the PIN.
 */
class SeedAppPinsStepTest {

  /** mw-auth: the transport key, and first PINs set once per wallet (decrypted with the private key). */
  static final class FakeAuth implements AuthSeeds {
    final KeyPair rsa;
    final Set<String> projected = new HashSet<>();
    final List<String> clearPins = new ArrayList<>();
    final Set<String> withPin = new HashSet<>();

    FakeAuth() throws Exception {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      rsa = g.generateKeyPair();
    }

    @Override
    public PinKeyView pinKey() {
      String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
          .encodeToString(rsa.getPublic().getEncoded());
      return new PinKeyView("pin-transport.v1", "RSA-OAEP-SHA-256",
          "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n");
    }

    @Override
    public SeedPinsResult seedPins(SeedPinsRequest request) {
      List<SeedPinsResult.Outcome> out = new ArrayList<>();
      for (SeedPinsRequest.Pin p : request.pins()) {
        assertThat(p.encryptedPin().keyId()).isEqualTo("pin-transport.v1");
        if (!projected.contains(p.walletId())) {
          out.add(new SeedPinsResult.Outcome(p.walletId(), "UNKNOWN_WALLET"));
        } else if (!withPin.add(p.walletId())) {
          out.add(new SeedPinsResult.Outcome(p.walletId(), "UNCHANGED"));
        } else {
          clearPins.add(decrypt(p.encryptedPin().ciphertext()));
          out.add(new SeedPinsResult.Outcome(p.walletId(), "CREATED"));
        }
      }
      return new SeedPinsResult(out);
    }

    String decrypt(String b64) {
      try {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(Cipher.DECRYPT_MODE, rsa.getPrivate(), new OAEPParameterSpec("SHA-256", "MGF1",
            MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return new String(c.doFinal(Base64.getDecoder().decode(b64)), StandardCharsets.US_ASCII);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  FakeAuth auth;
  SeedAppPinsStep step;

  @BeforeEach
  void setUp() throws Exception {
    auth = new FakeAuth();
    step = new SeedAppPinsStep(auth, SeedFixtures.props(Path.of("unused")));
  }

  @Test
  void everyAppWalletGetsTheEncryptedDevPinOnce() {
    auth.projected.addAll(List.of("01712000001", "01812000001", "01711000001"));
    SeedContext ctx = new SeedContext();

    assertThat(step.run(ctx)).isEqualTo(StepResult.created(3));
    assertThat(auth.clearPins).containsOnly("13579").hasSize(3);
    assertThat(ctx.appPins()).isEqualTo(
        new SeedManifest.AppPins("MW_SEED_APP_PIN", List.of("01712000001", "01812000001", "01711000001")));

    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.unchanged(3));
  }

  @Test
  void aWalletNotProjectedYetMakesTheStepRetry() {
    auth.projected.add("01712000001");
    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOfSatisfying(RemoteCallException.class, e -> assertThat(e.retryable()).isTrue())
        .hasMessageNotContaining("01812000001");
  }
}
