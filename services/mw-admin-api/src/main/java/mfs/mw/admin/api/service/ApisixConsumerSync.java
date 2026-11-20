package mfs.mw.admin.api.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.admin.api.client.ApisixAdmin;
import mfs.mw.admin.api.client.ApisixAdmin.Kind;
import mfs.mw.admin.api.config.ApisixProperties;
import mfs.mw.admin.api.dto.ChannelClientSpec;
import mfs.mw.admin.api.entity.ChannelClient;
import mfs.mw.admin.api.mapper.ChannelClientMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projects the channel client registry onto APISIX (ticket 100a): the registry is the source of
 * truth, APISIX a projection written through the Admin API. Only clients whose auth method has a
 * consumer ({@link ChannelClientSpec.AuthMethod#hasConsumer()}, BASIC here) are projected:
 *
 * <ul>
 *   <li>consumer {@code <clientId with - as _>} (the name the routes' {@code consumer-restriction}
 *       lists, e.g. {@code ussd_gp}): {@code basic-auth} user = clientId, password = the APISIX
 *       secret reference {@code <secretRef>/<clientId>/password} (Vault KV v1 mount {@code apisix/}),
 *       never a value; labels {@code managed-by=mw-admin-api}, {@code channel-client}, {@code
 *       registry-version};
 *   <li>consumer group {@code client_<name>} with {@code limit-req} (ratePerSec), {@code limit-count}
 *       (dailyQuota per 24 h, Redis, 429) and {@code ip-restriction} (ipAllowList), only when the
 *       client has one of them;
 *   <li>a disabled client keeps its consumer without any credential (its basic-auth fails, 401) and
 *       without a group, so {@code apisix-init} (which skips a managed consumer) never brings the old
 *       credential back.
 * </ul>
 *
 * Applying is idempotent: an object equal to the wanted one (APISIX's own fields aside) is not
 * written again. The group is written before the consumer that names it and deleted after the
 * consumer no longer does. {@link #reconcile} also deletes the managed consumers and groups of
 * clients the registry no longer projects; objects without the label are never touched.
 */
@Service
public class ApisixConsumerSync {

  public static final String MANAGED_BY = "managed-by";
  public static final String MANAGER = "mw-admin-api";
  static final String CLIENT_LABEL = "channel-client";
  static final int DAY_SECONDS = 86_400;
  /** Fields APISIX adds to a stored object; ignored when comparing. */
  private static final Set<String> APISIX_FIELDS = Set.of("create_time", "update_time", "id");

  private final ApisixAdmin apisix;
  private final ApisixProperties props;
  private final JsonMapper json;

  public ApisixConsumerSync(ApisixAdmin apisix, ApisixProperties props, JsonMapper json) {
    this.apisix = apisix;
    this.props = props;
    this.json = json;
  }

  /** What one apply did. */
  public enum Outcome {
    /** Something was written or deleted. */
    WRITTEN,
    /** APISIX already had it. */
    UNCHANGED,
    /** The client's auth method has no consumer: nothing to project. */
    NONE
  }

  /** What a reconcile did: objects written, objects of unknown clients removed. */
  public record Report(int written, int unchanged, int removed) {}

  /** The APISIX consumer of a client id. */
  public static String consumerName(String clientId) {
    return ChannelClientMapper.consumerName(clientId);
  }

  static String groupName(String consumer) {
    return "client_" + consumer;
  }

  /** Brings APISIX in line with one registry row. */
  public Outcome apply(ChannelClient client) {
    if (!client.spec().authMethod().hasConsumer()) {
      return Outcome.NONE;
    }
    String consumer = consumerName(client.clientId());
    String group = groupName(consumer);
    Optional<Map<String, Object>> groupBody = groupBody(client, group);
    boolean written = false;
    if (groupBody.isPresent()) {
      written |= ensure(Kind.CONSUMER_GROUPS, group, groupBody.get());
    }
    written |= ensure(Kind.CONSUMERS, consumer, consumerBody(client, consumer, groupBody.isPresent() ? group : null));
    if (groupBody.isEmpty()) {
      written |= deleteManaged(Kind.CONSUMER_GROUPS, group);
    }
    return written ? Outcome.WRITTEN : Outcome.UNCHANGED;
  }

  /**
   * Applies every row, then removes the managed consumers (and their groups) whose client is not
   * projected any more.
   */
  public Report reconcile(List<ChannelClient> clients) {
    int written = 0;
    int unchanged = 0;
    Set<String> wanted = new HashSet<>();
    for (ChannelClient c : clients) {
      Outcome o = apply(c);
      if (o == Outcome.NONE) {
        continue;
      }
      String consumer = consumerName(c.clientId());
      wanted.add(consumer);
      if (o == Outcome.WRITTEN) {
        written++;
      } else {
        unchanged++;
      }
    }
    int removed = 0;
    for (Map<String, Object> c : apisix.list(Kind.CONSUMERS)) {
      String name = String.valueOf(c.get("username"));
      if (managed(c) && !wanted.contains(name)) {
        apisix.delete(Kind.CONSUMERS, name);
        deleteManaged(Kind.CONSUMER_GROUPS, groupName(name));
        removed++;
      }
    }
    for (Map<String, Object> g : apisix.list(Kind.CONSUMER_GROUPS)) {
      Object id = g.get("id");
      if (managed(g) && id != null && !wanted.contains(String.valueOf(id).replaceFirst("^client_", ""))) {
        apisix.delete(Kind.CONSUMER_GROUPS, String.valueOf(id));
      }
    }
    return new Report(written, unchanged, removed);
  }

  // --- the wanted objects -----------------------------------------------------------------------

  Map<String, Object> consumerBody(ChannelClient client, String consumer, String group) {
    ChannelClientSpec s = client.spec();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", consumer);
    body.put("desc", "Channel client " + client.clientId() + " (" + s.type() + "), projected from the mw-admin-api registry"
        + (s.isEnabled() ? "" : "; disabled: no credential"));
    Map<String, Object> labels = labels(client);
    labels.put("registry-version", String.valueOf(client.version()));
    body.put("labels", labels);
    if (s.isEnabled()) {
      body.put("plugins", Map.of("basic-auth", Map.of(
          "username", client.clientId(),
          "password", props.secretRef() + "/" + client.clientId() + "/password")));
      if (group != null) {
        body.put("group_id", group);
      }
    }
    return body;
  }

  Optional<Map<String, Object>> groupBody(ChannelClient client, String group) {
    ChannelClientSpec s = client.spec();
    if (!s.isEnabled()) {
      return Optional.empty();
    }
    Map<String, Object> plugins = new LinkedHashMap<>();
    if (s.ratePerSec() != null) {
      plugins.put("limit-req", Map.of(
          "rate", s.ratePerSec(), "burst", s.ratePerSec(), "key_type", "var", "key", "consumer_name",
          "rejected_code", 429));
    }
    if (s.dailyQuota() != null) {
      Map<String, Object> quota = new LinkedHashMap<>();
      quota.put("count", s.dailyQuota());
      quota.put("time_window", DAY_SECONDS);
      quota.put("key_type", "var");
      quota.put("key", "consumer_name");
      quota.put("group", group);
      quota.put("rejected_code", 429);
      quota.put("show_limit_quota_header", true);
      quota.put("policy", "redis");
      quota.put("redis_host", props.redis().host());
      quota.put("redis_port", props.redis().port());
      quota.put("redis_password", props.redis().passwordRef());
      quota.put("redis_database", 0);
      quota.put("redis_timeout", 1000);
      quota.put("allow_degradation", true);
      plugins.put("limit-count", quota);
    }
    if (!s.ipAllowList().isEmpty()) {
      plugins.put("ip-restriction", Map.of("whitelist", s.ipAllowList()));
    }
    if (plugins.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("desc", "Limits of channel client " + client.clientId() + " (mw-admin-api registry)");
    body.put("labels", labels(client));
    body.put("plugins", plugins);
    return Optional.of(body);
  }

  private static Map<String, Object> labels(ChannelClient client) {
    Map<String, Object> labels = new LinkedHashMap<>();
    labels.put(MANAGED_BY, MANAGER);
    labels.put(CLIENT_LABEL, client.clientId());
    return labels;
  }

  // --- writing ----------------------------------------------------------------------------------

  /** PUTs the object unless APISIX already has an equal one; true when written. */
  private boolean ensure(Kind kind, String id, Map<String, Object> wanted) {
    Optional<Map<String, Object>> current = apisix.get(kind, id);
    if (current.isPresent() && canonical(current.get()).equals(canonical(wanted))) {
      return false;
    }
    apisix.put(kind, id, wanted);
    return true;
  }

  private boolean deleteManaged(Kind kind, String id) {
    Optional<Map<String, Object>> current = apisix.get(kind, id);
    if (current.isPresent() && managed(current.get())) {
      apisix.delete(kind, id);
      return true;
    }
    return false;
  }

  private static boolean managed(Map<String, Object> object) {
    return object.get("labels") instanceof Map<?, ?> labels && MANAGER.equals(labels.get(MANAGED_BY));
  }

  /** The object as a JSON tree without APISIX's own fields (numbers compared by value). */
  private JsonNode canonical(Map<String, Object> object) {
    Map<String, Object> copy = new LinkedHashMap<>(object);
    APISIX_FIELDS.forEach(copy::remove);
    return json.readTree(json.writeValueAsString(copy));
  }
}
