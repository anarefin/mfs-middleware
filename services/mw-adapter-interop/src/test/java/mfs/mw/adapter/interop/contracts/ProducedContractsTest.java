package mfs.mw.adapter.interop.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import mfs.mw.adapter.interop.InteropFixtures;
import mfs.mw.adapter.interop.config.MwServiceProperties;
import mfs.mw.adapter.interop.dto.PartnerReply;
import mfs.mw.adapter.interop.dto.PartnerRequest;
import mfs.mw.adapter.interop.mapper.PartnerReplyMapper;
import mfs.mw.adapter.interop.messaging.EventMetadata;
import mfs.mw.adapter.interop.messaging.EventPublisher;
import mfs.mw.adapter.interop.messaging.OutboxWriter;
import mfs.mw.adapter.interop.messaging.PartnerAuditPublisher;
import mfs.mw.adapter.interop.partner.PartnerResponse;
import mfs.mw.adapter.interop.partner.config.PartnerConfig;
import mfs.mw.adapter.interop.partner.route.PartnerAudit;
import mfs.mw.adapter.interop.partner.PartnerTestSeeds;
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
  static final MwServiceProperties SERVICE = new MwServiceProperties("mw-adapter-interop", "0.1.0", "test");
  static final mfs.mw.adapter.interop.config.MwKafkaProperties KAFKA =
      new mfs.mw.adapter.interop.config.MwKafkaProperties(
          java.time.Duration.ofSeconds(1),
          new mfs.mw.adapter.interop.config.MwKafkaProperties.Listener(java.time.Duration.ofMillis(100)));
  static final String TXN_ID = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";

  /** Fixtures keyed by manifest entry: each writes one or more events through the outbox. */
  static final Map<String, Function<Outbox, List<Outbox.Record>>> FIXTURES =
      Map.of(
          "events/mw-orchestrator/mw.partner.reply.v1.schema.json#interop", ProducedContractsTest::partnerReplies,
          "events/mw-orchestrator/mw.partner.inbound.v1.schema.json#interop", ProducedContractsTest::npsbCredits,
          "events/mw-audit-dw/mw.audit.event.v1.schema.json", ProducedContractsTest::auditEvents,
          "events/mw-adapter-billpay/mw.partner.route.status.v1.schema.json", ProducedContractsTest::routeStatuses,
          "events/mw-adapter-billpay/mw.partner.recon.v1.schema.json", ProducedContractsTest::reconLines);

  /** Two lines of one file, through {@link mfs.mw.adapter.interop.messaging.PartnerReconPublisher} itself (ticket 86). */
  static List<Outbox.Record> reconLines(Outbox outbox) {
    var publisher = new mfs.mw.adapter.interop.messaging.PartnerReconPublisher(
        outbox.writer, outbox.transactions, SERVICE,
        new mfs.mw.adapter.interop.partner.config.PartnerProperties(null, null, null, "interop", null), CLOCK);
    var file = new mfs.mw.adapter.interop.partner.route.PartnerRecon.File(
        "c2wbank-settle", "settlement-20261118.csv", "settlement-20261118.csv-74-1795507200000");
    publisher.publish(file, java.util.stream.Stream.of(
        new mfs.mw.adapter.interop.partner.route.PartnerRecon.Line(2, false, Map.of("reference", "BITS01", "amount", "500.00")),
        new mfs.mw.adapter.interop.partner.route.PartnerRecon.Line(3, true, Map.of("reference", "BITS02", "amount", "120.50"))));
    return outbox.records;
  }

  /** LIVE and REJECTED, through {@link mfs.mw.adapter.interop.messaging.PartnerRouteStatusPublisher} itself. */
  static List<Outbox.Record> routeStatuses(Outbox outbox) {
    var publisher = new mfs.mw.adapter.interop.messaging.PartnerRouteStatusPublisher(
        outbox.writer, outbox.transactions, SERVICE,
        new mfs.mw.adapter.interop.partner.config.PartnerProperties(null, null, null, "interop", null), CLOCK);
    publisher.publish(new mfs.mw.adapter.interop.partner.route.PartnerRouteStatus(
        "newco", 2, mfs.mw.adapter.interop.partner.route.PartnerRouteStatus.Status.LIVE, List.of(),
        List.of("mw-audit", "mw-classify", "mw-http-call", "mw-map")));
    publisher.publish(mfs.mw.adapter.interop.partner.route.PartnerRouteStatus.rejected(
        "newco", 3, List.of("dry-run PAY gave no usable answer: HTTP_5XX")));
    return outbox.records;
  }

  /** An accepted NPSB credit (ticket 121b), mapped by {@link mfs.mw.adapter.interop.mapper.NpsbInboundMapper} and written as {@code NpsbInboundService} writes it. */
  static List<Outbox.Record> npsbCredits(Outbox outbox) {
    var transfer = new mfs.mw.adapter.interop.dto.iso.NpsbTransfer(
        "MSG-1", java.time.OffsetDateTime.parse("2026-11-03T08:15:00Z"), "NPSB0123456789ABCDEF0123456789AB", "TX-1",
        new mfs.mw.adapter.interop.dto.Money(250_000, "BDT"), java.time.LocalDate.of(2026, 11, 3),
        new mfs.mw.adapter.interop.dto.iso.NpsbParty("Karim Ahmed", "1234567890123", "FAKEBDD1"),
        new mfs.mw.adapter.interop.dto.iso.NpsbParty("Rahim Uddin", "01711000001", "BITSBDDH"));
    var event = new mfs.mw.adapter.interop.mapper.NpsbInboundMapper()
        .toInbound(transfer, "01711000001", TXN_ID, "corr-0002", CLOCK.instant());
    return outbox.inTransaction(() -> outbox.writer.append(
        mfs.mw.adapter.interop.service.NpsbInboundService.TOPIC, TXN_ID,
        new EventMetadata(event.correlationId(), TXN_ID, event.inboundId(), 1), event));
  }

  /** One reply per outcome the mapper produces, written as {@code TopupService} writes it. */
  static List<Outbox.Record> partnerReplies(Outbox outbox) {
    PartnerReplyMapper mapper = new PartnerReplyMapper(CLOCK);
    PartnerRequest pay = InteropFixtures.pay(TXN_ID, InteropFixtures.ACCOUNT, 50_000);
    PartnerRequest query = InteropFixtures.statusQuery(TXN_ID);
    String ref = "BITS01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";
    List<PartnerReply> replies =
        List.of(
            mapper.toReply(pay, ref, new PartnerResponse.Success("C2W-445566", "00", Map.of())),
            mapper.toReply(pay, ref, new PartnerResponse.Rejected("4001", "Refused", "PAYMENT_REFUSED", false)),
            mapper.toReply(pay, ref, new PartnerResponse.Ambiguous("9876", "In doubt")),
            mapper.toReply(pay, ref, new PartnerResponse.Pending("C2W-445566", "01")),
            mapper.toReply(pay, ref, new PartnerResponse.NotSent(PartnerResponse.NotSentReason.CIRCUIT_OPEN)),
            mapper.toReply(pay, ref, new PartnerResponse.Unknown(PartnerResponse.UnknownReason.TIMEOUT)),
            mapper.toReply(query, ref, new PartnerResponse.Success("C2W-445566", "00", Map.of())),
            mapper.toReply(query, ref, new PartnerResponse.NotFound("404", "No such payment")),
            mapper.toReply(query, ref, new PartnerResponse.NotSent(PartnerResponse.NotSentReason.BULKHEAD_FULL)),
            mapper.expired(pay),
            mapper.invalid(pay, "amount must be positive"));
    // the topic is the service's own constant (InteropPartnerService.REPLY_TOPIC is package-private)
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

  /** The adapter framework's audit entries, through {@link PartnerAuditPublisher} itself. */
  static List<Outbox.Record> auditEvents(Outbox outbox) {
    PartnerAuditPublisher publisher = new PartnerAuditPublisher(outbox.writer, outbox.transactions, SERVICE, CLOCK);
    publisher.accept(
        new PartnerAudit.Entry(
            "c2wbank", "c2wbank", "PAY", TXN_ID, "corr-0002", "POST", "/c2wbank/v1/debits", 200, "SUCCESS", 120,
            "{\"account\":\"*********0123\",\"amount\":\"500.00\"}", "{\"status\":\"SUCCESS\",\"ref\":\"GP-778899\"}"));
    publisher.accept(
        new PartnerAudit.Entry(
            "c2wbank", "c2wbank", "STATUS_QUERY", TXN_ID, null, "GET", "/c2wbank/v1/debits/{reference}", null, "NOT_SENT", 0,
            null, null));
    publisher.accept(
        new PartnerAudit.Entry(
            "c2wbank", "c2wbank", "PAY", TXN_ID, " ", "POST", "/c2wbank/v1/debits", 503, "UNKNOWN", 20_000, "{}", "busy"));
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
        new EventPublisher(null, null, ServiceJson.MAPPER, null, null, SERVICE, KAFKA) {
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
            new EventPublisher(null, null, ServiceJson.MAPPER, null, null, SERVICE, KAFKA) {
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
