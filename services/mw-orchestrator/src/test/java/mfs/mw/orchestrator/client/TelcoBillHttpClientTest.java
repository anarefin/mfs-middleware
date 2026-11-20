package mfs.mw.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.orchestrator.config.TopupProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The bill client posts the profile and the number and reads both amounts (ticket 112b). */
class TelcoBillHttpClientTest {

  @Test
  void aFetchPostsTheProfileAndReadsTheBill() throws Exception {
    AtomicReference<String> sent = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/telco/v1/bills/fetch", exchange -> {
      sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = """
          {"outstanding":{"valueMinor":45000,"currency":"BDT"},"minimum":{"valueMinor":5000,"currency":"BDT"}}"""
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      var client = new TelcoBillHttpClient(
          new TopupProperties(Map.of(), Map.of(), new TopupProperties.Bill(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Duration.ofSeconds(1), Duration.ofSeconds(2))),
          JsonMapper.builder().build());

      TelcoBillClient.Bill bill = client.fetch("gp-ocs", "01711000021", "corr-1");

      assertThat(bill.outstandingMinor()).isEqualTo(45_000);
      assertThat(bill.minimumMinor()).isEqualTo(5_000);
      assertThat(sent.get()).contains("\"partnerId\":\"gp-ocs\"").contains("\"msisdn\":\"01711000021\"");
    } finally {
      server.stop(0);
    }
  }
}
