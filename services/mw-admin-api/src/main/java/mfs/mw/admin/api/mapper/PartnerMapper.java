package mfs.mw.admin.api.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.PartnerView;
import mfs.mw.admin.api.entity.Partner;

/**
 * Partners (ticket 38) to their {@code admin.v1} view, the diff a maker's change request shows and
 * the {@code mw.config.changed.v1} PARTNER record an approval publishes (key {@code
 * partner/<id>}, value the full config).
 */
public final class PartnerMapper {

  public static final String KEY_PREFIX = "partner/";

  private PartnerMapper() {}

  public static PartnerView view(Partner p) {
    return new PartnerView(p.id(), p.family(), p.version(), p.config(), p.updatedAt(), p.updatedBy(), p.approvedBy());
  }

  public static String configKey(String partnerId) {
    return KEY_PREFIX + partnerId;
  }

  /**
   * The top-level config fields that differ, each side holding its value (a field absent on one
   * side is absent there).
   */
  public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
    Set<String> keys = new LinkedHashSet<>(before.keySet());
    keys.addAll(after.keySet());
    Map<String, Object> b = new LinkedHashMap<>();
    Map<String, Object> a = new LinkedHashMap<>();
    for (String k : keys) {
      if (!Objects.equals(before.get(k), after.get(k))) {
        if (before.get(k) != null) {
          b.put(k, before.get(k));
        }
        if (after.get(k) != null) {
          a.put(k, after.get(k));
        }
      }
    }
    Map<String, Object> diff = new LinkedHashMap<>();
    diff.put("before", b);
    diff.put("after", a);
    return diff;
  }

  /** The approved full config as a config-changed record (spec 20.4). */
  public static ConfigChangedEvent configChanged(
      String partnerId, long version, Map<String, Object> value, String makerId, String checkerId,
      String correlationId, Instant at) {
    return new ConfigChangedEvent(
        1, configKey(partnerId), ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, version, value,
        at, makerId, checkerId, correlationId);
  }
}
