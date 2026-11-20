package mfs.mw.orchestrator.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.client.CoreWalletClient;
import mfs.mw.orchestrator.config.CoreClientConfig;
import mfs.mw.orchestrator.dto.TxnRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The saga calls the core over REST (ADR D31, ticket 95a; FRS EXT-18, WAL-13, PLT-021/023/030) end to
 * end over real Kafka and PostgreSQL against {@link CoreRestStub}: P2P and TOPUP complete; a PostTransfer
 * whose answer is lost (the stub posts, then answers after the client's read timeout) goes POSTING ->
 * UNKNOWN on its deadline and the WAL-13 inquiry completes it with exactly one posting; and no core call
 * ever runs on a Kafka listener thread, only on the virtual threads of the core call executor.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=true",
      "mw.orchestrator.timer.enabled=true",
      "mw.orchestrator.timer.period=200ms",
      "mw.orchestrator.posting-timeout=1s",
      "mw.orchestrator.inquiry.schedule=1s",
      "mw.orchestrator.inquiry.not-found-grace=1s",
      "mw.orchestrator.core.read-timeout=500ms",
      "mw.outbox.relay.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class CoreRestFlowIT {

  private static final CoreRestStub core = CoreRestStub.start();
  private static PartnerStub partner;

  @DynamicPropertySource
  static void coreApi(DynamicPropertyRegistry registry) {
    core.register(registry);
  }

  @MockitoSpyBean CoreWalletClient client;

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final List<Thread> callers = new CopyOnWriteArrayList<>();

  @Autowired
  CoreRestFlowIT(EventPublisher publisher, KafkaContainer kafka, JdbcTemplate jdbc, JsonMapper json) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeAll
  static void startPartner(@Autowired KafkaContainer kafka) {
    partner = new PartnerStub(kafka.getBootstrapServers());
  }

  @AfterAll
  static void stopStubs() {
    partner.close();
    core.close();
  }

  @BeforeEach
  void recordCallingThreads() {
    doAnswer(invocation -> {
      callers.add(Thread.currentThread());
      return invocation.callRealMethod();
    }).when(client).call(any());
  }

  private TxnRequest publish(TxnRequest req) {
    publisher.publish("mw.txn.request.v1", req.txnId(),
        new EventMetadata(req.correlationId(), req.txnId(), req.idempotencyKey(), 1), req);
    return req;
  }

  private List<String> statuses(String txnId, int count) {
    List<ConsumerRecord<String, String>> got = KafkaTestConsumer.read(
        kafka.getBootstrapServers(), "mw.txn.status.v1", count, Duration.ofSeconds(60), r -> txnId.equals(r.key()));
    return got.stream().map(r -> json.readTree(r.value()).path("status").asString()).toList();
  }

  private void assertNoCallOnAListenerThread() {
    assertThat(callers).isNotEmpty().allSatisfy(t -> {
      assertThat(t.isVirtual()).as("virtual thread: %s", t).isTrue();
      assertThat(t.getName()).as("core call executor thread").startsWith(CoreClientConfig.THREAD_PREFIX);
    });
  }

  @Test
  void aP2pCompletesOverRestAndNoCoreCallRunsOnAListenerThread() {
    TxnRequest req = publish(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 25_000));

    assertThat(statuses(req.txnId(), 4)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");
    assertThat(core.calls(req.txnId())).containsExactly("ValidateTransaction", "PostTransfer");
    assertThat(core.postings(req.txnId())).isEqualTo(1);
    assertNoCallOnAListenerThread();
    assertThat(jdbc.queryForList("select result from core_call where txn_id = ?::uuid order by seq", String.class, req.txnId()))
        .as("the saga timeline records each answer").containsExactly("TransactionValidated", "TransferPosted");
  }

  @Test
  void aTopupHoldsCallsThePartnerAndCommits() {
    TxnRequest req = publish(TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002"));

    assertThat(statuses(req.txnId(), 6))
        .containsExactly("RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "COMMITTING", "COMPLETED");
    assertThat(core.calls(req.txnId())).containsExactly("ValidateTransaction", "HoldFunds", "CommitHold");
    assertNoCallOnAListenerThread();
  }

  @Test
  void aLostTransferAnswerGoesUnknownAndWal13CompletesItWithOnePosting() {
    TxnRequest req = publish(TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), CoreRestStub.SILENT_AMOUNT));

    assertThat(statuses(req.txnId(), 5)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "UNKNOWN", "COMPLETED");
    assertThat(core.calls(req.txnId())).startsWith("ValidateTransaction", "PostTransfer").contains("QueryTransaction")
        .filteredOn("PostTransfer"::equals).hasSize(1);
    assertThat(core.postings(req.txnId())).as("one posting").isEqualTo(1);
    assertNoCallOnAListenerThread();
  }
}
