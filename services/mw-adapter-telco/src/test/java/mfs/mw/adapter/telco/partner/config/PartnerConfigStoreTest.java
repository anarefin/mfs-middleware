package mfs.mw.adapter.telco.partner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.adapter.telco.dto.ConfigChangedEvent;
import mfs.mw.adapter.telco.partner.template.PartnerInstanceFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The partner config snapshot: seeds, then mw.config.changed.v1 PARTNER records, newest version
 * wins, a bad record never replaces a good config. Values are written as JSON, as the admin API
 * publishes them.
 */
class PartnerConfigStoreTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final PartnerInstanceFactory FACTORY =
      new PartnerInstanceFactory(ref -> "fake", Clock.systemUTC(), JSON);

  static final String GP_V1 =
      """
      {"template": "rest-json-request-reply", "baseUrl": "http://ocs.invalid", "timeoutMs": 20000,
       "operations": {"PAY": {"method": "POST", "path": "/ocs/v1/topup", "definiteStatuses": [422],
                              "response": "{\\"outcome\\": \\"UNKNOWN\\"}"}},
       "errorMap": {"2001": {"suffix": "TOPUP_REJECTED", "retryable": false}},
       "ambiguousCodes": ["9876"], "unknownFieldsAreIgnored": true}
      """;

  final PartnerConfigStore store =
      new PartnerConfigStore(new PartnerProperties("ocs", Map.of("ocs", config(GP_V1))), FACTORY, JSON);

  static PartnerConfig config(String json) {
    return JSON.readValue(json, PartnerConfig.class);
  }

  static ConfigChangedEvent upsert(String key, long version, String valueJson) {
    return new ConfigChangedEvent(
        1, key, ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, version,
        readMap(valueJson),
        Instant.parse("2026-11-04T10:00:00Z"), "ops.maker1", "ops.checker1", null);
  }

  static Map<String, Object> readMap(String json) {
    return JSON.readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  @Test
  void aValueIsReadWithDefaultsForWhatItLeavesOut() {
    PartnerConfig c = config(GP_V1);

    assertThat(c.enabled()).isTrue();
    assertThat(c.connectTimeoutMs()).isEqualTo(3000L);
    assertThat(c.readTimeoutMs()).isEqualTo(22_000L);
    assertThat(c.auth().scheme()).isEqualTo("none");
    assertThat(c.errorMapping("2001")).isEqualTo(new PartnerConfig.ErrorMapping("TOPUP_REJECTED", false));
    assertThat(c.errorMapping("7777")).isEqualTo(PartnerConfig.ErrorMapping.UNMAPPED);
    assertThat(c.operation("pay")).isNotNull();
    assertThat(c.problems()).isEmpty();
  }

  @Test
  void aPartnerWithoutConfigIsServedByTheDefault() {
    assertThat(store.resolve("gp")).get().extracting(i -> i.id()).isEqualTo("ocs");

    store.apply(upsert("partner/gp", 1, GP_V1));

    assertThat(store.resolve("gp")).get().extracting(i -> i.id()).isEqualTo("gp");
  }

  @Test
  void aNewerVersionReplacesTheConfigAndAnOlderOrEqualOneIsIgnored() {
    AtomicInteger changes = new AtomicInteger();
    store.onChange(changes::incrementAndGet);

    assertThat(store.apply(upsert("partner/gp", 5, GP_V1))).isTrue();
    assertThat(store.apply(upsert("partner/gp", 5, GP_V1.replace("20000", "1000")))).isFalse();
    assertThat(store.apply(upsert("partner/gp", 4, GP_V1.replace("20000", "1000")))).isFalse();
    assertThat(store.instance("gp")).get().extracting(i -> i.config().timeoutMs()).isEqualTo(20_000L);

    assertThat(store.apply(upsert("partner/gp", 6, GP_V1.replace("20000", "1000")))).isTrue();
    assertThat(store.instance("gp")).get().extracting(i -> i.config().timeoutMs()).isEqualTo(1000L);
    assertThat(changes).hasValue(2);
  }

  @Test
  void anIncompleteOrBrokenValueIsRejectedAndTheOldConfigStays() {
    store.apply(upsert("partner/gp", 1, GP_V1));

    // the contract's own sample value: no endpoint, no operations
    assertThat(store.apply(upsert("partner/gp", 2, "{\"enabled\": true, \"timeoutMs\": 20000, \"credentialsRef\": \"kv/partners/gp\"}")))
        .isFalse();
    assertThat(store.apply(upsert("partner/gp", 3, GP_V1.replace("UNKNOWN\\\"}", "UNKNOWN\\\"")))).isFalse();
    assertThat(store.apply(upsert("partner/gp", 4, GP_V1.replace("rest-json-request-reply", "soap")))).isFalse();
    assertThat(store.apply(upsert("partner/gp", 5, GP_V1.replace("TOPUP_REJECTED", "topup rejected")))).isFalse();

    assertThat(store.instance("gp")).get().extracting(i -> i.version()).isEqualTo(1L);
  }

  @Test
  void aDeleteRemovesThePartnerAndAStaleUpsertCannotBringItBack() {
    store.apply(upsert("partner/gp", 3, GP_V1));
    ConfigChangedEvent delete =
        new ConfigChangedEvent(
            1, "partner/gp", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.DELETE, 4, null,
            Instant.parse("2026-11-04T10:00:00Z"), "ops.maker1", "ops.checker1", null);

    assertThat(store.apply(delete)).isTrue();
    assertThat(store.instance("gp")).isEmpty();
    assertThat(store.resolve("gp")).get().extracting(i -> i.id()).isEqualTo("ocs");
    assertThat(store.apply(upsert("partner/gp", 4, GP_V1))).isFalse();
    assertThat(store.templates()).containsOnlyKeys("ocs");
  }

  @Test
  void otherConfigTypesAndKeysAreNotPartnerConfig() {
    ConfigChangedEvent template =
        new ConfigChangedEvent(
            1, "template/TXN_P2P_SENT.bn", ConfigChangedEvent.ConfigType.TEMPLATE,
            ConfigChangedEvent.Change.DELETE, 3, null, Instant.now(), "ops.maker1", null, null);

    assertThat(store.apply(template)).isFalse();
    assertThat(store.apply(upsert("route/gp", 1, GP_V1))).isFalse();
  }

  @Test
  void aBrokenSeedFailsStartUp() {
    PartnerConfig broken = config("{\"baseUrl\": \"http://x.invalid\"}");

    assertThatThrownBy(() -> new PartnerConfigStore(new PartnerProperties(null, Map.of("x", broken)), FACTORY, JSON))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one operation");
  }

  @Test
  void theListenerSkipsRecordsItCannotUseAndAppliesTheRest() {
    PartnerConfigListener listener = new PartnerConfigListener(JSON, store);

    listener.onRecord(null);
    listener.onRecord("not json");
    listener.onRecord(
        "{\"schemaVersion\":1,\"configKey\":\"partner/gp\",\"configType\":\"PARTNER\",\"change\":\"UPSERT\","
            + "\"version\":9,\"value\":" + GP_V1 + ",\"changedAt\":\"2026-11-04T10:00:00Z\",\"changedBy\":\"ops.maker1\"}");

    assertThat(store.instance("gp")).get().extracting(i -> i.version()).isEqualTo(9L);
  }
}
