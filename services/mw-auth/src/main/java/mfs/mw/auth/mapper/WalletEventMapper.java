package mfs.mw.auth.mapper;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.auth.dto.CustomerFactorsAnswer;
import mfs.mw.auth.dto.WalletStateChanged;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code core.wallet.event.v1} records (ticket 67). The topic carries every core reply to
 * mw-orchestrator as well; only {@code WalletStateChanged} is projected, the rest is empty. A
 * WalletStateChanged without the fields the projection needs is malformed ({@code MW-VAL-002}: the
 * error handler parks it on the retry topics, then the DLQ). MSISDNs are normalised to the national form
 * {@code 01XXXXXXXXX} (the contract allows an {@code 880} / {@code +880} prefix), so the keyed hash
 * of one number is always the same.
 */
@Component
public class WalletEventMapper {

  private static final Pattern MSISDN = Pattern.compile("^(?:\\+?880)?(01[3-9]\\d{8})$");
  private static final Set<String> TYPES = Set.of("CUSTOMER", "AGENT", "MERCHANT", "ORG", "DISTRIBUTOR", "DSO");
  // ticket 94 (FRS 7.1): the core's DORMANT and CLOSED; only ACTIVE logs in (WalletAccess)
  private static final Set<String> STATUSES = Set.of("ACTIVE", "BLOCKED", "HOLD", "LOCKED", "DORMANT", "CLOSED");
  /** The core's ORGANISATION (ticket 94) is this service's ORG (no app role either way). */
  private static final String ORGANISATION = "ORGANISATION";

  private final JsonMapper json;

  public WalletEventMapper(JsonMapper json) {
    this.json = json;
  }

  /** The wallet state of a WalletStateChanged record; empty for any other core event. */
  public Optional<WalletStateChanged> read(String body) {
    JsonNode tree;
    try {
      tree = json.readTree(body);
    } catch (JacksonException e) {
      throw malformed("core event is not JSON");
    }
    if (!WalletStateChanged.TYPE.equals(tree.path("eventType").asString(""))) {
      return Optional.empty();
    }
    WalletStateChanged e;
    try {
      e = json.treeToValue(tree, WalletStateChanged.class);
    } catch (JacksonException ex) {
      throw malformed("WalletStateChanged unreadable");
    }
    WalletStateChanged.State s = e.payload();
    if (s != null && ORGANISATION.equals(s.walletType())) {
      s = new WalletStateChanged.State(s.walletId(), s.msisdn(), "ORG", s.status(), s.hasPin(), s.birthYear(), s.version(),
          s.change());
      e = new WalletStateChanged(e.eventType(), e.eventId(), e.txnId(), s);
    }
    if (e.eventId() == null || e.eventId().isBlank() || s == null || s.walletId() == null || s.walletId().isBlank()
        || s.version() == null || s.version() < 1 || s.hasPin() == null
        || !TYPES.contains(s.walletType()) || !STATUSES.contains(s.status())
        || s.msisdn() == null || !MSISDN.matcher(s.msisdn()).matches()) {
      throw malformed("WalletStateChanged lacks a required field");
    }
    return Optional.of(e);
  }

  /**
   * The core's answer to a PIN reset's VerifyCustomerFactors (ticket 70): {@code
   * CustomerFactorsVerified} / {@code CustomerFactorsRejected} with a {@code pin-reset:} commandId;
   * empty for any other core event. Without eventId or commandId it is malformed.
   */
  public Optional<CustomerFactorsAnswer> readFactorsAnswer(String body) {
    JsonNode tree;
    try {
      tree = json.readTree(body);
    } catch (JacksonException e) {
      throw malformed("core event is not JSON");
    }
    String type = tree.path("eventType").asString("");
    boolean verified = "CustomerFactorsVerified".equals(type);
    if (!verified && !"CustomerFactorsRejected".equals(type)) {
      return Optional.empty();
    }
    String eventId = tree.path("eventId").asString("");
    String commandId = tree.path("commandId").asString("");
    if (eventId.isBlank() || !commandId.startsWith("pin-reset:")) {
      throw malformed(type + " lacks a required field");
    }
    String reason = tree.path("reasonCode").asString("");
    return Optional.of(new CustomerFactorsAnswer(eventId, commandId, tree.path("payload").path("resetId").asString(""),
        verified, reason.isBlank() ? null : reason));
  }

  /** The national form {@code 01XXXXXXXXX} of a contract MSISDN. */
  public static String nationalMsisdn(String msisdn) {
    var m = MSISDN.matcher(msisdn);
    if (!m.matches()) {
      throw malformed("not an MSISDN");
    }
    return m.group(1);
  }

  private static MwException malformed(String message) {
    return new MwException(ErrorCodes.VAL_MALFORMED, message);
  }
}
