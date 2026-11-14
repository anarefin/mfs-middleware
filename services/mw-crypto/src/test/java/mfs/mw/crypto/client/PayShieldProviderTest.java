package mfs.mw.crypto.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import mfs.mw.crypto.client.PinBlockProvider.PinKey;
import mfs.mw.crypto.config.PayShieldProperties;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The payShield provider's host-command client (ticket 64) against a stand-in TCP server in this
 * test: the 2-byte length frame and the header it correlates by, the command fields it sends, how
 * the error codes map (01 -> no match, 10/11 -> MW-VAL-015, 15 -> MW-VAL-014, others -> MW-SYS-010),
 * connection reuse, timeouts and the circuit breaker. The real simulator: PayShieldProviderIT.
 */
class PayShieldProviderTest {

  static final String TPK = "U" + "1".repeat(32);
  static final String ZPK = "U" + "2".repeat(32);
  static final String ZPK_AES = "S" + "3".repeat(32);
  static final String PVK = "U" + "4".repeat(32);

  /** A stand-in payShield: answers each body with {@code answer}, records bodies and connections. */
  static final class FakeHsm implements AutoCloseable {
    final ServerSocket server;
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    volatile UnaryOperator<String> answer = b -> "XX00";
    volatile boolean wrongHeader;
    volatile long delayMs;

    FakeHsm() throws IOException {
      server = new ServerSocket(0);
      Thread.ofVirtual().start(() -> {
        while (!server.isClosed()) {
          try {
            Socket s = server.accept();
            connections.incrementAndGet();
            Thread.ofVirtual().start(() -> serve(s));
          } catch (IOException e) {
            return;
          }
        }
      });
    }

    void serve(Socket s) {
      try (s) {
        var in = new DataInputStream(s.getInputStream());
        var out = new DataOutputStream(s.getOutputStream());
        while (true) {
          byte[] m = new byte[in.readUnsignedShort()];
          in.readFully(m);
          String text = new String(m, StandardCharsets.US_ASCII);
          bodies.add(text.substring(4));
          if (delayMs > 0) {
            Thread.sleep(delayMs);
          }
          String header = wrongHeader ? "9999" : text.substring(0, 4);
          byte[] r = (header + answer.apply(text.substring(4))).getBytes(StandardCharsets.US_ASCII);
          out.writeShort(r.length);
          out.write(r);
          out.flush();
        }
      } catch (IOException | InterruptedException e) {
        // closed
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }

  FakeHsm hsm;
  PayShieldProvider provider;

  PayShieldProvider provider(Duration callTimeout) throws IOException {
    hsm = new FakeHsm();
    var props = new PayShieldProperties(true, "127.0.0.1", hsm.server.getLocalPort(), 4, 2, Duration.ofSeconds(1),
        callTimeout, Duration.ofSeconds(1), List.of(), null);
    var breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofMinutes(1)).build());
    provider = new PayShieldProvider(props, breakers);
    return provider;
  }

  @AfterEach
  void close() throws IOException {
    if (provider != null) {
      provider.shutdown();
    }
    if (hsm != null) {
      hsm.close();
    }
  }

  static String code(Throwable e) {
    return ((MwException) e).code();
  }

  @Test
  void generatesKeysWithA0AndReturnsTheBlobUnderTheLmk() throws IOException {
    provider(Duration.ofSeconds(2));
    hsm.answer = b -> "A100" + ZPK + "ABCDEF";
    assertThat(provider.createKey("zpk.v1", KeyAlgorithm.TDES_2KEY, "ZPK")).isEqualTo(ZPK);
    hsm.answer = b -> "A100" + ZPK_AES + "ABCDEF";
    assertThat(provider.createKey("zpk-aes.v1", KeyAlgorithm.AES_128, "ZPK_AES")).isEqualTo(ZPK_AES);
    assertThat(provider.createKey("tpk.v1", KeyAlgorithm.TDES_2KEY, "TPK")).isEqualTo(ZPK_AES);
    provider.createKey("pvk.v1", KeyAlgorithm.TDES_2KEY, "PVK");
    assertThat(hsm.bodies).containsExactly("A00001U", "A00001S", "A00002U", "A00002U");
    // one connection, reused
    assertThat(hsm.connections.get()).isEqualTo(1);
  }

  @Test
  void translateSendsCaOrCcWithTheAccountField() throws IOException {
    provider(Duration.ofSeconds(2));
    hsm.answer = b -> b.startsWith("CA") ? "CB0004" + "A".repeat(16) + "01" : "CD0004" + "B".repeat(32) + "48";
    String out = provider.translate(new PinKey(TPK, true), "1".repeat(16), PinBlockFormat.ISO0, new PinKey(ZPK, false),
        PinBlockFormat.ISO0, "4000001234562000");
    assertThat(out).isEqualTo("A".repeat(16));
    assertThat(hsm.bodies.getLast()).isEqualTo("CA" + TPK + ZPK + "12" + "1".repeat(16) + "0101" + "000123456200");
    String aes = provider.translate(new PinKey(ZPK, false), "1".repeat(16), PinBlockFormat.ISO3,
        new PinKey(ZPK_AES, false), PinBlockFormat.ISO4, "4000001234562000");
    assertThat(aes).isEqualTo("B".repeat(32));
    assertThat(hsm.bodies.getLast()).isEqualTo("CC" + ZPK + ZPK_AES + "12" + "1".repeat(16) + "4748" + "16"
        + "4000001234562000");
  }

  @Test
  void pvvGenerateGoesThroughThePinUnderLmk() throws IOException {
    provider(Duration.ofSeconds(2));
    hsm.answer = b -> b.startsWith("JC") ? "JD00" + "C".repeat(32) : "DH001234";
    assertThat(provider.generatePvv(PVK, new PinKey(TPK, true), "1".repeat(16), PinBlockFormat.ISO0,
        "4000001234562000", 1)).isEqualTo("1234");
    assertThat(hsm.bodies).containsExactly("JC" + TPK + "1".repeat(16) + "01" + "000123456200",
        "DG" + PVK + "C".repeat(32) + "000123456200" + "1");
  }

  @Test
  void verifyMapsZeroOneToNoMatch() throws IOException {
    provider(Duration.ofSeconds(2));
    hsm.answer = b -> "ED00";
    assertThat(provider.verifyPvv(PVK, new PinKey(ZPK, false), "1".repeat(16), PinBlockFormat.ISO0,
        "4000001234562000", 1, "1234")).isTrue();
    assertThat(hsm.bodies.getLast()).isEqualTo("EC" + ZPK + PVK + "1".repeat(16) + "01" + "000123456200" + "1"
        + "1234");
    hsm.answer = b -> "DD01";
    assertThat(provider.verifyPvv(PVK, new PinKey(TPK, true), "1".repeat(16), PinBlockFormat.ISO0,
        "4000001234562000", 1, "1234")).isFalse();
  }

  @Test
  void everyPinFailureOnVerifyIsNoMatchSoErrorCodesAreNoOracle() throws IOException {
    provider(Duration.ofSeconds(2));
    PinKey tpk = new PinKey(TPK, true);
    for (String code : new String[] {"01", "15", "20", "24"}) {
      hsm.answer = b -> "DD" + code;
      assertThat(provider.verifyPvv(PVK, tpk, "1".repeat(16), PinBlockFormat.ISO0, "4000001234562000", 1, "1234"))
          .as(code).isFalse();
    }
    // key and HSM failures do not depend on the PIN: they stay errors
    hsm.answer = b -> "DD10";
    assertThatThrownBy(() -> provider.verifyPvv(PVK, tpk, "1".repeat(16), PinBlockFormat.ISO0, "4000001234562000", 1,
        "1234")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    hsm.answer = b -> "DD68";
    assertThatThrownBy(() -> provider.verifyPvv(PVK, tpk, "1".repeat(16), PinBlockFormat.ISO0, "4000001234562000", 1,
        "1234")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-SYS-010"));
  }

  @Test
  void aFormatTheHsmDisallowsIsAKeyNotUsable() throws IOException {
    provider(Duration.ofSeconds(2));
    hsm.answer = b -> "CB69";
    assertThatThrownBy(() -> provider.translate(new PinKey(TPK, true), "1".repeat(16), PinBlockFormat.ISO0,
        new PinKey(ZPK, false), PinBlockFormat.ISO1, "4000001234562000"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
  }

  @Test
  void errorCodesMapToTheEnvelope() throws IOException {
    provider(Duration.ofSeconds(2));
    PinKey tpk = new PinKey(TPK, true);
    PinKey zpk = new PinKey(ZPK, false);
    hsm.answer = b -> "CB15";
    assertThatThrownBy(() -> provider.translate(tpk, "1".repeat(16), PinBlockFormat.ISO0, zpk, PinBlockFormat.ISO0,
        "4000001234562000")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-014"));
    hsm.answer = b -> "CB10";
    assertThatThrownBy(() -> provider.translate(tpk, "1".repeat(16), PinBlockFormat.ISO0, zpk, PinBlockFormat.ISO0,
        "4000001234562000")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    hsm.answer = b -> "DD11";
    assertThatThrownBy(() -> provider.verifyPvv(PVK, tpk, "1".repeat(16), PinBlockFormat.ISO0, "4000001234562000", 1,
        "1234")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    hsm.answer = b -> "CB68";
    assertThatThrownBy(() -> provider.translate(tpk, "1".repeat(16), PinBlockFormat.ISO0, zpk, PinBlockFormat.ISO0,
        "4000001234562000")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-SYS-010"));
    hsm.answer = b -> "ZZ00";
    assertThatThrownBy(() -> provider.translate(tpk, "1".repeat(16), PinBlockFormat.ISO0, zpk, PinBlockFormat.ISO0,
        "4000001234562000")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-SYS-010"));
  }

  @Test
  void aResponseWithAnotherHeaderIsRefusedAndTheConnectionReplaced() throws IOException {
    provider(Duration.ofSeconds(2));
    hsm.answer = b -> "ND00" + "0".repeat(16) + "1500-0000";
    hsm.wrongHeader = true;
    assertThat(provider.probe()).isFalse();
    hsm.wrongHeader = false;
    assertThat(provider.probe()).isTrue();
    assertThat(hsm.connections.get()).isEqualTo(2);
  }

  @Test
  void aSlowHsmTimesOutClosed() throws IOException {
    provider(Duration.ofMillis(300));
    hsm.answer = b -> "ND00" + "0".repeat(16) + "1500-0000";
    hsm.delayMs = 2000;
    long t0 = System.nanoTime();
    assertThatThrownBy(() -> provider.createKey("z.v1", KeyAlgorithm.TDES_2KEY, "ZPK"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-SYS-010"));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1500));
  }

  @Test
  void theBreakerOpensAfterTransportFailuresAndThenFailsFast() throws IOException {
    provider(Duration.ofSeconds(2));
    hsm.close();
    for (int i = 0; i < 4; i++) {
      assertThat(provider.probe()).isFalse();
    }
    assertThatThrownBy(() -> provider.createKey("z.v1", KeyAlgorithm.TDES_2KEY, "ZPK"))
        .satisfies(e -> assertThat(((MwException) e).getMessage()).contains("circuit open"));
  }

  @Test
  void errorAnswersDoNotOpenTheBreaker() throws IOException {
    provider(Duration.ofSeconds(2));
    hsm.answer = b -> "DD01";
    for (int i = 0; i < 6; i++) {
      assertThat(provider.verifyPvv(PVK, new PinKey(TPK, true), "1".repeat(16), PinBlockFormat.ISO0,
          "4000001234562000", 1, "1234")).isFalse();
    }
    hsm.answer = b -> "ND00" + "0".repeat(16) + "1500-0000";
    assertThat(provider.probe()).isTrue();
  }
}
