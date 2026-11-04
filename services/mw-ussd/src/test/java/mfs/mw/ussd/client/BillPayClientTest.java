package mfs.mw.ussd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.ussd.config.BillPayProperties;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.exception.BillPayRefusedException;
import mfs.mw.ussd.exception.BillPayUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The internal HTTP call to mw-adapter-billpay: answers, the adapter's envelope, and transport failures. */
class BillPayClientTest {

  private HttpServer server;
  private final List<String> seen = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile String body = "[]";
  private BillPayClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " "
              + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + " "
              + ex.getRequestHeaders().getFirst("X-Correlation-Id"));
          byte[] b = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
          if (b.length > 0) {
            ex.getResponseBody().write(b);
          }
          ex.close();
        });
    server.start();
    client = client("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static BillPayClient client(String base) {
    return new BillPayClient(
        new BillPayProperties(URI.create(base), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(60)),
        JsonMapper.builder().build());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void listsTheBillersWithTheirAggregator() {
    body = "[{\"code\":\"DESCO\",\"name\":\"Dhaka Electric\",\"category\":\"ELECTRICITY\",\"partnerId\":\"billagg\",\"extra\":1}]";
    List<Biller> billers = client.billers();
    assertThat(billers).containsExactly(new Biller("DESCO", "Dhaka Electric", "ELECTRICITY", "billagg"));
    assertThat(seen.get(0)).startsWith("GET /billpay/v1/billers");
  }

  @Test
  void fetchPostsTheBillNumberWithTheCorrelationId() {
    org.slf4j.MDC.put("correlationId", "corr-fetch");
    try {
      body = "{\"billerCode\":\"DESCO\",\"billNumber\":\"1234567\",\"amount\":{\"valueMinor\":66700,\"currency\":\"BDT\"},\"dueDate\":\"2026-11-14\",\"billPeriod\":\"2026-11\"}";
      Bill bill = client.fetch("DESCO", "1234567");
      assertThat(bill.amount().valueMinor()).isEqualTo(66_700L);
      assertThat(bill.dueDate()).isEqualTo("2026-11-14");
      assertThat(seen.get(0))
          .startsWith("POST /billpay/v1/billers/DESCO/bills/fetch {\"billNumber\":\"1234567\"}")
          .endsWith("corr-fetch");
    } finally {
      org.slf4j.MDC.remove("correlationId");
    }
  }

  @Test
  void theAdaptersEnvelopeIsADefiniteAnswer() {
    status = 502;
    body = "{\"code\":\"MW-PTR-BILLAGG-BILL_NOT_FOUND\",\"message\":\"Bill not found\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}";
    assertThatThrownBy(() -> client.fetch("DESCO", "1234404"))
        .isInstanceOfSatisfying(
            BillPayRefusedException.class,
            e -> {
              assertThat(e.status()).isEqualTo(502);
              assertThat(e.envelope().code()).isEqualTo("MW-PTR-BILLAGG-BILL_NOT_FOUND");
            });
  }

  @Test
  void aServerErrorWithoutEnvelopeOrAnUnreadableBodyIsUnavailable() {
    status = 500;
    body = "oops";
    assertThatThrownBy(() -> client.billers()).isInstanceOf(BillPayUnavailableException.class);
    status = 200;
    body = "{not json";
    assertThatThrownBy(() -> client.fetch("DESCO", "1")).isInstanceOf(BillPayUnavailableException.class);
  }

  @Test
  void anUnreachableAdapterIsUnavailable() {
    BillPayClient dead = client("http://127.0.0.1:1");
    assertThatThrownBy(dead::billers).isInstanceOf(BillPayUnavailableException.class);
  }

  @Test
  void theBillerCodeIsEncodedInThePath() {
    status = 404;
    body = "{\"code\":\"MW-VAL-003\",\"message\":\"Unknown biller\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}";
    assertThatThrownBy(() -> client.fetch("A B", "1")).isInstanceOf(BillPayRefusedException.class);
    assertThat(seen.get(0)).startsWith("POST /billpay/v1/billers/A%20B/bills/fetch");
  }
}
