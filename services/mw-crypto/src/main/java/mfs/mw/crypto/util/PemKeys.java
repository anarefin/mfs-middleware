package mfs.mw.crypto.util;

import java.io.ByteArrayInputStream;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.crypto.exception.CryptoErrors;

/**
 * A partner key given in a verify request (ticket 65): an X.509 certificate ({@code BEGIN
 * CERTIFICATE}) or a public key ({@code BEGIN PUBLIC KEY}) in PEM. Only RSA of at least 2048 bits and
 * EC P-256 are accepted; anything else is {@code MW-VAL-001} on {@code field}.
 *
 * @param key the public key
 * @param validNow false for a certificate outside its validity period at the time asked
 */
public record PemKeys(PublicKey key, boolean validNow) {

  private static final Pattern PEM =
      Pattern.compile("-----BEGIN (CERTIFICATE|PUBLIC KEY)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----\\s*");

  public static PemKeys parse(String field, String pem, Instant now) {
    Matcher m = PEM.matcher(pem.strip() + "\n");
    if (!m.matches()) {
      throw CryptoErrors.invalidField(field, "one PEM certificate or public key");
    }
    try {
      byte[] der = Base64.getMimeDecoder().decode(m.group(2));
      PublicKey key;
      boolean valid = true;
      if (m.group(1).equals("CERTIFICATE")) {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(der));
        key = cert.getPublicKey();
        try {
          cert.checkValidity(Date.from(now));
        } catch (CertificateExpiredException | CertificateNotYetValidException e) {
          valid = false;
        }
      } else {
        key = publicKey(der);
      }
      if (!(key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= 2048)
          && !(key instanceof ECPublicKey ec && isP256(ec))) {
        throw CryptoErrors.invalidField(field, "an RSA key of at least 2048 bits or an EC P-256 key");
      }
      return new PemKeys(key, valid);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw CryptoErrors.invalidField(field, "one PEM certificate or public key");
    }
  }

  private static boolean isP256(ECPublicKey key) throws GeneralSecurityException {
    AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
    p.init(new ECGenParameterSpec("secp256r1"));
    ECParameterSpec p256 = p.getParameterSpec(ECParameterSpec.class);
    return key.getParams().getOrder().equals(p256.getOrder())
        && key.getParams().getCurve().equals(p256.getCurve())
        && key.getParams().getGenerator().equals(p256.getGenerator());
  }

  private static PublicKey publicKey(byte[] der) throws GeneralSecurityException {
    X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
    try {
      return KeyFactory.getInstance("RSA").generatePublic(spec);
    } catch (GeneralSecurityException e) {
      return KeyFactory.getInstance("EC").generatePublic(spec);
    }
  }
}
