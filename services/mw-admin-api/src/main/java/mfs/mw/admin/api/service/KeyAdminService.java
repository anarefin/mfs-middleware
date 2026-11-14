package mfs.mw.admin.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.admin.api.client.CryptoKeyClient;
import mfs.mw.admin.api.config.AdminApiProperties;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.AuditEvent;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.CryptoKeys;
import mfs.mw.admin.api.dto.KeyCeremonyBody;
import mfs.mw.admin.api.dto.KeyCeremonyView;
import mfs.mw.admin.api.dto.KeyChangeBody;
import mfs.mw.admin.api.dto.KeyChangeBody.KeyAction;
import mfs.mw.admin.api.dto.KeyComponentBody;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.ChangeRequestMapper;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.ChangeRequestRepository;
import mfs.mw.admin.api.util.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keys and the key ceremony on the console (ticket 75, spec 18.8, D23, OWN-08): the key list from
 * mw-crypto ({@link AdminRole#READ_KEYS}); the maker side of key change requests and ceremonies
 * ({@link AdminRole#KEY_ADMIN}, fresh MFA); a custodian's component, passed to mw-crypto, which
 * combines the two inside the HSM and answers the KCV. The change request (resourceType {@code
 * key}) keeps who entered which share and when, and the KCV; never a component. Approval is {@link
 * KeyChangeHandler}'s.
 */
@Service
public class KeyAdminService {

  private static final Logger log = LoggerFactory.getLogger(KeyAdminService.class);
  /** The purposes the ceremony makes (HMAC-SHA256 keys; KEKs and the LMK are a follow-up). */
  static final Pattern CEREMONY_PURPOSE = Pattern.compile("PIN_HMAC|TOKENISE_[A-Z][A-Z0-9]{0,23}|HMAC_[A-Z][A-Z0-9]{0,23}");

  private final CryptoKeyClient crypto;
  private final ChangeRequestService changes;
  private final ChangeRequestRepository requests;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final AdminApiProperties props;
  private final Clock clock;

  public KeyAdminService(
      CryptoKeyClient crypto,
      ChangeRequestService changes,
      ChangeRequestRepository requests,
      OutboxWriter outbox,
      TransactionTemplate tx,
      AdminApiProperties props,
      Clock clock) {
    this.crypto = crypto;
    this.changes = changes;
    this.requests = requests;
    this.outbox = outbox;
    this.tx = tx;
    this.props = props;
    this.clock = clock;
  }

  public CryptoKeys.Page list(Operator operator) {
    if (!operator.hasAny(AdminRole.READ_KEYS)) {
      throw new ForbiddenException("Viewing keys needs the security-admin or auditor role");
    }
    return crypto.list();
  }

  /** KEY_GENERATE, KEY_ROTATE or KEY_RETIRE, checked against the key as mw-crypto has it now. */
  public ChangeRequestView requestChange(Operator maker, KeyChangeBody body) {
    requireKeyAdmin(maker);
    Optional<CryptoKeys.Key> key = find(body.keyId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("keyId", body.keyId());
    String before;
    String after;
    switch (body.action()) {
      case KEY_GENERATE -> {
        if (body.purpose() == null || body.version() != null) {
          throw invalid("purpose", "KEY_GENERATE needs a purpose and no version");
        }
        if (key.isPresent()) {
          throw notFitting("The key exists already");
        }
        payload.put("purpose", body.purpose());
        before = "none";
        after = body.keyId() + ".v1 ACTIVE";
      }
      case KEY_ROTATE -> {
        CryptoKeys.Key k = key.orElseThrow(() -> notFitting("No such key"));
        if (body.purpose() != null || body.version() != null) {
          throw invalid("purpose", "KEY_ROTATE takes no purpose or version");
        }
        payload.put("purpose", k.purpose());
        before = k.active() == null ? "none" : k.active().kid() + " ACTIVE";
        after = body.keyId() + ".v" + (k.newest().version() + 1) + " ACTIVE";
      }
      case KEY_RETIRE -> {
        CryptoKeys.Key k = key.orElseThrow(() -> notFitting("No such key"));
        if (body.version() == null || body.purpose() != null) {
          throw invalid("version", "KEY_RETIRE needs the version to retire");
        }
        CryptoKeys.Version v = k.versions().stream().filter(x -> x.version() == body.version()).findFirst()
            .orElseThrow(() -> notFitting("No such key version"));
        if (!"VERIFY_ONLY".equals(v.state())) {
          throw notFitting("Only a VERIFY_ONLY version can be retired");
        }
        payload.put("purpose", k.purpose());
        payload.put("version", body.version());
        before = v.kid() + " VERIFY_ONLY";
        after = v.kid() + " RETIRED";
      }
      default -> throw invalid("action", "Use POST /admin/v1/key-ceremonies for a ceremony");
    }
    return changes.open(maker, ChangeRequest.KEY, body.keyId(), body.action().name(), payload,
        diff(before, after), body.reason());
  }

  /** Opens a KEY_CEREMONY request; the custodians then enter their components. */
  public ChangeRequestView startCeremony(Operator maker, KeyCeremonyBody body) {
    requireKeyAdmin(maker);
    if (!CEREMONY_PURPOSE.matcher(body.purpose()).matches()) {
      throw invalid("purpose", "The ceremony makes HMAC keys only: PIN_HMAC, TOKENISE_<domain>, HMAC_<use>");
    }
    Optional<CryptoKeys.Key> key = find(body.keyId());
    if (key.isPresent() && !key.get().purpose().equals(body.purpose())) {
      throw notFitting("The key has another purpose");
    }
    int next = key.map(k -> k.newest().version() + 1).orElse(1);
    String kid = body.keyId() + ".v" + next;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("keyId", body.keyId());
    payload.put("purpose", body.purpose());
    payload.put("kid", kid);
    payload.put("shares", List.of());
    String before = key.map(k -> k.active() == null ? "none" : k.active().kid() + " ACTIVE").orElse("none");
    return changes.open(maker, ChangeRequest.KEY, body.keyId(), KeyAction.KEY_CEREMONY.name(), payload,
        diff(before, kid + " ACTIVE"), body.reason());
  }

  /** The ceremony's progress, from its change request. */
  public KeyCeremonyView ceremony(Operator operator, UUID id) {
    if (!operator.hasAny(AdminRole.READ_KEYS)) {
      throw new ForbiddenException("Viewing a key ceremony needs the security-admin or auditor role");
    }
    return view(ceremonyRequest(id));
  }

  /** One custodian's component, in the custodian's own session; returns the progress (KCV once combined). */
  public KeyCeremonyView enterComponent(Operator custodian, UUID id, KeyComponentBody body) {
    requireKeyAdmin(custodian);
    ChangeRequest cr = ceremonyRequest(id);
    Instant now = clock.instant();
    if (cr.status() != ChangeRequest.Status.PENDING) {
      throw new MwException(ErrorCodes.VAL_REQUEST_DECIDED, "The ceremony was already decided")
          .withStatus(HttpStatus.CONFLICT);
    }
    if (cr.expiredAt(now)) {
      throw new MwException(ErrorCodes.VAL_REQUEST_EXPIRED, "The ceremony expired").withStatus(HttpStatus.CONFLICT);
    }
    CryptoKeys.Ceremony c = crypto.enterComponent(
        id, String.valueOf(cr.payload().get("keyId")), String.valueOf(cr.payload().get("purpose")),
        custodian.username(), body.component(), body.checkValue());
    Map<String, Object> payload = new LinkedHashMap<>(cr.payload());
    List<Map<String, Object>> shares = new ArrayList<>();
    for (CryptoKeys.Share s : c.shares()) {
      shares.add(Map.of("share", s.share(), "custodian", s.custodian(), "enteredAt", s.enteredAt().toString()));
    }
    payload.put("shares", shares);
    payload.put("kid", c.kid());
    if (c.checkValue() != null) {
      payload.put("checkValue", c.checkValue());
    }
    int share = c.shares().size();
    String correlationId = CorrelationIds.current();
    ChangeRequest updated =
        tx.execute(
            s -> {
              if (!requests.updatePendingPayload(id, payload)) {
                throw new MwException(ErrorCodes.VAL_REQUEST_DECIDED, "The ceremony was decided meanwhile")
                    .withStatus(HttpStatus.CONFLICT);
              }
              AuditEvent e = ChangeRequestMapper.componentEntered(cr, custodian.username(), share, c.checkValue(),
                  correlationId, now);
              outbox.append(ChangeRequestService.AUDIT_TOPIC, e.resource(),
                  new EventMetadata(e.correlationId(), cr.id().toString(), e.auditId(), 1), e);
              return requests.find(id).orElseThrow();
            });
    log.atInfo()
        .addKeyValue("event", "admin.key_ceremony.component_entered")
        .addKeyValue("changeRequestId", id.toString())
        .addKeyValue("share", share)
        .addKeyValue("custodian", custodian.username())
        .log("key ceremony component entered");
    return view(updated);
  }

  private ChangeRequest ceremonyRequest(UUID id) {
    return requests.find(id)
        .filter(c -> ChangeRequest.KEY.equals(c.resourceType()) && KeyAction.KEY_CEREMONY.name().equals(c.action()))
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such key ceremony")
            .withStatus(HttpStatus.NOT_FOUND));
  }

  private void requireKeyAdmin(Operator operator) {
    if (!operator.hasAny(AdminRole.KEY_ADMIN)) {
      throw new ForbiddenException("Key changes need the security-admin role");
    }
    KeyChangeHandler.requireFreshMfa(operator, clock.instant(), props);
  }

  private Optional<CryptoKeys.Key> find(String keyId) {
    return crypto.list().items().stream().filter(k -> k.keyId().equals(keyId)).findFirst();
  }

  @SuppressWarnings("unchecked")
  private static KeyCeremonyView view(ChangeRequest cr) {
    List<KeyCeremonyView.Share> shares = new ArrayList<>();
    if (cr.payload().get("shares") instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Map<?, ?> m) {
          shares.add(new KeyCeremonyView.Share(((Number) m.get("share")).intValue(), String.valueOf(m.get("custodian")),
              String.valueOf(m.get("enteredAt"))));
        }
      }
    }
    Object kcv = cr.payload().get("checkValue");
    return new KeyCeremonyView(cr.id(), String.valueOf(cr.payload().get("keyId")),
        String.valueOf(cr.payload().get("purpose")), (String) cr.payload().get("kid"), cr.status().name(), shares,
        kcv == null ? null : String.valueOf(kcv));
  }

  private static Map<String, Object> diff(String before, String after) {
    return Map.of("before", Map.of("key", before), "after", Map.of("key", after));
  }

  private static MwException invalid(String field, String issue) {
    return new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
        .withDetails(List.of(new mfs.mw.admin.api.dto.ErrorDetail(field, issue)))
        .withStatus(HttpStatus.BAD_REQUEST);
  }

  private static MwException notFitting(String message) {
    return new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, message).withStatus(HttpStatus.CONFLICT);
  }
}
