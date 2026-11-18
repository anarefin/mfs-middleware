package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.PostgresOnlyInfrastructure;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.dto.TxnAuthorisation;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Kafka down: the app gets a retryable 503 MW-SYS-* quickly and nothing is accepted. */
@SpringBootTest(
    properties = {
      "spring.kafka.bootstrap-servers=127.0.0.1:1",
      "spring.kafka.producer.properties.max.block.ms=1500",
      "mw.outbox.relay.enabled=false"
    })
@AutoConfigureMockMvc
@Import(PostgresOnlyInfrastructure.class)
@DirtiesContext
class KafkaUnavailableIT {

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;

  @Autowired
  KafkaUnavailableIT(MockMvcTester mvc, JdbcTemplate jdbc) {
    this.mvc = mvc;
    this.jdbc = jdbc;
  }

  /** mw-auth answers (ticket 72): only Kafka is down here. */
  @MockitoBean AuthClient auth;

  @Test
  void publishFailureIsRetryable503AndNothingIsAccepted() {
    when(auth.authorise(any()))
        .thenReturn(new TxnAuthorisation("eyJh.eyJq.c2ln", java.time.Instant.now().plusSeconds(60), false));
    Integer before = jdbc.queryForObject("select count(*) from app_transaction", Integer.class);
    long start = System.nanoTime();

    MvcTestResult r =
        mvc.post()
            .uri("/app/v1/transactions")
            .with(
                jwt()
                    .jwt(
                        j ->
                            j.claim("msisdn", "01711000001")
                                .claim("role", "customer").claim("amr", List.of("pin", "device"))))
            .header("Idempotency-Key", "kafka-down-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"txnType":"P2P","creditParty":{"id":"01711000002"},
                 "amount":{"valueMinor":1000,"currency":"BDT"},
                 "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVy"}}
                """)
            .exchange();

    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(15_000);
    assertThat(r).hasStatus(503);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-SYS-010");
    assertThat(r).bodyJson().extractingPath("$.retryable").isEqualTo(true);
    assertThat(r).bodyJson().doesNotHavePath("$.txnId");
    assertThat(jdbc.queryForObject("select count(*) from app_transaction", Integer.class))
        .isEqualTo(before);
  }
}
