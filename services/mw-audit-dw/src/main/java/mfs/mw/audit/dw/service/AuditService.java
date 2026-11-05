package mfs.mw.audit.dw.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.entity.AuditRow;
import mfs.mw.audit.dw.entity.ChainHead;
import mfs.mw.audit.dw.util.MaskingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes {@code mw.audit.event.v1} into {@code audit.event} (spec 14.3): masks it (18.3), appends
 * it to the current month's hash chain and stores it once per {@code auditId}
 * ({@code audit.event_key}, kept for good, unlike the purged inbox), all in one transaction. Only
 * INSERTs into {@code audit.event}; the chain tail comes from {@code audit.chain_head}, whose row
 * lock serialises writers of one month.
 *
 * <p>Masking: every text field and {@code before}/{@code after} go through {@link
 * MaskingPolicy#scrub(String)}, with two system-id exemptions so ids stay traceable: a {@code
 * resource} that is exactly {@code <type>/<uuid>} ({@link #SYSTEM_RESOURCE}; producers build these
 * from system ids) and a {@code correlationId} that is a UUID ({@link #RFC_UUID}), each only when
 * the scrub also leaves it unchanged; a valid UUID that hides a 10+ digit run or spreads an MSISDN
 * over its groups is stored scrubbed.
 * {@code txnId} is parsed as a UUID.
 */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  /** A lower-case RFC 9562 UUID: version nibble 1-8, variant 8-b. */
  private static final String UUID_TEXT =
      "[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

  private static final Pattern RFC_UUID = Pattern.compile(UUID_TEXT);

  /** A resource that names a system id: {@code <type>/<uuid>}, nothing more. */
  private static final Pattern SYSTEM_RESOURCE = Pattern.compile("[a-z][a-z0-9-]*/" + UUID_TEXT);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final Clock clock;

  public AuditService(JdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  /**
   * Stores {@code event} unless its {@code auditId} was stored before.
   *
   * @return true when stored, false for a duplicate
   * @throws IllegalArgumentException when a field the contract requires is missing
   */
  public boolean record(AuditEvent event) {
    validate(event);
    boolean stored = Boolean.TRUE.equals(tx.execute(s -> append(event)));
    if (stored) {
      log.atInfo()
          .addKeyValue("event", "audit.event.recorded")
          .addKeyValue("auditId", event.auditId().toString())
          .addKeyValue("txnId", event.txnId())
          .addKeyValue("correlationId", event.correlationId())
          .addKeyValue("action", event.action())
          .log("audit event recorded");
    } else {
      log.atInfo()
          .addKeyValue("event", "audit.event.duplicate")
          .addKeyValue("auditId", event.auditId().toString())
          .log("audit event already stored; ignored");
    }
    return stored;
  }

  /** @return false when the auditId was stored before (a concurrent copy waits on the key) */
  private boolean append(AuditEvent e) {
    Instant recordedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String month = YearMonth.from(recordedAt.atOffset(ZoneOffset.UTC)).toString();

    if (jdbc.update(
            "insert into event_key (audit_id, recorded_at) values (?, ?) on conflict do nothing",
            e.auditId(), utc(recordedAt))
        == 0) {
      return false;
    }

    int created =
        jdbc.update(
            "insert into chain_head (month, seq, hash, updated_at) values (?, 0, ?, ?) on conflict do nothing",
            month, HashChain.GENESIS, utc(recordedAt));
    if (created == 1) {
      jdbc.query("select ensure_month_partition(?)", rs -> {}, month);
    }
    ChainHead head =
        jdbc.queryForObject(
            "select month, seq, hash from chain_head where month = ? for update",
            (rs, i) -> new ChainHead(rs.getString(1), rs.getLong(2), rs.getBytes(3)),
            month);

    // PostgreSQL's jsonb text form is what a verifier reads back, so hash exactly that.
    Map<String, Object> normalised =
        jdbc.queryForMap(
            "select ?::jsonb::text before, ?::jsonb::text after",
            jsonText(e.before()), jsonText(e.after()));

    AuditRow row =
        HashChain.seal(
            new AuditRow(
                month,
                head.seq() + 1,
                e.auditId(),
                recordedAt,
                e.at().truncatedTo(ChronoUnit.MICROS),
                e.txnId() == null ? null : UUID.fromString(e.txnId()),
                keptIf(RFC_UUID, e.correlationId()),
                e.actor().type(),
                MaskingPolicy.scrub(e.actor().id()),
                e.action(),
                keptIf(SYSTEM_RESOURCE, e.resource()),
                e.outcome(),
                e.service(),
                e.sourceIp(),
                MaskingPolicy.scrub(e.payloadRef()),
                (String) normalised.get("before"),
                (String) normalised.get("after"),
                null,
                null),
            head.hash());

    jdbc.update(
        """
        insert into event (month, seq, audit_id, recorded_at, at, txn_id, correlation_id,
                           actor_type, actor_id, action, resource, outcome, service, source_ip,
                           payload_ref, before, after, prev_hash, hash)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
        """,
        row.month(), row.seq(), row.auditId(), utc(row.recordedAt()), utc(row.at()), row.txnId(),
        row.correlationId(), row.actorType(), row.actorId(), row.action(), row.resource(),
        row.outcome(), row.service(), row.sourceIp(), row.payloadRef(), row.before(), row.after(),
        row.prevHash(), row.hash());
    jdbc.update(
        "update chain_head set seq = ?, hash = ?, updated_at = ? where month = ?",
        row.seq(), row.hash(), utc(recordedAt), month);
    return true;
  }

  /**
   * {@code value} as it is when it has exactly the {@code systemId} shape and the scrub leaves it
   * unchanged (a crafted id of that shape can still spread an MSISDN over its groups), otherwise
   * scrubbed.
   */
  private static String keptIf(Pattern systemId, String value) {
    if (value == null) {
      return null;
    }
    String scrubbed = MaskingPolicy.scrub(value);
    return systemId.matcher(value).matches() && scrubbed.equals(value) ? value : scrubbed;
  }

  private String jsonText(JsonNode node) {
    return node == null || node.isNull() ? null : json.writeValueAsString(masked(node));
  }

  /**
   * A copy of {@code node} with every string value, number and property name scrubbed (18.3
   * safety net). A number that the scrub changes becomes the masked string. Property names that
   * scrub to a name already used are never overwritten: they get {@code #2}, {@code #3}... in
   * their original order.
   */
  private static JsonNode masked(JsonNode node) {
    if (node.isString() || node.isNumber()) {
      String text = node.asString();
      String scrubbed = MaskingPolicy.scrub(text);
      return text.equals(scrubbed)
          ? node
          : JsonMapper.shared().getNodeFactory().stringNode(scrubbed);
    }
    if (node instanceof ObjectNode obj) {
      ObjectNode out = obj.objectNode();
      obj.properties()
          .forEach(p -> out.set(unusedName(out, MaskingPolicy.scrub(p.getKey())), masked(p.getValue())));
      return out;
    }
    if (node instanceof ArrayNode arr) {
      ArrayNode out = arr.arrayNode();
      arr.forEach(v -> out.add(masked(v)));
      return out;
    }
    return node;
  }

  private static String unusedName(ObjectNode out, String name) {
    String candidate = name;
    for (int n = 2; out.has(candidate); n++) {
      candidate = name + "#" + n;
    }
    return candidate;
  }

  private static OffsetDateTime utc(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static void validate(AuditEvent e) {
    if (e.auditId() == null) {
      throw new IllegalArgumentException("audit event lacks auditId");
    }
    require(e.correlationId(), "correlationId");
    if (e.actor() == null) {
      throw new IllegalArgumentException("audit event lacks actor");
    }
    require(e.actor().type(), "actor.type");
    require(e.actor().id(), "actor.id");
    require(e.action(), "action");
    require(e.resource(), "resource");
    require(e.outcome(), "outcome");
    require(e.service(), "service");
    if (e.at() == null) {
      throw new IllegalArgumentException("audit event lacks at");
    }
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("audit event lacks " + name);
    }
  }
}
