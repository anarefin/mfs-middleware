package mfs.mw.admin.api.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Throw-away client certificates for the credential tests (ticket 131), made by the JDK's keytool
 * in a temp folder (no key material in the repo; the adapters' {@code TestKeystores} do the same).
 * One obviously fake store password for all.
 */
public final class TestCertificates {

  public static final String PASSWORD = "fake-store-password";

  private static final Path DIR = createDir();

  private TestCertificates() {}

  /** A key pair with a self-signed certificate valid for {@code days} days ({@code keyalg} RSA or EC). */
  public static Generated generate(String alias, String keyalg, int days) {
    Path store = DIR.resolve(alias + "-" + System.nanoTime() + ".p12");
    List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", alias, "-keyalg", keyalg));
    if ("EC".equals(keyalg)) {
      args.addAll(List.of("-groupname", "secp256r1"));
    }
    args.addAll(List.of("-dname", "CN=" + alias + ",O=Bits Test", "-validity", Integer.toString(days),
        "-keystore", store.toString()));
    keytool(args);
    try (InputStream in = Files.newInputStream(store)) {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(in, PASSWORD.toCharArray());
      Key key = ks.getKey(alias, PASSWORD.toCharArray());
      Certificate cert = ks.getCertificate(alias);
      return new Generated(
          pem("CERTIFICATE", cert.getEncoded()), pem("PRIVATE KEY", key.getEncoded()),
          Base64.getEncoder().encodeToString(Files.readAllBytes(store)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The PEM texts and the PKCS#12 store (base64, password {@link #PASSWORD}). */
  public record Generated(String certificatePem, String privateKeyPem, String pkcs12) {}

  private static String pem(String type, byte[] der) {
    return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(der) + "\n-----END " + type + "-----\n";
  }

  private static Path createDir() {
    try {
      return Files.createTempDirectory("mw-credentials");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void keytool(List<String> args) {
    List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
    command.addAll(args);
    command.addAll(List.of("-storetype", "PKCS12", "-storepass", PASSWORD));
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + out);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
