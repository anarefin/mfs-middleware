package mfs.mw.orchestrator.service.routing;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.orchestrator.dto.ConfigChangedEvent;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.TxnDetail.PartnerCallView;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.repository.PartnerCallRepository;
import mfs.mw.orchestrator.repository.RoutingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * The routing snapshot (ticket 98, FRS PLT-041): loaded from this service's tables at start, replaced
 * when {@code mw.config.changed.v1} carries a routing resource, and re-read on the safety reload.
 * Partner health from {@code mw.partner.route.status.v1} stays in memory across a reload.
 */
@Service
public class RoutingCatalog {

  private static final Logger log = LoggerFactory.getLogger(RoutingCatalog.class);

  static final String RULE = "ROUTING_RULE";
  static final String PREFIX = "MSISDN_PREFIX";
  static final String MNP = "MNP_OVERRIDE";
  static final String MAINTENANCE = "MAINTENANCE_MESSAGE";

  private final RoutingRepository repository;
  private final RoutingService routing;
  private final PartnerCallRepository partnerCalls;
  private final Clock clock;
  private final AtomicReference<RoutingSnapshot> current = new AtomicReference<>(RoutingSnapshot.empty());

  public RoutingCatalog(
      RoutingRepository repository,
      RoutingService routing,
      PartnerCallRepository partnerCalls,
      Clock clock) {
    this.repository = repository;
    this.routing = routing;
    this.partnerCalls = partnerCalls;
    this.clock = clock;
  }

  /** Loads the tables. A start with no database (the Vault binding test) leaves the snapshot empty. */
  @PostConstruct
  public void loadAtStart() {
    try {
      reload();
    } catch (DataAccessException e) {
      log.atWarn().setCause(e).log("routing snapshot not loaded at start; the reload job will retry");
    }
  }

  public void reload() {
    RoutingSnapshot loaded = repository.load();
    current.updateAndGet(prev -> loaded.withHealth(prev.healthByPartner()));
  }

  public RoutingSnapshot snapshot() {
    return current.get();
  }

  /** The operator of a top-up number: an MNP override, else the longest enabled prefix (PLT-042). */
  public Optional<String> operatorOf(String msisdn) {
    RoutingSnapshot snap = current.get();
    return MsisdnRouting.mno(msisdn, snap.prefixes(), snap.overrides());
  }

  /** {@code 01XXXXXXXXX}, or null when {@code msisdn} is not a mobile number. */
  public String canonicalMsisdn(String msisdn) {
    return MsisdnRouting.canonical(msisdn);
  }

  public Decision decide(TxnRequest request) {
    return routing.decide(query(request), current.get(), clock.instant());
  }

  /** The next attempt whose wire id has not already been sent a PAY. */
  public Optional<Attempt> nextUnsent(TransactionRecord rec) {
    Decision decision = decide(rec.request());
    if (!(decision instanceof Decision.Ready ready)) {
      return Optional.empty();
    }
    Set<String> sent = sentPays(rec);
    return ready.attempts().stream().filter(a -> !sent.contains(a.wirePartnerId())).findFirst();
  }

  public void noteHealth(String partnerId, String health) {
    if (partnerId == null || partnerId.isBlank()) {
      return;
    }
    current.updateAndGet(
        snap -> {
          Map<String, String> healthByPartner = new LinkedHashMap<>(snap.healthByPartner());
          if (health == null || health.isBlank()) {
            healthByPartner.remove(partnerId);
          } else {
            healthByPartner.put(partnerId, health);
          }
          return snap.withHealth(healthByPartner);
        });
  }

  /** Applies one config change. A type this module does not own is ignored. An older version is ignored. */
  public void apply(ConfigChangedEvent event) {
    if (event == null || event.configType() == null || event.configKey() == null) {
      return;
    }
    String id = idOf(event.configKey());
    int version = (int) event.version();
    boolean delete = event.change() == ConfigChangedEvent.Change.DELETE;
    switch (event.configType()) {
      case ROUTING_RULE -> {
        if (delete) {
          repository.deleteRule(id, version);
        } else {
          repository.upsert(rule(event, id, version));
        }
      }
      case MSISDN_PREFIX -> {
        if (delete) {
          repository.deletePrefix(id, version);
        } else {
          repository.upsert(prefix(event, id, version));
        }
      }
      case MNP_OVERRIDE -> {
        if (delete) {
          repository.deleteOverride(id, version);
        } else {
          repository.upsert(override(event, id, version));
        }
      }
      case MAINTENANCE_MESSAGE -> {
        if (delete) {
          repository.deleteMessage(id, version);
        } else {
          repository.upsert(message(event, id, version));
        }
      }
      default -> {
        return;
      }
    }
    reload();
  }

  /** The operator, biller or bank a request is matched as, plus the partner the channel named. */
  RoutingQuery query(TxnRequest request) {
    PartnerRef partner = request.partner();
    String requested = partner == null || partner.partnerId() == null || partner.partnerId().isBlank()
        ? null
        : partner.partnerId();
    String biller = partner == null ? null : partner.billerCode();
    String product = partner == null ? null : partner.productCode();
    String operator = null;
    String bank = null;
    RoutingSnapshot snap = current.get();
    if ("TOPUP".equals(request.txnType())) {
      String fromNumber =
          MsisdnRouting.mno(
                  request.creditParty() == null ? null : request.creditParty().id(), snap.prefixes(), snap.overrides())
              .orElse(null);
      operator = isOperatorCode(requested, snap) ? requested : fromNumber;
    } else if ("ADD_MONEY_BANK".equals(request.txnType())) {
      bank = requested;
    }
    long amount = request.amount() == null ? 0 : request.amount().valueMinor();
    return new RoutingQuery(request.txnType(), product, biller, bank, operator, amount, request.channel(), requested);
  }

  /** A channel partner id such as {@code gp}, not a connector such as {@code ocs}. */
  private static boolean isOperatorCode(String id, RoutingSnapshot snap) {
    if (id == null || id.isBlank()) {
      return false;
    }
    for (MsisdnPrefix prefix : snap.prefixes()) {
      if (prefix.enabled() && id.equalsIgnoreCase(prefix.mno())) {
        return true;
      }
    }
    for (RoutingRule rule : snap.rules()) {
      if (rule.enabled() && id.equalsIgnoreCase(rule.operatorCode())) {
        return true;
      }
    }
    return false;
  }

  private Set<String> sentPays(TransactionRecord rec) {
    List<String> ids = new ArrayList<>();
    for (PartnerCallView call : partnerCalls.of(rec.txnId())) {
      if ("PAY".equals(call.operation()) && call.partnerId() != null) {
        ids.add(call.partnerId());
      }
    }
    return Set.copyOf(ids);
  }

  private RoutingRule rule(ConfigChangedEvent event, String id, int version) {
    Map<String, Object> value = event.value() == null ? Map.of() : event.value();
    return new RoutingRule(
        id,
        text(value, "txnType", null),
        text(value, "productCode", null),
        text(value, "billerCode", null),
        text(value, "bankCode", null),
        text(value, "operatorCode", null),
        longOrNull(value, "amountMinMinor"),
        longOrNull(value, "amountMaxMinor"),
        text(value, "channel", null),
        text(value, "primaryPartnerId", null),
        strings(value, "fallbacks"),
        intOr(value, "priority", 0),
        enabled(value),
        version);
  }

  private MsisdnPrefix prefix(ConfigChangedEvent event, String id, int version) {
    Map<String, Object> value = event.value();
    return new MsisdnPrefix(id, text(value, "mno", null), enabled(value), version);
  }

  private MnpOverride override(ConfigChangedEvent event, String id, int version) {
    Map<String, Object> value = event.value();
    String msisdn = MsisdnRouting.canonical(id);
    return new MnpOverride(msisdn == null ? id : msisdn, text(value, "mno", null), enabled(value), version);
  }

  private MaintenanceMessage message(ConfigChangedEvent event, String id, int version) {
    Map<String, Object> value = event.value() == null ? Map.of() : event.value();
    return new MaintenanceMessage(
        id,
        text(value, "partnerId", null),
        text(value, "billerCode", null),
        text(value, "messageEn", null),
        text(value, "messageBn", null),
        instant(value, "validFrom"),
        instant(value, "validUntil"),
        enabled(value),
        version);
  }

  private static String idOf(String configKey) {
    int slash = configKey.indexOf('/');
    return slash < 0 ? configKey : configKey.substring(slash + 1);
  }

  private static String text(Map<String, Object> value, String key, String fallback) {
    Object raw = value == null ? null : value.get(key);
    return raw == null ? fallback : String.valueOf(raw);
  }

  private static boolean enabled(Map<String, Object> value) {
    Object raw = value == null ? null : value.get("enabled");
    return raw == null || Boolean.parseBoolean(String.valueOf(raw));
  }

  private static Long longOrNull(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    if (raw == null) {
      return null;
    }
    return raw instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(raw));
  }

  private static int intOr(Map<String, Object> value, String key, int fallback) {
    Object raw = value.get(key);
    if (raw == null) {
      return fallback;
    }
    return raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw));
  }

  private static List<String> strings(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object item : list) {
      if (item != null) {
        out.add(String.valueOf(item));
      }
    }
    return out;
  }

  private static Instant instant(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    if (raw == null || String.valueOf(raw).isBlank()) {
      return null;
    }
    return Instant.parse(String.valueOf(raw));
  }

  /** Definite pre-send refusals: the partner was not paid, so another connector may be tried. */
  public static boolean preSendRefusal(String code) {
    if (code == null) {
      return false;
    }
    return switch (code.toUpperCase(Locale.ROOT)) {
      case "MW-SYS-PARTNER_CIRCUIT_OPEN",
          "MW-SYS-PARTNER_BUSY",
          "MW-SYS-PARTNER_UNAVAILABLE",
          "MW-SYS-PARTNER_DISABLED",
          "MW-SYS-PARTNER_MISCONFIGURED",
          "MW-SYS-PARTNER_MAINTENANCE",
          "MW-PTR-UNKNOWN-PARTNER" -> true;
      default -> false;
    };
  }
}
