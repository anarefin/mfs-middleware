package mfs.mw.core.mock.security;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import org.springframework.stereotype.Component;

/**
 * The core's PIN key pair (spec M11). The PKCS#8 private key comes from configuration (OpenBao KV
 * in a stack); the public key is derived from it and exported for channels and tests. Parsed on
 * first use, so a context without a key (e.g. the image's AOT training run) still starts.
 */
@Component
public class PinKeyProvider {

  private final CoreMockProperties.Pin config;
  private volatile PrivateKey privateKey;
  private volatile PublicKey publicKey;

  @org.springframework.beans.factory.annotation.Autowired
  public PinKeyProvider(CoreMockProperties props) {
    this(props.pin());
  }

  PinKeyProvider(CoreMockProperties.Pin config) {
    this.config = config;
  }

  public String keyId() {
    return config.keyId();
  }

  public PrivateKey privateKey() {
    load();
    return privateKey;
  }

  /** The public key as PEM (X.509 SubjectPublicKeyInfo), 64 characters per line. */
  public String publicKeyPem() {
    load();
    return "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(publicKey.getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
  }

  private void load() {
    if (privateKey != null) {
      return;
    }
    synchronized (this) {
      if (privateKey != null) {
        return;
      }
      String raw = config.privateKey();
      if (raw == null || raw.isBlank()) {
        throw new MwException(ErrorCodes.SYS_INTERNAL, "no PIN private key configured")
            .withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
      }
      try {
        byte[] der = Base64.getMimeDecoder().decode(raw.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(der));
        publicKey = rsa.generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        privateKey = key;
      } catch (java.security.GeneralSecurityException | RuntimeException e) {
        throw new MwException(ErrorCodes.SYS_INTERNAL, "PIN private key is not a PKCS#8 RSA key", e);
      }
    }
  }
}
