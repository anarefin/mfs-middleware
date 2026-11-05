package mfs.mw.notify.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import mfs.mw.notify.dto.ConfigChangedEvent;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.messaging.ConfigChangedListener;
import mfs.mw.notify.messaging.NotifyRequestListener;
import mfs.mw.notify.messaging.TxnStatusListener;
import mfs.mw.notify.service.NotificationService;
import mfs.mw.notify.service.TemplatePublisher;
import mfs.mw.notify.service.TxnStatusService;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code consumes.events} entry of {@code contracts.yml} (ticket 53): each valid contract
 * sample goes through the real listener ({@link NotifyRequestListener}, {@link TxnStatusListener}:
 * read with Spring Boot's default JsonMapper, the module configures none, plus the listener's own
 * check) and reaches the service as the module's DTO. No Spring context, no Docker (a bare Camel
 * exchange carries the body).
 *
 * <p>Invalid samples: neither listener validates against the schema. {@code NotifyRequestListener}
 * refuses a request without messageId or templateCode, and Jackson refuses an unknown enum value
 * ({@code Priority}, {@code Channel}); an empty recipient or no channels is recorded FAILED by
 * {@code NotificationService} (a service rule, not a rejection). {@code TxnStatusListener} refuses
 * only a missing txnId or status; status is a plain string and the rules match on it, so the
 * mw.txn.status.v1 invalid samples are not rejected and no check is invented for them.
 */
class ConsumedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String REQUEST = "events/mw-notify/mw.notify.request.v1.schema.json";
  static final String TXN_STATUS = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  static final String CONFIG = "events/mw-admin-api/mw.config.changed.v1.schema.json";

  /** Invalid samples the listener refuses (under contracts/). */
  static final List<String> REJECTED_BY_LISTENER =
      List.of("events/mw-notify/samples/mw.notify.request.v1/invalid-unknown-priority.json");

  static Exchange exchange(String body) {
    Exchange exchange = new DefaultExchange(new DefaultCamelContext());
    exchange.getMessage().setBody(body);
    return exchange;
  }

  static NotificationRequest readRequest(String body) {
    NotificationService service = mock(NotificationService.class);
    new NotifyRequestListener(service, JSON).handle(exchange(body));
    ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
    verify(service).accept(captor.capture());
    return captor.getValue();
  }

  static TxnStatusEvent readTxnStatus(String body) {
    TxnStatusService service = mock(TxnStatusService.class);
    new TxnStatusListener(service, JSON).handle(exchange(body));
    ArgumentCaptor<TxnStatusEvent> captor = ArgumentCaptor.forClass(TxnStatusEvent.class);
    verify(service).onStatus(captor.capture());
    return captor.getValue();
  }

  static ConfigChangedEvent readConfig(String body) {
    TemplatePublisher service = mock(TemplatePublisher.class);
    new ConfigChangedListener(service, JSON).handle(exchange(body));
    ArgumentCaptor<ConfigChangedEvent> captor = ArgumentCaptor.forClass(ConfigChangedEvent.class);
    verify(service).onConfigChanged(captor.capture());
    return captor.getValue();
  }

  /** Manifest entry -> what the listener hands the service, checked for the fields it relies on. */
  static final Map<String, Consumer<String>> READERS =
      Map.of(
          REQUEST,
          body -> {
            NotificationRequest r = readRequest(body);
            assertThat(r.messageId()).isNotBlank();
            assertThat(r.templateCode()).isNotBlank();
            assertThat(r.recipient()).isNotNull();
          },
          TXN_STATUS,
          body -> {
            TxnStatusEvent e = readTxnStatus(body);
            assertThat(e.txnId()).isNotBlank();
            assertThat(e.status()).isNotBlank();
            assertThat(e.txnType()).isNotBlank();
          },
          CONFIG,
          body -> {
            ConfigChangedEvent e = readConfig(body);
            assertThat(e.configKey()).isNotBlank();
            if (e.configType() == ConfigChangedEvent.ConfigType.TEMPLATE && e.change() == ConfigChangedEvent.Change.UPSERT) {
              assertThat(e.value()).containsKeys("code", "channel", "locale", "body");
            }
          });

  @TestFactory
  Stream<DynamicTest> everyValidSampleOfAConsumedEventDeserialises() {
    return ContractManifest.consumedEvents().stream()
        .flatMap(
            entry -> {
              assertThat(READERS).as("reader for manifest entry %s", entry).containsKey(entry);
              List<Path> valid = ContractSchemas.samples(ContractManifest.samplesDir(entry), "valid");
              assertThat(valid).as("valid samples of %s", entry).isNotEmpty();
              return valid.stream()
                  .map(
                      sample ->
                          DynamicTest.dynamicTest(
                              entry + " " + sample.getFileName(),
                              () -> READERS.get(entry).accept(Files.readString(sample))));
            });
  }

  @Test
  void readersAndManifestListTheSameEntries() {
    assertThat(READERS.keySet()).containsExactlyInAnyOrderElementsOf(ContractManifest.consumedEvents());
  }

  @TestFactory
  Stream<DynamicTest> invalidSamplesTheListenerChecksAreRejected() {
    return REJECTED_BY_LISTENER.stream()
        .map(
            name ->
                DynamicTest.dynamicTest(
                    name,
                    () -> {
                      Path sample = ContractSchemas.contractsDir().resolve(name);
                      String body = Files.readString(sample);
                      NotificationService service = mock(NotificationService.class);
                      assertThatThrownBy(() -> new NotifyRequestListener(service, JSON).handle(exchange(body)))
                          .isInstanceOf(RuntimeException.class);
                    }));
  }
}
