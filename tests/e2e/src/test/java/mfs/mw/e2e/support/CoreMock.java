package mfs.mw.e2e.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import tools.jackson.databind.JsonNode;

/**
 * mw-core-mock's test/admin API through the test-only APISIX route {@code /test/core-mock/v1/*}
 * (deploy/apisix/routes-test/test-core-mock.json, applied only with {@code MW_E2E_ROUTES=1},
 * which {@code make e2e} sets): the PIN public key, wallet balances and fault injection. The route
 * requires key-auth with the generated dev key {@code MW_E2E_API_KEY} (header {@code apikey}).
 */
public final class CoreMock {

  private static final String BASE = "test/core-mock/v1/";

  private static volatile PinKey pinKey;

  private CoreMock() {}

  /** The core's PIN encryption key as exported by core-mock. */
  public record PinKey(String keyId, PublicKey key) {}

  /** A wallet's balances, minor units. */
  public record Balance(String walletId, long balanceMinor, long heldMinor, long availableMinor) {}

  public static PinKey pinKey() {
    PinKey k = pinKey;
    if (k == null) {
      Http.Response r = Http.get(Stack.get().url(BASE + "pin-public-key"), auth());
      expectOk(r, "PIN public key");
      k = new PinKey(r.body().get("keyId").asString(), parsePem(r.body().get("publicKeyPem").asString()));
      pinKey = k;
    }
    return k;
  }

  /**
   * The PIN encrypted the way an app does it: RSA-OAEP with SHA-256 (hash and MGF1) under the core
   * public key, Base64. Returns {@code {keyId, ciphertext}} as the app API expects.
   */
  public static Map<String, String> encryptPin(String pin) {
    PinKey k = pinKey();
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
      c.init(
          Cipher.ENCRYPT_MODE,
          k.key(),
          new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      byte[] ct = c.doFinal(pin.getBytes(StandardCharsets.UTF_8));
      return Map.of("keyId", k.keyId(), "ciphertext", Base64.getEncoder().encodeToString(ct));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("PIN encryption failed", e);
    }
  }

  public static Balance balance(String walletId) {
    Http.Response r = Http.get(Stack.get().url(BASE + "wallets/" + walletId), auth());
    expectOk(r, "balance of " + walletId);
    JsonNode b = r.body();
    return new Balance(
        b.get("walletId").asString(),
        b.get("balanceMinor").asLong(),
        b.get("heldMinor").asLong(),
        b.get("availableMinor").asLong());
  }

  /** Injects a fault ({@code {mode, commandType?, txnId?, delayMs?, reasonCode?, times?}}). */
  public static JsonNode injectFault(JsonNode fault) {
    Http.Response r = Http.postJson(Stack.get().url(BASE + "faults"), auth(), fault);
    if (r.status() != 201) {
      throw new IllegalStateException("fault not accepted: " + r);
    }
    return r.body();
  }

  /** Removes every injected fault (call after a scenario that injected one). */
  public static void resetFaults() {
    Http.Response r = Http.delete(Stack.get().url(BASE + "faults"), auth());
    if (r.status() != 204) {
      throw new IllegalStateException("fault reset failed: " + r);
    }
  }

  private static Map<String, String> auth() {
    return Map.of("apikey", Stack.get().devCredential("MW_E2E_API_KEY"));
  }

  private static void expectOk(Http.Response r, String what) {
    if (r.status() != 200) {
      throw new IllegalStateException(what + ": " + r);
    }
  }

  private static PublicKey parsePem(String pem) {
    String b64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("bad PIN public key", e);
    }
  }
}
