package mfs.mw.simulators.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.SplittableRandom;
import mfs.mw.simulators.config.PayShieldProperties;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.service.PayShieldKeys;
import mfs.mw.simulators.service.PayShieldService;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.PartySwitch;
import mfs.mw.simulators.service.mockcontrol.ScenarioEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The payShield simulator's TCP front (ticket 64): a 2-byte big-endian length, the header (4
 * characters by default) echoed in the answer, several commands on one connection; and the mock party
 * {@code payshield} (ticket 102b): latency, a forced error code per command, a cut-short answer, a reset
 * connection, DOWN and MAINTENANCE.
 */
class PayShieldServerTest {

  PartySwitch switches = new PartySwitch();
  ScenarioEngine engine = new ScenarioEngine(switches, new SimulatorProperties(Duration.ofMillis(300), 10),
      new SplittableRandom(1));
  PayShieldServer server;

  @BeforeEach
  void start() {
    PayShieldProperties props = new PayShieldProperties(true, 0, 4, "memory", false, null);
    server = new PayShieldServer(new PayShieldService(new PayShieldKeys(new InMemoryLmkStore()), false), engine,
        new StaticListableBeanFactory().getBeanProvider(Journal.class), Clock.systemUTC(), props);
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

  String one(String message) throws IOException {
    try (Socket s = new Socket("127.0.0.1", server.port())) {
      s.setSoTimeout(5000);
      return exchange(new DataOutputStream(s.getOutputStream()), new DataInputStream(s.getInputStream()), message);
    }
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
  void aSlowSwitchDelaysTheAnswer() throws IOException {
    switches.set("payshield", new MockSwitch(MockSwitch.State.SLOW, 300L, 300L, null));
    long t0 = System.nanoTime();
    one("0001NC");
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
  }

  @Test
  void aBusinessErrorScenarioAnswersItsErrorCodeForItsCommandOnly() throws IOException {
    engine.add("payshield", MockScenario.of("command", MockBehaviour.BUSINESS_ERROR).withCode("68").withKey("A0"));
    assertThat(one("0001A00001U")).isEqualTo("0001A168");
    assertThat(one("0001NC")).startsWith("0001ND00");
    engine.add("payshield", MockScenario.of("command", MockBehaviour.BUSINESS_ERROR));
    assertThat(one("0001NC")).isEqualTo("0001ND15");
  }

  @Test
  void aMalformedScenarioAnswersTheResponseCodeAlone() throws IOException {
    engine.add("payshield", MockScenario.of(null, MockBehaviour.MALFORMED_RESPONSE).withNextN(1));
    assertThat(one("0001NC")).isEqualTo("0001ND");
    assertThat(one("0001NC")).startsWith("0001ND00");
  }

  @Test
  void aConnectionResetOrADownSwitchClosesTheConnectionWithoutAnAnswer() throws IOException {
    engine.add("payshield", MockScenario.of(null, MockBehaviour.CONNECTION_RESET).withNextN(1));
    assertThatThrownBy(() -> one("0001NC")).isInstanceOfAny(EOFException.class, IOException.class);
    switches.set("payshield", new MockSwitch(MockSwitch.State.DOWN, null, null, null));
    assertThatThrownBy(() -> one("0001NC")).isInstanceOfAny(EOFException.class, IOException.class);
  }

  @Test
  void maintenanceAnswersCommandDisabled() throws IOException {
    switches.set("payshield", new MockSwitch(MockSwitch.State.MAINTENANCE, null, null, null));
    assertThat(one("0001NC")).isEqualTo("0001ND68");
  }
}
