package mfs.mw.adapter.billpay.partner.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.adapter.billpay.dto.ConfigChangedEvent;
import mfs.mw.adapter.billpay.partner.template.PartnerInstance;
import mfs.mw.adapter.billpay.partner.template.PartnerInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-memory snapshot of partner config (spec 20.4): seeded from {@link PartnerProperties}, then
 * changed only by {@code mw.config.changed.v1} PARTNER records ({@code configKey}
 * {@code partner/<id>}). Each change builds a new immutable map and swaps it in one step, so a
 * call sees either the old or the new config of a partner, never a mix. A record whose version is
 * not newer than the one held is ignored; an UPSERT whose value is incomplete or does not compile
 * is rejected and the old config stays. Every value also passes the static security rules ({@link
 * PartnerEndpointPolicy}: endpoint host allowlist, https, credentials only under the partner's own
 * secrets); a record that breaks them is refused with a {@code security.partner_config_refused}
 * event, so a config-changed record can change operational fields (enabled, timeouts, mappings,
 * error maps) but cannot point a partner, or its credentials, anywhere the static config does not
 * allow.
 */
@Component
public class PartnerConfigStore {

  public static final String KEY_PREFIX = "partner/";

  private static final Logger log = LoggerFactory.getLogger(PartnerConfigStore.class);

  /** Instance (null once deleted) and the version that last changed it. */
  private record Entry(long version, PartnerInstance instance) {}

  private final AtomicReference<Map<String, Entry>> snapshot;
  private final String defaultPartner;
  private final PartnerInstanceFactory factory;
  private final JsonMapper json;
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  public PartnerConfigStore(PartnerProperties props, PartnerInstanceFactory factory, JsonMapper json) {
    this.factory = factory;
    this.json = json;
    this.defaultPartner = props.defaultPartner();
    Map<String, Entry> seed = new LinkedHashMap<>();
    // a broken seed fails start-up: better than serving a partner with half a config
    props.instances().forEach((id, config) -> seed.put(id, new Entry(0, factory.create(id, 0, config))));
    this.snapshot = new AtomicReference<>(Map.copyOf(seed));
  }

  /** The current instance with this id. */
  public Optional<PartnerInstance> instance(String id) {
    Entry e = id == null ? null : snapshot.get().get(id);
    return e == null ? Optional.empty() : Optional.ofNullable(e.instance());
  }

  /** Instance serving {@code partnerId}: its own, else the default one, else empty. */
  public Optional<PartnerInstance> resolve(String partnerId) {
    Optional<PartnerInstance> own = instance(partnerId);
    return own.isPresent() ? own : instance(defaultPartner);
  }

  /** Every current instance id with its route template. */
  public Map<String, String> templates() {
    Map<String, String> out = new HashMap<>();
    snapshot.get().forEach((id, e) -> {
      if (e.instance() != null) {
        out.put(id, e.instance().config().template());
      }
    });
    return out;
  }

  /** Runs {@code listener} after every applied change (route registration). */
  public void onChange(Runnable listener) {
    listeners.add(listener);
  }

  /**
   * Applies one config-changed record.
   *
   * @return true when the snapshot changed
   */
  public boolean apply(ConfigChangedEvent event) {
    if (event.configType() != ConfigChangedEvent.ConfigType.PARTNER
        || event.configKey() == null
        || !event.configKey().startsWith(KEY_PREFIX)) {
      return false;
    }
    String id = event.configKey().substring(KEY_PREFIX.length());
    boolean changed;
    synchronized (this) {
      Map<String, Entry> current = snapshot.get();
      Entry held = current.get(id);
      if (held != null && event.version() <= held.version()) {
        log.atInfo()
            .addKeyValue("event", "config.stale")
            .addKeyValue("configKey", event.configKey())
            .addKeyValue("version", event.version())
            .addKeyValue("heldVersion", held.version())
            .log("older partner config ignored");
        return false;
      }
      Entry next;
      if (event.change() == ConfigChangedEvent.Change.DELETE) {
        next = new Entry(event.version(), null);
      } else {
        try {
          PartnerConfig config = json.convertValue(event.value(), PartnerConfig.class);
          next = new Entry(event.version(), factory.create(id, event.version(), config));
        } catch (PartnerConfigRefusedException e) {
          if (e.security()) {
            // security event: the record tried to leave the static allowlists (no secret values)
            log.atWarn()
                .addKeyValue("event", "security.partner_config_refused")
                .addKeyValue("configKey", event.configKey())
                .addKeyValue("version", event.version())
                .addKeyValue("changedBy", event.changedBy())
                .addKeyValue("approvedBy", event.approvedBy())
                .addKeyValue("correlationId", event.correlationId())
                .addKeyValue("detail", e.getMessage())
                .log("partner config refused by security policy, previous config kept");
          } else {
            log.atWarn()
                .addKeyValue("event", "config.rejected")
                .addKeyValue("configKey", event.configKey())
                .addKeyValue("version", event.version())
                .addKeyValue("detail", e.getMessage())
                .log("partner config rejected, previous config kept");
          }
          return false;
        } catch (RuntimeException e) {
          log.atWarn()
              .addKeyValue("event", "config.rejected")
              .addKeyValue("configKey", event.configKey())
              .addKeyValue("version", event.version())
              .addKeyValue("detail", e.getMessage())
              .log("partner config rejected, previous config kept");
          return false;
        }
      }
      Map<String, Entry> updated = new HashMap<>(current);
      updated.put(id, next);
      snapshot.set(Map.copyOf(updated));
      changed = true;
    }
    log.atInfo()
        .addKeyValue("event", "config.applied")
        .addKeyValue("configKey", event.configKey())
        .addKeyValue("change", event.change())
        .addKeyValue("version", event.version())
        .log("partner config applied");
    listeners.forEach(Runnable::run);
    return changed;
  }
}
