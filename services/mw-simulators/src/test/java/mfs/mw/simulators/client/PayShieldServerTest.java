package mfs.mw.simulators.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import mfs.mw.simulators.config.PayShieldProperties;
import mfs.mw.simulators.dto.PayShieldScenario;
import mfs.mw.simulators.service.PayShieldKeys;
import mfs.mw.simulators.service.PayShieldScenarioStore;
import mfs.mw.simulators.service.PayShieldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The payShield simulator's TCP front (ticket 64): a 2-byte big-endian length, the header (4
 * characters by default) echoed in the answer, several commands on one connection, and the
 * scenario's latency and outage.
 */
class PayShieldServerTest {

  PayShieldScenarioStore scenario = new PayShieldScenarioStore();
  PayShieldServer server;

  @BeforeEach
  void start() {
    PayShieldProperties props = new PayShieldProperties(true, 0, 4, "memory", false, null);
    server = new PayShieldServer(new PayShieldService(new PayShieldKeys(new InMemoryLmkStore()), scenario, false), scenario,
        props);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  static String exchange(DataOutputStream out, DataInputStream in, String message) throws IOException {
    byte[] b = message.getBytes(StandardCharsets.US_ASCII);
    out.writeShort(b.length);
    out.write(b);
    out.flush();
    byte[] r = new byte[in.readUnsignedShort()];
    in.readFully(r);
    return new String(r, StandardCharsets.US_ASCII);
  }

  @Test
  void answersEveryFramedCommandWithItsHeader() throws IOException {
    try (Socket s = new Socket("127.0.0.1", server.port())) {
      var out = new DataOutputStream(s.getOutputStream());
      var in = new DataInputStream(s.getInputStream());
      assertThat(exchange(out, in, "0001NC")).startsWith("0001ND00");
      assertThat(exchange(out, in, "ab12A00001U")).matches("ab12A100U[0-9A-F]{38}");
      assertThat(exchange(out, in, "0003XY")).isEqualTo("0003XZ68");
    }
  }

  @Test
  void latencyDelaysTheAnswer() throws IOException {
    scenario.set(new PayShieldScenario(300L, null, null, null));
    try (Socket s = new Socket("127.0.0.1", server.port())) {
      long t0 = System.nanoTime();
      exchange(new DataOutputStream(s.getOutputStream()), new DataInputStream(s.getInputStream()), "0001NC");
      assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }
  }

  @Test
  void anOutageClosesTheConnectionWithoutAnAnswer() throws IOException {
    scenario.set(new PayShieldScenario(null, null, true, null));
    try (Socket s = new Socket("127.0.0.1", server.port())) {
      s.setSoTimeout(5000);
      var out = new DataOutputStream(s.getOutputStream());
      var in = new DataInputStream(s.getInputStream());
      assertThatThrownBy(() -> exchange(out, in, "0001NC")).isInstanceOfAny(EOFException.class, IOException.class);
    }
  }
}
