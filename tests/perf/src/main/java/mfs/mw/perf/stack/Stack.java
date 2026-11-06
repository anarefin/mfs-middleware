package mfs.mw.perf.stack;

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
 * The running MVP stack as the perf suite sees it (as the former Cucumber suite's {@code Stack}, copied: the
 * suites share no code): the APISIX edge URL (the only published port) and {@code docker compose}
 * on the stack's project for what no API offers (Keycloak user provisioning, the post-run
 * read-only queries of the core's outbox and the audit trail).
 *
 * <p>Configuration (system properties forwarded by Gradle, or environment): {@code
 * mw.perf.base-url} / {@code MW_PERF_BASE_URL} (default {@code https://localhost:<port>} with the
 * port Compose published for {@code apisix:9443}), {@code COMPOSE_PROJECT_NAME} (default {@code
 * mfs-mw}), {@code mw.perf.root} (repository root, set by the build). Credentials are the generated
 * dev ones in {@code deploy/.env} ({@code scripts/gen-dev-env.sh}).
 */
public final class Stack {

  private static volatile Stack instance;

  private final Path root;
  private final Map<String, String> devEnv;
  private volatile URI baseUrl;

  private Stack(Path root) {
    this.root = root;
    this.devEnv = readEnvFile(root.resolve("deploy/.env"));
  }

  public static Stack get() {
    Stack s = instance;
    if (s == null) {
      synchronized (Stack.class) {
        s = instance;
        if (s == null) {
          s = new Stack(Path.of(System.getProperty("mw.perf.root", "../..")).toAbsolutePath().normalize());
          instance = s;
        }
      }
    }
    return s;
  }

  /** The APISIX edge, e.g. {@code https://localhost:443/}. */
  public URI baseUrl() {
    URI u = baseUrl;
    if (u == null) {
      u = resolveBaseUrl();
      baseUrl = u;
    }
    return u;
  }

  public URI url(String path) {
    return baseUrl().resolve(path);
  }

  /** A generated dev credential (e.g. {@code KC_CUSTOMERS_TEST_PASSWORD}); fails when missing. */
  public String devCredential(String name) {
    String v = System.getenv(name);
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
   * One read-only SQL statement as the PostgreSQL superuser inside the {@code postgres} container;
   * psql's unaligned, tuples-only output.
   */
  public String psql(String sql) {
    String user = devEnv.getOrDefault("POSTGRES_USER", "mw_admin");
    String db = devEnv.getOrDefault("POSTGRES_DB", "mw");
    return compose(
            null,
            "exec", "-T", "-e", "PGTZ=UTC", "postgres",
            "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db, "-c", sql)
        .strip();
  }

  /** {@code docker compose -f deploy/docker-compose.yml <args>} with {@code input} on stdin; stdout. */
  public String compose(String input, String... args) {
    List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", root.resolve("deploy/docker-compose.yml").toString()));
    cmd.addAll(List.of(args));
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.environment().putAll(devEnv);
      Process p = pb.start();
      try (var stdin = p.getOutputStream()) {
        if (input != null) {
          stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
      }
      var err = p.getErrorStream();
      Thread drain = Thread.ofVirtual().start(() -> {
        try {
          err.readAllBytes();
        } catch (IOException ignored) {
          // nothing to report
        }
      });
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(300, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("timed out: docker compose " + args[0]);
      }
      drain.join();
      if (p.exitValue() != 0) {
        throw new IllegalStateException("docker compose " + args[0] + " failed (" + p.exitValue() + ")");
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
    String configured = System.getProperty("mw.perf.base-url", System.getenv("MW_PERF_BASE_URL"));
    if (configured != null && !configured.isBlank()) {
      return URI.create(configured.endsWith("/") ? configured : configured + "/");
    }
    String published = compose(null, "port", "apisix", "9443").strip().lines().findFirst().orElse("");
    int colon = published.lastIndexOf(':');
    if (colon < 0) {
      throw new IllegalStateException("APISIX publishes no port: is the stack up (`make up`)?");
    }
    return URI.create("https://localhost:" + published.substring(colon + 1) + "/");
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
