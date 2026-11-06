package mfs.mw.adapter.billpay.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import mfs.mw.adapter.billpay.BillpayFixtures;
import mfs.mw.adapter.billpay.config.MwServiceProperties;
import mfs.mw.adapter.billpay.dto.PartnerInbound;
import mfs.mw.adapter.billpay.dto.PartnerReply;
import mfs.mw.adapter.billpay.dto.PartnerRequest;
import mfs.mw.adapter.billpay.mapper.PartnerInboundMapper;
import mfs.mw.adapter.billpay.mapper.PartnerReplyMapper;
import mfs.mw.adapter.billpay.messaging.EventMetadata;
import mfs.mw.adapter.billpay.messaging.EventPublisher;
import mfs.mw.adapter.billpay.messaging.OutboxWriter;
import mfs.mw.adapter.billpay.messaging.PartnerAuditPublisher;
import mfs.mw.adapter.billpay.partner.InboundResult;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.template.PartnerAudit;
import mfs.mw.adapter.billpay.partner.template.PartnerFrameworkSeeds;
import mfs.mw.adapter.billpay.service.PartnerCallbackService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Every event this module produces ({@code produces.events} in {@code contracts.yml}, ticket 53)
 * built by its real mapper/publisher from a fixture, written by the real {@link OutboxWriter} with
 * the service's JSON mapper (the stored outbox body is what the relay sends unchanged), and
 * validated against the contract. No Spring context, no Docker: the outbox's JDBC insert is
 * captured instead of executed. A manifest entry without a fixture fails.
 */
class ProducedContractsTest {

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T08:15:40Z"), ZoneOffset.UTC);
  static final MwServiceProperties SERVICE = new MwServiceProperties("mw-adapter-billpay", "0.1.0", "test");
  static final String TXN_ID = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";

  /** Fixtures keyed by manifest entry: each writes one or more events through the outbox. */
  static final Map<String, Function<Outbox, List<Outbox.Record>>> FIXTURES =
      Map.of(
          "events/mw-orchestrator/mw.partner.reply.v1.schema.json#billpay", ProducedContractsTest::partnerReplies,
          "events/mw-orchestrator/mw.partner.inbound.v1.schema.json#billpay", ProducedContractsTest::partnerInbound,
          "events/mw-audit-dw/mw.audit.event.v1.schema.json", ProducedContractsTest::auditEvents);

  /** One reply per outcome the mapper produces, written as {@code TopupService} writes it. */
  static List<Outbox.Record> partnerReplies(Outbox outbox) {
    PartnerReplyMapper mapper = new PartnerReplyMapper(CLOCK);
    PartnerRequest pay = BillpayFixtures.pay(TXN_ID, BillpayFixtures.BILL, 66_700);
    PartnerRequest query = BillpayFixtures.statusQuery(TXN_ID);
    String ref = "BITS01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";
    List<PartnerReply> replies =
        List.of(
            mapper.toReply(pay, ref, new PartnerResponse.Success("AGG-445566", "00", Map.of())),
            mapper.toReply(pay, ref, new PartnerResponse.Rejected("3003", "Refused", "PAYMENT_REFUSED", false)),
            mapper.toReply(pay, ref, new PartnerResponse.Ambiguous("9876", "In doubt")),
            mapper.toReply(pay, ref, new PartnerResponse.Pending("AGG-445566", "01")),
            mapper.toReply(pay, ref, new PartnerResponse.NotSent(PartnerResponse.NotSentReason.CIRCUIT_OPEN)),
            mapper.toReply(pay, ref, new PartnerResponse.Unknown(PartnerResponse.UnknownReason.TIMEOUT)),
            mapper.toReply(query, ref, new PartnerResponse.Success("AGG-445566", "00", Map.of())),
            mapper.toReply(query, ref, new PartnerResponse.NotFound("404", "No such payment")),
            mapper.toReply(query, ref, new PartnerResponse.NotSent(PartnerResponse.NotSentReason.BULKHEAD_FULL)),
            mapper.expired(pay),
            mapper.invalid(pay, "amount must be positive"));
    // the topic is the service's own constant (BillPaymentService.REPLY_TOPIC is package-private)
    return outbox.inTransaction(
        () ->
            replies.forEach(
                r ->
                    outbox.writer.append(
                        "mw.partner.reply.v1",
                        r.txnId(),
                        new EventMetadata(r.correlationId(), r.txnId(), r.requestId(), 1),
                        r)));
  }

  /**
   * Callbacks mapped by {@link PartnerInboundMapper} and published as {@code
   * PartnerCallbackService} publishes them ({@code EventPublisher.publish}, synchronous, not the
   * outbox); the capture serialises with the publisher's own mapper, as {@code publish} does first.
   */
  static List<Outbox.Record> partnerInbound(Outbox outbox) {
    PartnerInboundMapper mapper = new PartnerInboundMapper();
    PartnerConfig config = PartnerFrameworkSeeds.billagg();
    String ref = "BITS01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";
    Instant now = CLOCK.instant();
    List<PartnerInbound> events =
        List.of(
            mapper.toInbound(new InboundResult("AGG-445566", ref, "SUCCESS", "000", "Done", Map.of()), config, "billagg", TXN_ID, "corr-0002", now),
            mapper.toInbound(new InboundResult(null, ref, "FAILED", "3003", "Refused", Map.of()), config, "billagg", TXN_ID, "corr-0002", now),
            mapper.toInbound(new InboundResult(null, ref, "FAILED", "7777", null, Map.of()), config, "billagg", TXN_ID, "corr-0002", now),
            mapper.toInbound(new InboundResult(null, ref, "FAILED", "9876", null, Map.of()), config, "billagg", TXN_ID, "corr-0002", now),
            mapper.toInbound(new InboundResult("AGG-445566", ref, "PENDING", null, null, Map.of("note", "queued")), config, "billagg", TXN_ID, "corr-0002", now));
    events.forEach(
        e ->
            outbox.publisher.publish(
                PartnerCallbackService.TOPIC, e.txnId(), new EventMetadata(e.correlationId(), e.txnId(), e.inboundId(), 1), e));
    return outbox.records;
  }

  /** The adapter framework's audit entries, through {@link PartnerAuditPublisher} itself. */
  static List<Outbox.Record> auditEvents(Outbox outbox) {
    PartnerAuditPublisher publisher = new PartnerAuditPublisher(outbox.writer, outbox.transactions, SERVICE, CLOCK);
    publisher.accept(
        new PartnerAudit.Entry(
            "billagg", "billagg", "PAY", TXN_ID, "corr-0002", "POST", "/billagg/v1/payments", 200, "SUCCESS", 120,
            "{\"account\":\"*********0123\",\"amount\":\"500.00\"}", "{\"status\":\"SUCCESS\",\"ref\":\"GP-778899\"}"));
    publisher.accept(
        new PartnerAudit.Entry(
            "billagg", "billagg", "STATUS_QUERY", TXN_ID, null, "GET", "/billagg/v1/payments/{reference}", null, "NOT_SENT", 0,
            null, null));
    publisher.accept(
        new PartnerAudit.Entry(
            "billagg", "billagg", "PAY", TXN_ID, " ", "POST", "/billagg/v1/payments", 503, "UNKNOWN", 20_000, "{}", "busy"));
    // an entry without a txnId is not written at all: EventMetadata requires one (spec 6.1)
    return outbox.records;
  }

  @TestFactory
  Stream<DynamicTest> everyProducedEventMatchesItsContract() {
    return ContractManifest.load().producedEvents().stream()
        .map(entry -> DynamicTest.dynamicTest(entry, () -> assertProduced(entry)));
  }

  @Test
  void everyManifestEntryHasAFixtureAndEveryFixtureIsInTheManifest() {
    assertThat(FIXTURES.keySet())
        .containsExactlyInAnyOrderElementsOf(ContractManifest.load().producedEvents());
  }

  static void assertProduced(String entry) {
    assertThat(FIXTURES).as("fixture for manifest entry %s", entry).containsKey(entry);
    ContractManifest.Entry e = ContractManifest.Entry.of(entry);
    List<Outbox.Record> records = FIXTURES.get(entry).apply(new Outbox());
    assertThat(records).as("events written for %s", entry).isNotEmpty();
    for (Outbox.Record r : records) {
      assertThat(r.topic()).as("topic of %s", entry).isEqualTo(e.topic());
      JsonNode body = ServiceJson.MAPPER.readTree(r.payload());
      assertThat(ContractSchemas.schema(e.path()).validate(body)).as("%s: %s", entry, r.payload()).isEmpty();
      if (e.msg() != null) {
        assertThat(body.path("family").asString()).isEqualTo(e.msg());
      }
    }
  }

  /**
   * The real {@link OutboxWriter} with the service's JSON mapper, its insert captured: {@code
   * records} holds the topic and the stored JSON body of every append.
   */
  static final class Outbox {

    record Record(String topic, String payload) {}

    final List<Record> records = new ArrayList<>();

    final PlatformTransactionManager transactions =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
          }

          @Override
          public void rollback(TransactionStatus status) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
          }
        };

    /** {@code publish} captured: the body is what its first line writes, {@code json.writeValueAsString}. */
    final EventPublisher publisher =
        new EventPublisher(null, ServiceJson.MAPPER, null, null, SERVICE) {
          @Override
          public void publish(String topic, String key, EventMetadata meta, Object payload) {
            records.add(new Record(topic, ServiceJson.MAPPER.writeValueAsString(payload)));
          }
        };

    final OutboxWriter writer =
        new OutboxWriter(
            new JdbcTemplate() {
              @Override
              public int update(String sql, Object... args) {
                records.add(new Record((String) args[0], (String) args[3]));
                return 1;
              }
            },
            ServiceJson.MAPPER,
            new EventPublisher(null, ServiceJson.MAPPER, null, null, SERVICE) {
              @Override
              public Map<String, String> headersFor(String topic, EventMetadata meta) {
                return Map.of();
              }
            });

    List<Record> inTransaction(Runnable work) {
      new TransactionTemplate(transactions).executeWithoutResult(s -> work.run());
      return records;
    }
  }
}
