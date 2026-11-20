package mfs.mw.admin.api.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.admin.api.dto.CredentialChangeBody;
import mfs.mw.admin.api.dto.CredentialType;
import mfs.mw.admin.api.dto.ErrorDetail;

/**
 * Checks an uploaded partner credential and turns it into the fields of the partner's Vault secret
 * {@code secret/<adapter>/<partnerId>} (ticket 131, ADR D28 / 131 split decision): an API key or
 * HMAC secret is one field named after the credential (what {@code auth.credentialsRef} names); a
 * client certificate is the two fields the adapters' mTLS contexts read, {@code <name>-keystore}
 * (a base64 PKCS#12 store) and {@code <name>-keystore-password} (a PEM upload gets a fresh random
 * password). Besides the fields only metadata comes out (fingerprint, subject, serial, notAfter);
 * no problem message repeats any part of the upload.
 */
public final class CredentialMaterial {

  /** A secret's name: what {@code auth.credentialsRef} and the adapters' secret names accept. */
  public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  /** A certificate's name: room left for the {@code -keystore-password} suffix within {@link #NAME}. */
  public static final Pattern CERT_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,45}$");

  static final String KEYSTORE = "-keystore";
  static final String KEYSTORE_PASSWORD = "-keystore-password";
  static final int MIN_SECRET = 16;

  private static final Pattern PEM_BLOCK =
      Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

  private CredentialMaterial() {}

  /** What goes to Vault, and what may be shown. {@link #toString()} never shows the fields' values. */
  public record Prepared(Map<String, String> fields, Meta meta) {
    public Prepared {
      fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    @Override
    public String toString() {
      return "Prepared[fields=" + fields.keySet() + ", meta=" + meta + "]";
    }
  }

  /** Metadata of a credential: never secret. Certificates only: fingerprint, subject, serial. */
  public record Meta(String fingerprint, String subject, String serialNumber, Instant notAfter) {}

  /**
   * The Vault fields and metadata of {@code body} for credential {@code name}; problems go to
   * {@code problems} (then the result is null).
   */
  public static Prepared prepare(
      String name, CredentialChangeBody body, Instant now, SecureRandom random, List<ErrorDetail> problems) {
    int before = problems.size();
    Prepared p = body.type() == CredentialType.CLIENT_CERT
        ? certificate(name, body, now, random, problems)
        : secret(name, body, now, problems);
    return problems.size() == before ? p : null;
  }

  private static Prepared secret(String name, CredentialChangeBody b, Instant now, List<ErrorDetail> problems) {
    if (name == null || !NAME.matcher(name).matches()) {
      problems.add(new ErrorDetail("name", "lower-case letters, digits and hyphens, at most 64"));
    }
    if (b.secret() == null || b.secret().length() < MIN_SECRET || b.secret().isBlank()) {
      problems.add(new ErrorDetail("secret", "a " + b.type() + " needs a secret of at least " + MIN_SECRET + " characters"));
    }
    for (String[] f : new String[][] {{"certificatePem", b.certificatePem()}, {"privateKeyPem", b.privateKeyPem()},
        {"pkcs12", b.pkcs12()}, {"pkcs12Password", b.pkcs12Password()}}) {
      if (f[1] != null) {
        problems.add(new ErrorDetail(f[0], "only a CLIENT_CERT takes certificate fields"));
      }
    }
    if (b.notAfter() != null && !b.notAfter().isAfter(now)) {
      problems.add(new ErrorDetail("notAfter", "must be in the future"));
    }
    return new Prepared(name == null ? Map.of() : Map.of(name, b.secret() == null ? "" : b.secret()),
        new Meta(null, null, null, b.notAfter()));
  }

  private static Prepared certificate(
      String name, CredentialChangeBody b, Instant now, SecureRandom random, List<ErrorDetail> problems) {
    if (name == null || !CERT_NAME.matcher(name).matches()) {
      problems.add(new ErrorDetail("name", "a certificate name is lower-case letters, digits and hyphens, at most 46"));
    }
    if (b.secret() != null) {
      problems.add(new ErrorDetail("secret", "a CLIENT_CERT takes certificate fields, not a secret"));
    }
    if (b.notAfter() != null) {
      problems.add(new ErrorDetail("notAfter", "a certificate's expiry is read from the certificate"));
    }
    boolean pem = b.certificatePem() != null || b.privateKeyPem() != null;
    boolean store = b.pkcs12() != null || b.pkcs12Password() != null;
    if (pem == store) {
      problems.add(new ErrorDetail("pkcs12",
          "give either certificatePem and privateKeyPem, or pkcs12 and pkcs12Password"));
      return null;
    }
    return pem ? fromPem(name, b, now, random, problems) : fromPkcs12(name, b, now, problems);
  }

  private static Prepared fromPem(
      String name, CredentialChangeBody b, Instant now, SecureRandom random, List<ErrorDetail> problems) {
    List<X509Certificate> chain = certificates(b.certificatePem());
    if (chain.isEmpty()) {
      problems.add(new ErrorDetail("certificatePem", "not a PEM certificate (BEGIN CERTIFICATE)"));
    }
    PrivateKey key = privateKey(b.privateKeyPem());
    if (key == null) {
      problems.add(new ErrorDetail("privateKeyPem",
          "not a PKCS#8 PEM private key (BEGIN PRIVATE KEY; convert with openssl pkcs8 -topk8 -nocrypt)"));
    }
    if (chain.isEmpty() || key == null) {
      return null;
    }
    if (!matches(key, chain.get(0))) {
      problems.add(new ErrorDetail("privateKeyPem", "the private key does not belong to the certificate"));
      return null;
    }
    if (!valid(chain.get(0), now, "certificatePem", problems)) {
      return null;
    }
    byte[] pw = new byte[24];
    random.nextBytes(pw);
    String password = Base64.getUrlEncoder().withoutPadding().encodeToString(pw);
    try {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(null, null);
      ks.setKeyEntry(name, key, password.toCharArray(), chain.toArray(Certificate[]::new));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ks.store(out, password.toCharArray());
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put(name + KEYSTORE, Base64.getEncoder().encodeToString(out.toByteArray()));
      fields.put(name + KEYSTORE_PASSWORD, password);
      return new Prepared(fields, meta(chain.get(0)));
    } catch (GeneralSecurityException | java.io.IOException e) {
      problems.add(new ErrorDetail("privateKeyPem", "the key and certificate cannot be stored as PKCS#12"));
      return null;
    }
  }

  private static Prepared fromPkcs12(String name, CredentialChangeBody b, Instant now, List<ErrorDetail> problems) {
    if (b.pkcs12() == null || b.pkcs12Password() == null) {
      problems.add(new ErrorDetail("pkcs12", "a PKCS#12 upload needs pkcs12 and pkcs12Password"));
      return null;
    }
    byte[] bytes;
    try {
      bytes = Base64.getMimeDecoder().decode(b.pkcs12().strip());
    } catch (IllegalArgumentException e) {
      problems.add(new ErrorDetail("pkcs12", "not base64"));
      return null;
    }
    KeyStore ks;
    String alias = null;
    try {
      ks = KeyStore.getInstance("PKCS12");
      ks.load(new ByteArrayInputStream(bytes), b.pkcs12Password().toCharArray());
      for (String a : Collections.list(ks.aliases())) {
        if (ks.isKeyEntry(a)) {
          if (alias != null) {
            problems.add(new ErrorDetail("pkcs12", "the store must hold exactly one key"));
            return null;
          }
          alias = a;
        }
      }
      if (alias == null) {
        problems.add(new ErrorDetail("pkcs12", "the store holds no private key"));
        return null;
      }
      PrivateKey key = (PrivateKey) ks.getKey(alias, b.pkcs12Password().toCharArray());
      if (!(ks.getCertificate(alias) instanceof X509Certificate cert) || key == null || !matches(key, cert)) {
        problems.add(new ErrorDetail("pkcs12", "the store's key has no matching X.509 certificate"));
        return null;
      }
      if (!valid(cert, now, "pkcs12", problems)) {
        return null;
      }
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put(name + KEYSTORE, Base64.getEncoder().encodeToString(bytes));
      fields.put(name + KEYSTORE_PASSWORD, b.pkcs12Password());
      return new Prepared(fields, meta(cert));
    } catch (GeneralSecurityException | java.io.IOException | RuntimeException e) {
      problems.add(new ErrorDetail("pkcs12", "the store cannot be opened with the given password"));
      return null;
    }
  }

  private static boolean valid(X509Certificate cert, Instant now, String field, List<ErrorDetail> problems) {
    if (!cert.getNotAfter().toInstant().isAfter(now)) {
      problems.add(new ErrorDetail(field, "the certificate has expired"));
      return false;
    }
    return true;
  }

  private static Meta meta(X509Certificate cert) {
    try {
      String fingerprint = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
      return new Meta(fingerprint, cert.getSubjectX500Principal().getName(), cert.getSerialNumber().toString(16),
          cert.getNotAfter().toInstant());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  private static List<X509Certificate> certificates(String pem) {
    if (pem == null || !pem.contains("-----BEGIN CERTIFICATE-----")) {
      return List.of();
    }
    try {
      Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509")
          .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
      return certs.stream().filter(X509Certificate.class::isInstance).map(X509Certificate.class::cast).toList();
    } catch (GeneralSecurityException | RuntimeException e) {
      return List.of();
    }
  }

  private static PrivateKey privateKey(String pem) {
    if (pem == null) {
      return null;
    }
    Matcher m = PEM_BLOCK.matcher(pem);
    if (!m.find() || !"PRIVATE KEY".equals(m.group(1))) {
      return null;
    }
    byte[] der;
    try {
      der = Base64.getMimeDecoder().decode(m.group(2).replaceAll("\\s", ""));
    } catch (IllegalArgumentException e) {
      return null;
    }
    for (String algorithm : List.of("RSA", "EC", "Ed25519", "Ed448")) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
      } catch (GeneralSecurityException | RuntimeException e) {
        // expected: try the next algorithm
      }
    }
    return null;
  }

  /** Whether {@code key} signs what the certificate's public key verifies. */
  private static boolean matches(PrivateKey key, X509Certificate cert) {
    String algorithm = switch (key.getAlgorithm()) {
      case "RSA" -> "SHA256withRSA";
      case "EC" -> "SHA256withECDSA";
      case "Ed25519", "Ed448", "EdDSA" -> key.getAlgorithm();
      default -> null;
    };
    if (algorithm == null) {
      return false;
    }
    try {
      byte[] probe = "mw-admin-api credential check".getBytes(StandardCharsets.US_ASCII);
      Signature s = Signature.getInstance(algorithm);
      s.initSign(key);
      s.update(probe);
      byte[] sig = s.sign();
      Signature v = Signature.getInstance(algorithm);
      v.initVerify(cert.getPublicKey());
      v.update(probe);
      return v.verify(sig);
    } catch (GeneralSecurityException | RuntimeException e) {
      return false;
    }
  }
}
