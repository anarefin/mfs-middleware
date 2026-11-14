package mfs.mw.simulators.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Provider;
import java.security.ProviderException;
import java.security.Security;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import mfs.mw.simulators.config.PayShieldProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The payShield simulator's LMK in the SoftHSM {@code paysim} token (ticket 64, spec 18.8, ticket 61):
 * JDK SunPKCS11 against the image's p11-kit proxy, whose one module dials {@code softhsm:5658}
 * (deploy/softhsm/README.md). On first use it logs in with the user PIN from OpenBao ({@code
 * sim.payshield.pkcs11.user-pin}, never logged; the char copy is zeroed) and finds the AES-256 key
 * labelled {@code lmk}, or generates it as a sensitive, non-extractable token object. Over the p11-kit
 * RPC the token only encrypts ({@code AES/ECB/NoPadding}; an ECB or CBC decrypt fails with {@code
 * CKR_FUNCTION_NOT_SUPPORTED}, as mw-crypto found), so a 16-byte block under the LMK is a 4-round
 * Feistel network whose round function is the token's AES encryption of the half block and the round
 * number: invertible with encryption only, every round in the token. A failure drops the session (the
 * next call logs in again) and throws
 * {@link IllegalStateException}. This code runs only inside the mw-simulators image: the host has no
 * PKCS#11 tooling (PayShieldSimulatorIT drives it there).
 */
public class Pkcs11LmkStore implements LmkStore {

  private static final Logger log = LoggerFactory.getLogger(Pkcs11LmkStore.class);

  private final PayShieldProperties.Pkcs11 props;
  private Provider provider;
  private SecretKey lmk;

  public Pkcs11LmkStore(PayShieldProperties.Pkcs11 props) {
    this.props = props;
  }

  private static final int ROUNDS = 4;

  @Override
  public synchronized byte[] encrypt(byte[] data) {
    return feistel(data, true, this::run);
  }

  @Override
  public synchronized byte[] decrypt(byte[] data) {
    return feistel(data, false, this::run);
  }

  /**
   * Every 16-byte block of {@code data} through the Feistel network, {@code aes} the AES-ECB
   * encryption under the LMK (one call per round for all blocks).
   */
  static byte[] feistel(byte[] data, boolean forward, UnaryOperator<byte[]> aes) {
    if (data.length % 16 != 0) {
      throw new IllegalArgumentException("LMK data must be whole 16-byte blocks");
    }
    int n = data.length / 16;
    byte[][] left = new byte[n][];
    byte[][] right = new byte[n][];
    for (int b = 0; b < n; b++) {
      left[b] = Arrays.copyOfRange(data, b * 16, b * 16 + 8);
      right[b] = Arrays.copyOfRange(data, b * 16 + 8, b * 16 + 16);
    }
    for (int i = 0; i < ROUNDS; i++) {
      int round = forward ? i : ROUNDS - 1 - i;
      // forward: (L, R) -> (R, L ^ F(R)); backward: (L, R) -> (R ^ F(L), L)
      byte[][] in = forward ? right : left;
      byte[] f = roundFunction(in, round, aes);
      for (int b = 0; b < n; b++) {
        byte[] mixed = new byte[8];
        byte[] other = forward ? left[b] : right[b];
        for (int j = 0; j < 8; j++) {
          mixed[j] = (byte) (other[j] ^ f[b * 16 + j]);
        }
        if (forward) {
          left[b] = right[b];
          right[b] = mixed;
        } else {
          right[b] = left[b];
          left[b] = mixed;
        }
      }
    }
    byte[] out = new byte[data.length];
    for (int b = 0; b < n; b++) {
      System.arraycopy(left[b], 0, out, b * 16, 8);
      System.arraycopy(right[b], 0, out, b * 16 + 8, 8);
    }
    return out;
  }

  /** AES under the LMK, in the token, of every half block followed by the round number (16 bytes each). */
  private static byte[] roundFunction(byte[][] halves, int round, UnaryOperator<byte[]> aes) {
    byte[] in = new byte[halves.length * 16];
    for (int b = 0; b < halves.length; b++) {
      System.arraycopy(halves[b], 0, in, b * 16, 8);
      in[b * 16 + 15] = (byte) round;
    }
    return aes.apply(in);
  }

  private byte[] run(byte[] data) {
    try {
      if (lmk == null) {
        connect();
      }
      Cipher c = Cipher.getInstance("AES/ECB/NoPadding", provider);
      c.init(Cipher.ENCRYPT_MODE, lmk);
      return c.doFinal(data);
    } catch (GeneralSecurityException | ProviderException e) {
      lmk = null;
      log.atWarn().addKeyValue("event", "payshield.lmk_failed").addKeyValue("error", e.getClass().getSimpleName())
          .log("LMK operation in the paysim token failed; the next call logs in again");
      throw new IllegalStateException("LMK unavailable", e);
    }
  }

  private void connect() throws GeneralSecurityException {
    if (!Files.isReadable(Path.of(props.library()))) {
      throw new GeneralSecurityException("PKCS#11 library not found");
    }
    if (props.userPin().isEmpty()) {
      throw new GeneralSecurityException("no PKCS#11 user PIN configured");
    }
    if (provider == null) {
      String config = String.join("\n",
          "name = paysim",
          "library = " + props.library(),
          "slotListIndex = " + props.slotListIndex(),
          "attributes(*, CKO_SECRET_KEY, *) = {",
          "  CKA_SENSITIVE = true",
          "  CKA_EXTRACTABLE = false",
          "}",
          "");
      try {
        provider = Security.getProvider("SunPKCS11").configure("--" + config);
      } catch (ProviderException | IllegalArgumentException e) {
        throw new GeneralSecurityException("PKCS#11 provider not configured", e);
      }
    }
    KeyStore ks = KeyStore.getInstance("PKCS11", provider);
    char[] pin = props.userPin().toCharArray();
    try {
      ks.load(null, pin);
    } catch (IOException e) {
      throw new GeneralSecurityException("PKCS#11 login failed", e);
    } finally {
      Arrays.fill(pin, '\0');
    }
    if (ks.getKey(props.lmkLabel(), null) instanceof SecretKey existing) {
      lmk = existing;
    } else {
      KeyGenerator g = KeyGenerator.getInstance("AES", provider);
      g.init(256);
      ks.setEntry(props.lmkLabel(), new KeyStore.SecretKeyEntry(g.generateKey()), null);
      lmk = (SecretKey) ks.getKey(props.lmkLabel(), null);
      log.atInfo().addKeyValue("event", "payshield.lmk_generated").log("LMK generated in the paysim token");
    }
    log.atInfo().addKeyValue("event", "payshield.lmk_connected").log("Logged in to the paysim token");
  }
}
