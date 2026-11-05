package mfs.mw.admin.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.admin.api.config.ChangeRequestProperties;
import mfs.mw.admin.api.config.ContentProperties;
import mfs.mw.admin.api.dto.AuditEvent;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.ContentVersionView;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.Menu;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.TemplateChangeBody;
import mfs.mw.admin.api.dto.UssdMenuChangeBody;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.entity.ContentVersion;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.ChangeRequestMapper;
import mfs.mw.admin.api.mapper.ContentChangeMapper;
import mfs.mw.admin.api.mapper.MenuParser;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.ChangeRequestRepository;
import mfs.mw.admin.api.repository.ContentVersionRepository;
import mfs.mw.admin.api.security.AdminRole;
import mfs.mw.admin.api.security.OperatorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * USSD menus and notify templates through maker-checker (ticket 39, spec 9.2, 10.3, 15.2, 15.3).
 *
 * <ul>
 *   <li>A maker ({@link AdminRole#CONTENT_MAKER}) proposes a new version: the tree or template is
 *       checked first ({@link MenuValidator}, {@link TemplateValidator}: 400 MW-VAL-040 naming each
 *       problem), then stored PENDING in {@code admin.content_version} with its change request (one
 *       open request per menu / template) and audited, in one transaction.
 *   <li>A checker ({@link AdminRole#CONTENT_CHECKER}, not the maker) approves it through {@link
 *       ChangeRequestService}, which calls {@link #publish} inside its transaction: the version is
 *       PUBLISHED and its full value goes on {@code mw.config.changed.v1} (key configKey, compacted)
 *       through the outbox. Go-live times (menu per MNO, template {@code validFrom}) earlier than
 *       the approval become the approval time. mw-ussd and mw-notify take it from there.
 * </ul>
 */
@Service
public class ContentChangeService {

  private static final Logger log = LoggerFactory.getLogger(ContentChangeService.class);

  public static final String CONFIG_TOPIC = "mw.config.changed.v1";
  static final String ACTION = "PUBLISH";

  private static final Pattern MENU_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
  private static final Set<String> CHANNELS = Set.of("SMS", "EMAIL");
  private static final Set<String> LOCALES = Set.of("bn", "en");

  private final ChangeRequestRepository requests;
  private final ContentVersionRepository versions;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final ChangeRequestProperties props;
  private final ContentProperties content;
  private final JsonMapper json;
  private final Clock clock;

  public ContentChangeService(
      ChangeRequestRepository requests,
      ContentVersionRepository versions,
      OutboxWriter outbox,
      TransactionTemplate tx,
      ChangeRequestProperties props,
      ContentProperties content,
      JsonMapper json,
      Clock clock) {
    this.requests = requests;
    this.versions = versions;
    this.outbox = outbox;
    this.tx = tx;
    this.props = props;
    this.content = content;
    this.json = json;
    this.clock = clock;
  }

  /** Whether change requests of {@code resourceType} are this service's. */
  public static boolean handles(String resourceType) {
    return ContentChangeMapper.USSD_MENU.equals(resourceType) || ContentChangeMapper.NOTIFY_TEMPLATE.equals(resourceType);
  }

  // --- maker ----------------------------------------------------------------------------------

  public ChangeRequestView proposeMenu(Operator maker, String menuId, UssdMenuChangeBody body) {
    requireMaker(maker);
    if (!MENU_ID.matcher(menuId).matches()) {
      throw invalid(List.of(new ErrorDetail("menuId", "must be 1-32 of a-z, 0-9, _ or -, starting with a letter or digit")));
    }
    List<ErrorDetail> problems = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<Map<String, Object>> activations = new ArrayList<>();
    for (UssdMenuChangeBody.Activation a : body.activations()) {
      if (!content.mnos().contains(a.mno())) {
        problems.add(new ErrorDetail("activations", "unknown MNO, one of " + content.mnos()));
      } else if (!seen.add(a.mno())) {
        problems.add(new ErrorDetail("activations", "MNO " + a.mno() + " listed twice"));
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("mno", a.mno());
      if (a.goLiveAt() != null) {
        m.put("goLiveAt", a.goLiveAt().toString());
      }
      activations.add(m);
    }
    Map<String, Object> tree = new LinkedHashMap<>(body.menu());
    tree.put("menuId", menuId);
    tree.put("version", 1);
    Menu menu;
    try {
      menu = MenuParser.parse(json, tree);
    } catch (JacksonException | IllegalArgumentException e) {
      throw invalid(List.of(new ErrorDetail("menu", "not a USSD menu tree")));
    }
    MenuValidator.problems(menu).forEach(p -> problems.add(new ErrorDetail("menu", p)));
    if (!problems.isEmpty()) {
      throw invalid(problems);
    }
    String key = ContentChangeMapper.menuKey(menuId);
    return propose(maker, ContentChangeMapper.USSD_MENU, menuId, key, ContentVersion.USSD_MENU, body.reason(),
        version -> {
          tree.put("version", version);
          Map<String, Object> value = new LinkedHashMap<>();
          value.put("menuId", menuId);
          value.put("version", version);
          value.put("menu", tree);
          value.put("activations", activations);
          return value;
        });
  }

  public ChangeRequestView proposeTemplate(
      Operator maker, String code, String channel, String locale, TemplateChangeBody body) {
    requireMaker(maker);
    List<ErrorDetail> problems = new ArrayList<>(templateKeyProblems(code, channel, locale));
    if (problems.isEmpty()) {
      TemplateValidator.problems(channel, body).forEach(p -> problems.add(new ErrorDetail("template", p)));
    }
    if (!problems.isEmpty()) {
      throw invalid(problems);
    }
    String resource = ContentChangeMapper.templateResource(code, channel, locale);
    String key = ContentChangeMapper.templateKey(code, channel, locale);
    return propose(maker, ContentChangeMapper.NOTIFY_TEMPLATE, resource, key, ContentVersion.TEMPLATE, body.reason(),
        version -> {
          Map<String, Object> value = new LinkedHashMap<>();
          value.put("code", code);
          value.put("channel", channel);
          value.put("locale", locale);
          value.put("version", version);
          value.put("body", body.body());
          putIfPresent(value, "subject", body.subject());
          putIfPresent(value, "htmlBody", body.htmlBody());
          putIfPresent(value, "senderId", body.senderId());
          if (body.validFrom() != null) {
            value.put("validFrom", body.validFrom().toString());
          }
          return value;
        });
  }

  private ChangeRequestView propose(
      Operator maker, String resourceType, String resourceId, String key, String configType, String reason,
      java.util.function.IntFunction<Map<String, Object>> valueFor) {
    Instant now = clock.instant();
    ChangeRequest cr =
        tx.execute(
            s -> {
              int version = versions.nextVersion(key);
              Integer published = versions.latestPublished(key).map(ContentVersion::version).orElse(null);
              Map<String, Object> value = valueFor.apply(version);
              ChangeRequest c =
                  new ChangeRequest(
                      UUID.randomUUID(), resourceType, resourceId, ACTION, value,
                      ContentChangeMapper.diff(key, published, version), maker.username(), maker.subject(),
                      ChangeRequest.Status.PENDING, null, null, reason, null, now, null, now.plus(props.ttl()));
              if (!requests.insert(c)) {
                throw new MwException(ErrorCodes.VAL_REQUEST_OPEN, "This resource already has an open change request")
                    .withStatus(HttpStatus.CONFLICT);
              }
              versions.insert(
                  new ContentVersion(key, version, configType, value, ContentVersion.Status.PENDING, c.id(),
                      maker.username(), null, now, null));
              audit(c, ChangeRequestMapper.created(c, correlationId()));
              return c;
            });
    log.atInfo()
        .addKeyValue("event", "admin.change_request.created")
        .addKeyValue("changeRequestId", cr.id().toString())
        .addKeyValue("configKey", key)
        .addKeyValue("makerId", cr.makerId())
        .log("content change request created");
    return ChangeRequestMapper.view(cr);
  }

  // --- checker (called by ChangeRequestService inside its transaction) --------------------------

  /**
   * Publishes the version of an approved request: PUBLISHED, and {@code mw.config.changed.v1}
   * through the outbox (the caller's transaction). Returns the topic it was sent on.
   */
  String publish(ChangeRequest cr, String checkerId, String correlationId, Instant now) {
    ContentVersion v =
        versions.byChangeRequest(cr.id()).orElseThrow(() -> new IllegalStateException("no content version for " + cr.id()));
    Map<String, Object> value = resolved(v, now);
    versions.decide(cr.id(), ContentVersion.Status.PUBLISHED, checkerId, value, now);
    ConfigChangedEvent e = ContentChangeMapper.published(v, value, checkerId, correlationId, now);
    outbox.append(CONFIG_TOPIC, v.configKey(), new EventMetadata(correlationId, cr.id().toString(), cr.id().toString() + ":" + v.version(), 1), e);
    log.atInfo()
        .addKeyValue("event", "admin.content.published")
        .addKeyValue("configKey", v.configKey())
        .addKeyValue("configVersion", v.version())
        .log("content version published");
    return CONFIG_TOPIC;
  }

  /** Marks the version of a rejected or expired request (the caller's transaction). */
  void decided(ChangeRequest cr, ChangeRequest.Status status, String checkerId, Instant now) {
    ContentVersion.Status to =
        status == ChangeRequest.Status.REJECTED ? ContentVersion.Status.REJECTED : ContentVersion.Status.EXPIRED;
    versions.byChangeRequest(cr.id()).ifPresent(v -> versions.decide(cr.id(), to, checkerId, v.value(), now));
  }

  // --- reads ----------------------------------------------------------------------------------

  public ContentVersionView.Page menuVersions(String menuId, int limit, int offset) {
    return page(ContentChangeMapper.menuKey(menuId), limit, offset);
  }

  public ContentVersionView menuVersion(String menuId, int version) {
    return one(ContentChangeMapper.menuKey(menuId), version);
  }

  public ContentVersionView.Page templateVersions(String code, String channel, String locale, int limit, int offset) {
    return page(ContentChangeMapper.templateKey(code, channel, locale), limit, offset);
  }

  public ContentVersionView templateVersion(String code, String channel, String locale, int version) {
    return one(ContentChangeMapper.templateKey(code, channel, locale), version);
  }

  private ContentVersionView.Page page(String key, int limit, int offset) {
    return new ContentVersionView.Page(
        versions.list(key, limit, offset).stream().map(v -> ContentChangeMapper.view(v, false)).toList(), limit, offset);
  }

  private ContentVersionView one(String key, int version) {
    return versions.find(key, version)
        .map(v -> ContentChangeMapper.view(v, true))
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such version").withStatus(HttpStatus.NOT_FOUND));
  }

  // --- helpers --------------------------------------------------------------------------------

  /** The value as broadcast: go-live times not before the approval. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> resolved(ContentVersion v, Instant now) {
    Map<String, Object> value = new LinkedHashMap<>(v.value());
    if (ContentVersion.USSD_MENU.equals(v.configType())) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Object o : (List<Object>) value.getOrDefault("activations", List.of())) {
        Map<String, Object> a = new LinkedHashMap<>((Map<String, Object>) o);
        a.put("goLiveAt", notBefore(a.get("goLiveAt"), now));
        out.add(a);
      }
      value.put("activations", out);
    } else {
      value.put("validFrom", notBefore(value.get("validFrom"), now));
    }
    return value;
  }

  private static String notBefore(Object at, Instant now) {
    Instant t = at == null ? now : Instant.parse(String.valueOf(at));
    return (t.isBefore(now) ? now : t).toString();
  }

  private static List<ErrorDetail> templateKeyProblems(String code, String channel, String locale) {
    List<ErrorDetail> p = new ArrayList<>();
    if (!CODE.matcher(code).matches()) {
      p.add(new ErrorDetail("code", "must be 2-64 of A-Z, 0-9 or _, starting with a letter"));
    }
    if (!CHANNELS.contains(channel)) {
      p.add(new ErrorDetail("channel", "must be SMS or EMAIL"));
    }
    if (!LOCALES.contains(locale)) {
      p.add(new ErrorDetail("locale", "must be bn or en"));
    }
    return p;
  }

  private static void putIfPresent(Map<String, Object> m, String k, String v) {
    if (v != null) {
      m.put(k, v);
    }
  }

  private static void requireMaker(Operator maker) {
    if (!maker.hasAny(AdminRole.CONTENT_MAKER)) {
      throw OperatorResolver.forbidden("Proposing a menu or template version needs the ops-maker role");
    }
  }

  /** A content audit: key the resource, header mw-txn-id the change request id (no transaction). */
  private void audit(ChangeRequest cr, AuditEvent e) {
    outbox.append(
        ChangeRequestService.AUDIT_TOPIC, e.resource(),
        new EventMetadata(e.correlationId(), cr.id().toString(), e.auditId(), 1), e);
  }

  private static MwException invalid(List<ErrorDetail> details) {
    return new MwException(ErrorCodes.VAL_CONTENT_INVALID, "The proposed version is not usable").withDetails(details);
  }

  private static String correlationId() {
    String c = MDC.get("correlationId");
    return c == null || c.isBlank() ? "admin-" + UUID.randomUUID() : c;
  }
}
