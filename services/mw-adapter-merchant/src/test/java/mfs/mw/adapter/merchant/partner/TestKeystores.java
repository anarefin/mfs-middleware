package mfs.mw.adapter.merchant.partner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Throw-away PKCS#12 key stores for the mTLS tests (ticket 87), made by the JDK's keytool in a
 * temp folder when first used (no key material in the repo): a server key ({@code CN=localhost},
 * SAN localhost / 127.0.0.1), a trust store holding it ({@code client-trust}), two client keys
 * ({@code good}, {@code wrong}) and the server's trust store holding only {@code good}. One
 * obviously fake password for all.
 */
public final class TestKeystores {

  public static final String PASSWORD = "fake-store-password";

  private static final Path DIR = create();

  private TestKeystores() {}

  /** Path of key store {@code name}. */
  public static String store(String name) {
    return DIR.resolve(name + ".p12").toString();
  }

  /** Key store {@code name} as base64, as it is kept in Vault. */
  public static String base64(String name) {
    try {
      return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(store(name))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path create() {
    Path dir;
    try {
      dir = Files.createTempDirectory("mw-mtls");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String server = dir.resolve("server.p12").toString();
    keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
        "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-keystore", server);
    keytool("-exportcert", "-alias", "server", "-keystore", server, "-file", dir.resolve("server.crt").toString());
    keytool("-importcert", "-noprompt", "-alias", "server", "-file", dir.resolve("server.crt").toString(),
        "-keystore", dir.resolve("client-trust.p12").toString());
    for (String client : List.of("good", "wrong")) {
      keytool("-genkeypair", "-alias", client, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=bits-" + client,
          "-validity", "2", "-keystore", dir.resolve(client + ".p12").toString());
    }
    keytool("-exportcert", "-alias", "good", "-keystore", dir.resolve("good.p12").toString(),
        "-file", dir.resolve("good.crt").toString());
    keytool("-importcert", "-noprompt", "-alias", "good", "-file", dir.resolve("good.crt").toString(),
        "-keystore", dir.resolve("server-trust.p12").toString());
    return dir;
  }

  private static void keytool(String... args) {
    List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
    command.addAll(List.of(args));
    command.addAll(List.of("-storetype", "PKCS12", "-storepass", PASSWORD));
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
        throw new IllegalStateException("keytool " + args[0] + " failed: " + out);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
