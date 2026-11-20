package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.admin.api.client.ApisixAdmin;
import mfs.mw.admin.api.config.ApisixProperties;
import mfs.mw.admin.api.dto.ChannelClientSpec;
import mfs.mw.admin.api.dto.ChannelClientSpec.AuthMethod;
import mfs.mw.admin.api.dto.ChannelClientSpec.ClientType;
import mfs.mw.admin.api.entity.ChannelClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The APISIX projection of a channel client (ticket 100a): a registry row maps to the expected
 * consumer and consumer group JSON; applying it again changes nothing; a disabled client keeps a
 * consumer without a credential; the reconcile removes what the registry no longer has, and only
 * objects it manages.
 */
class ApisixConsumerSyncTest {

  static final Instant AT = Instant.parse("2026-11-20T08:00:00Z");

  /** In-memory APISIX Admin API: stores the bodies, records every write. */
  static final class FakeApisix implements ApisixAdmin {
    final Map<String, Map<String, Object>> objects = new LinkedHashMap<>();
    final List<String> writes = new ArrayList<>();

    @Override
    public Optional<Map<String, Object>> get(Kind kind, String id) {
      return Optional.ofNullable(objects.get(kind.path() + "/" + id));
    }

    @Override
    public void put(Kind kind, String id, Map<String, Object> body) {
      writes.add("PUT " + kind.path() + "/" + id);
      objects.put(kind.path() + "/" + id, body);
    }

    @Override
    public void delete(Kind kind, String id) {
      writes.add("DELETE " + kind.path() + "/" + id);
      objects.remove(kind.path() + "/" + id);
    }

    @Override
    public List<Map<String, Object>> list(Kind kind) {
      return objects.entrySet().stream()
          .filter(e -> e.getKey().startsWith(kind.path() + "/"))
          .map(Map.Entry::getValue)
          .toList();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> plugins(String key) {
      return (Map<String, Object>) objects.get(key).get("plugins");
    }
  }

  static final ApisixProperties PROPS =
      new ApisixProperties(
          true, URI.create("http://apisix:9180"), "admin-key-t", "$secret://vault/edge",
          new ApisixProperties.Redis("redis", 6379, "$env://REDIS_PASSWORD"), Duration.ofSeconds(2), Duration.ofSeconds(5));

  final FakeApisix apisix = new FakeApisix();
  final ApisixConsumerSync sync = new ApisixConsumerSync(apisix, PROPS, JsonMapper.builder().build());

  static ChannelClient client(String id, AuthMethod auth, Long rate, Long quota, List<String> ips, boolean enabled) {
    return new ChannelClient(
        id, new ChannelClientSpec(ClientType.MNO, auth, List.of("ussd-http-gp"), ips, rate, quota, enabled), 3, AT,
        "ops-maker01", "ops-checker01");
  }

  @Test
  void aBasicClientBecomesAConsumerWithAVaultSecretAndAGroupWithItsLimits() {
    ApisixConsumerSync.Outcome outcome =
        sync.apply(client("ussd-gp", AuthMethod.BASIC, 50L, 100000L, List.of("10.10.0.0/16"), true));

    assertThat(outcome).isEqualTo(ApisixConsumerSync.Outcome.WRITTEN);
    Map<String, Object> consumer = apisix.objects.get("consumers/ussd_gp");
    assertThat(consumer.get("username")).isEqualTo("ussd_gp");
    assertThat(consumer.get("group_id")).isEqualTo("client_ussd_gp");
    assertThat(apisix.plugins("consumers/ussd_gp"))
        .isEqualTo(Map.of("basic-auth", Map.of("username", "ussd-gp", "password", "$secret://vault/edge/ussd-gp/password")));
    assertThat(consumer.get("labels").toString()).contains("managed-by=mw-admin-api", "channel-client=ussd-gp");

    Map<String, Object> group = apisix.plugins("consumer_groups/client_ussd_gp");
    assertThat(group).containsOnlyKeys("limit-req", "limit-count", "ip-restriction");
    assertThat(group.get("limit-req"))
        .isEqualTo(Map.of("rate", 50L, "burst", 50L, "key_type", "var", "key", "consumer_name", "rejected_code", 429));
    @SuppressWarnings("unchecked")
    Map<String, Object> quota = (Map<String, Object>) group.get("limit-count");
    assertThat(quota)
        .containsEntry("count", 100000L)
        .containsEntry("time_window", 86400)
        .containsEntry("rejected_code", 429)
        .containsEntry("group", "client_ussd_gp")
        .containsEntry("policy", "redis")
        .containsEntry("redis_host", "redis")
        .containsEntry("redis_password", "$env://REDIS_PASSWORD");
    assertThat(group.get("ip-restriction")).isEqualTo(Map.of("whitelist", List.of("10.10.0.0/16")));
    // the group exists before the consumer names it
    assertThat(apisix.writes).containsExactly("PUT consumer_groups/client_ussd_gp", "PUT consumers/ussd_gp");
    // never a secret value in what is written
    assertThat(apisix.objects.toString()).doesNotContain("gp-secret");
  }

  @Test
  void applyingTheSameRowAgainWritesNothing() {
    ChannelClient c = client("ussd-gp", AuthMethod.BASIC, 50L, null, List.of(), true);
    sync.apply(c);
    apisix.writes.clear();

    assertThat(sync.apply(c)).isEqualTo(ApisixConsumerSync.Outcome.UNCHANGED);
    assertThat(apisix.writes).isEmpty();
  }

  @Test
  void aClientWithoutLimitsHasNoGroupAndLosesTheOldOne() {
    sync.apply(client("ussd-gp", AuthMethod.BASIC, 50L, null, List.of(), true));

    sync.apply(client("ussd-gp", AuthMethod.BASIC, null, null, List.of(), true));

    assertThat(apisix.objects.get("consumers/ussd_gp")).doesNotContainKey("group_id");
    assertThat(apisix.objects).doesNotContainKey("consumer_groups/client_ussd_gp");
    assertThat(apisix.writes).endsWith("PUT consumers/ussd_gp", "DELETE consumer_groups/client_ussd_gp");
  }

  @Test
  void aDisabledClientKeepsAConsumerWithoutCredential() {
    sync.apply(client("ussd-gp", AuthMethod.BASIC, 50L, 1000L, List.of(), true));

    sync.apply(client("ussd-gp", AuthMethod.BASIC, 50L, 1000L, List.of(), false));

    Map<String, Object> consumer = apisix.objects.get("consumers/ussd_gp");
    assertThat(consumer).doesNotContainKeys("plugins", "group_id");
    assertThat(apisix.objects).doesNotContainKey("consumer_groups/client_ussd_gp");
  }

  @Test
  void aClientWhoseAuthMethodHasNoConsumerIsNotProjected() {
    assertThat(sync.apply(client("customer-app", AuthMethod.JWT, 50L, 1000L, List.of(), true)))
        .isEqualTo(ApisixConsumerSync.Outcome.NONE);
    assertThat(sync.apply(client("billagg", AuthMethod.HMAC, null, null, List.of(), true)))
        .isEqualTo(ApisixConsumerSync.Outcome.NONE);
    assertThat(apisix.writes).isEmpty();
  }

  @Test
  void reconcileRepairsDriftAndRemovesOnlyManagedObjectsTheRegistryNoLongerHas() {
    ChannelClient gp = client("ussd-gp", AuthMethod.BASIC, 50L, null, List.of(), true);
    ChannelClient robi = client("ussd-robi", AuthMethod.BASIC, 50L, null, List.of(), true);
    sync.reconcile(List.of(gp, robi));
    // drift: someone changed the consumer by hand; an unmanaged consumer (apisix-init's) exists
    apisix.objects.get("consumers/ussd_gp").put("plugins", Map.of());
    apisix.put(ApisixAdmin.Kind.CONSUMERS, "e2e_tester", new LinkedHashMap<>(Map.of("username", "e2e_tester")));
    apisix.writes.clear();

    ApisixConsumerSync.Report report = sync.reconcile(List.of(gp));

    assertThat(report.written()).isEqualTo(1);
    assertThat(report.removed()).isEqualTo(1);
    assertThat(apisix.plugins("consumers/ussd_gp")).containsKey("basic-auth");
    assertThat(apisix.objects).doesNotContainKeys("consumers/ussd_robi", "consumer_groups/client_ussd_robi");
    assertThat(apisix.objects).containsKey("consumers/e2e_tester");
  }
}
