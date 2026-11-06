package mfs.mw.e2e.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The running MVP stack as the suite sees it: the APISIX edge URL (the only published port) and,
 * for the few checks no API offers yet, {@code docker compose} on the stack's project.
 *
 * <p>Configuration (system properties forwarded by Gradle, or environment):
 *
 * <ul>
 *   <li>{@code mw.e2e.base-url} / {@code MW_E2E_BASE_URL}: edge URL; default {@code
 *       https://localhost:<port>} with the port Compose published for {@code apisix:9443}
 *   <li>{@code COMPOSE_PROJECT_NAME}: the stack's project (per-worktree stacks), default {@code mfs-mw} (deploy/docker-compose.yml)
 *   <li>{@code mw.e2e.root}: repository root (set by the build)
 * </ul>
 *
 * Test users' passwords are the generated dev credentials in {@code deploy/.env}
 * ({@code scripts/gen-dev-env.sh}), read the same way {@code make smoke} reads them.
 */
public final class Stack {

  private static volatile Stack instance;

  private final Path root;
  private final Map<String, String> devEnv;
  private final URI baseUrl;

  private Stack(Path root) {
    this.root = root;
    this.devEnv = readEnvFile(root.resolve("deploy/.env"));
    this.baseUrl = resolveBaseUrl();
  }

  /** The stack under test (resolved once per JVM). */
  public static Stack get() {
    Stack s = instance;
    if (s == null) {
      synchronized (Stack.class) {
        s = instance;
        if (s == null) {
          String r = System.getProperty("mw.e2e.root", "../..");
          s = new Stack(Path.of(r).toAbsolutePath().normalize());
          instance = s;
        }
      }
    }
    return s;
  }

  /** The APISIX edge, e.g. {@code https://localhost:443}. */
  public URI baseUrl() {
    return baseUrl;
  }

  public URI url(String path) {
    return baseUrl.resolve(path);
  }

  /** A generated dev credential (e.g. {@code KC_CUSTOMERS_TEST_PASSWORD}); fails when missing. */
  public String devCredential(String name) {
    String v = setting("mw.e2e." + name.toLowerCase().replace('_', '-'), name);
    if (v == null) {
      v = devEnv.get(name);
    }
    if (v == null || v.isEmpty()) {
      throw new IllegalStateException(
          name + " not found: run `make up` first (it generates deploy/.env)");
    }
    return v;
  }

  /**
   * Runs one SQL statement as the PostgreSQL superuser inside the {@code postgres} container
   * ({@code docker compose exec}) and returns psql's unaligned, tuples-only output. Test-only
   * read access for data no API exposes yet (the audit trail); never used to change state.
   */
  public String psql(String sql) {
    String user = devEnv.getOrDefault("POSTGRES_USER", "mw_admin");
    String db = devEnv.getOrDefault("POSTGRES_DB", "mw");
    return compose(
            "exec", "-T", "-e", "PGTZ=UTC", "postgres",
            "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db, "-c", sql)
        .strip();
  }

  /** {@code docker compose -f deploy/docker-compose.yml <args>}; returns stdout. */
  public String compose(String... args) {
    List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", root.resolve("deploy/docker-compose.yml").toString()));
    cmd.addAll(List.of(args));
    return run(cmd, null);
  }

  /** As {@link #compose}, with {@code input} written to the command's stdin. */
  public String composeWithInput(String input, String... args) {
    List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", root.resolve("deploy/docker-compose.yml").toString()));
    cmd.addAll(List.of(args));
    return run(cmd, input);
  }

  private String run(List<String> cmd, String input) {
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      // Compose interpolates the platform file with the generated credentials, as make up does.
      pb.environment().putAll(devEnv);
      pb.redirectErrorStream(false);
      Process p = pb.start();
      try (var stdin = p.getOutputStream()) {
        if (input != null) {
          stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
      }
      byte[] out = p.getInputStream().readAllBytes();
      byte[] err = p.getErrorStream().readAllBytes();
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("timed out: " + String.join(" ", cmd.subList(0, 4)));
      }
      if (p.exitValue() != 0) {
        throw new IllegalStateException(
            "command failed (" + p.exitValue() + "): " + new String(err, StandardCharsets.UTF_8).strip());
      }
      return new String(out, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private URI resolveBaseUrl() {
    String configured = setting("mw.e2e.base-url", "MW_E2E_BASE_URL");
    if (configured != null && !configured.isBlank()) {
      return URI.create(configured.endsWith("/") ? configured : configured + "/");
    }
    // e.g. "0.0.0.0:55012" (one line per address family)
    String published = compose("port", "apisix", "9443").strip().lines().findFirst().orElse("");
    int colon = published.lastIndexOf(':');
    if (colon < 0) {
      throw new IllegalStateException("APISIX publishes no port: is the stack up (`make up`)?");
    }
    return URI.create("https://localhost:" + published.substring(colon + 1) + "/");
  }

  private static String setting(String property, String env) {
    String v = System.getProperty(property);
    return v != null ? v : System.getenv(env);
  }

  private static Map<String, String> readEnvFile(Path file) {
    Map<String, String> env = new HashMap<>();
    if (!Files.isRegularFile(file)) {
      return env;
    }
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String l = line.strip();
        int eq = l.indexOf('=');
        if (l.isEmpty() || l.startsWith("#") || eq < 1) {
          continue;
        }
        String value = l.substring(eq + 1);
        if (value.length() >= 2
            && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
          value = value.substring(1, value.length() - 1);
        }
        env.put(l.substring(0, eq), value);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return env;
  }
}
