package mfs.mw.data.export.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.data.export.config.PiiPolicyProperties;
import mfs.mw.data.export.dto.NotifyStatusEvent;
import mfs.mw.data.export.dto.TxnStatusEvent;
import mfs.mw.data.export.service.DwEvents;
import mfs.mw.data.export.service.DwTopics;
import mfs.mw.data.export.service.FieldTokeniser;
import mfs.mw.data.export.service.PiiPolicy;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every produces.events entry is built by {@link DwEvents} and matches its schema (ticket 128). */
class ProducedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
  static final Instant AT = Instant.parse("2026-11-20T02:00:00Z");

  static final FieldTokeniser TOKENS = (domain, value) -> switch (value) {
    case "01712345678" -> "MSISDN_PAYER";
    case "01812345678" -> "MSISDN_PAYEE";
    default -> domain + "_TESTTOKEN";
  };

  static PiiPolicy policy() throws IOException {
    StandardEnvironment env = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"))
        .forEach(env.getPropertySources()::addLast);
    PiiPolicyProperties props = new Binder(ConfigurationPropertySources.from(env.getPropertySources()))
        .bind("mw.export.pii", PiiPolicyProperties.class)
        .get();
    return new PiiPolicy(props, TOKENS);
  }

  static Map<String, JsonNode> published() throws IOException {
    DwEvents events = new DwEvents(policy());
    Map<String, JsonNode> out = new LinkedHashMap<>();
    TxnStatusEvent received = new TxnStatusEvent(TXN, "corr-128", "APP", "P2P", "RECEIVED", "NONE",
        new TxnStatusEvent.Party("WALLET", "01712345678"),
        new TxnStatusEvent.Party("WALLET", "01812345678"),
        new TxnStatusEvent.Amount(100, "BDT"), AT, AT.plusSeconds(1), null);
    for (DwEvents.Outbound one : events.fromTxn(received)) {
      out.put(one.topic(), node(one.body()));
    }
    TxnStatusEvent done = new TxnStatusEvent(TXN, "corr-128", "APP", "P2P", "COMPLETED", "POSTING",
        new TxnStatusEvent.Party("WALLET", "01712345678"),
        new TxnStatusEvent.Party("WALLET", "01812345678"),
        new TxnStatusEvent.Amount(100, "BDT"), AT, AT.plusSeconds(5), AT.plusSeconds(5));
    for (DwEvents.Outbound one : events.fromTxn(done)) {
      out.putIfAbsent(one.topic(), node(one.body()));
      if (DwTopics.TXN_COMPLETED.equals(one.topic())) {
        out.put(one.topic(), node(one.body()));
      }
    }
    out.put(DwTopics.NOTIFICATION, node(events.fromNotify(new NotifyStatusEvent(
        TXN + ":sms", TXN, "corr-128", "SMS", "SENT", "TXN_P2P_SENT", "01712345678", AT)).body()));
    out.put(DwTopics.RECON, node(events.fromRecon(Map.of(
        "exceptionId", "ex-1", "exceptionClass", "MISSING_IN_LEDGER", "partner", "trustbank",
        "txnId", TXN, "amountMinor", 100, "statementRef", "TB-1",
        "detectedAt", AT.toString(), "correlationId", "corr-128")).body()));
    out.put(DwTopics.KYC, node(events.fromKyc(Map.of(
        "walletId", "01712345678", "kycLevel", "FULL", "completedAt", AT.toString(),
        "correlationId", "corr-128")).body()));
    out.put(DwTopics.OPERATOR, node(events.fromOperatorAudit(
        "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c", "corr-128", "ops.checker1", "dw.export.replayed",
        "export/2026-11-20", "SUCCESS", AT).body()));
    out.put("events/mw-audit-dw/mw.audit.event.v1.schema.json", node(events.replayAudit(
        "ops.checker1", java.time.LocalDate.parse("2026-11-20"), AT,
        "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c", "corr-128")));
    return out;
  }

  static JsonNode node(Object body) {
    return JSON.readTree(JSON.writeValueAsString(body));
  }

  @TestFactory
  List<DynamicTest> everyProducedEventMatchesItsSchema() throws IOException {
    Map<String, JsonNode> bodies = published();
    return ContractManifest.producedEvents().stream()
        .map(entry -> DynamicTest.dynamicTest(entry, () -> {
          String topic = entry.substring(entry.lastIndexOf('/') + 1).replace(".schema.json", "");
          JsonNode body = bodies.containsKey(topic) ? bodies.get(topic) : bodies.get(entry);
          assertThat(body).as(entry).isNotNull();
          assertThat(ContractSchemas.schema(ContractManifest.schemaPath(entry)).validate(body))
              .as("%s: %s", entry, body)
              .isEmpty();
        }))
        .toList();
  }

  @Test
  void aTokenisedPartyIsNotTheClearMsisdn() throws IOException {
    JsonNode state = published().get(DwTopics.TXN_STATE);
    assertThat(state.toString()).doesNotContain("01712345678").doesNotContain("01812345678");
    assertThat(state.path("payerToken").asString()).isEqualTo("MSISDN_PAYER");
    assertThat(state.path("payeeToken").asString()).isEqualTo("MSISDN_PAYEE");
  }
}
