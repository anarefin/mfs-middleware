package mfs.mw.simulators.service.mockcontrol;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockParty;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import org.springframework.http.HttpStatus;

/**
 * The mock parties of mw-simulators that run on the mock-control engine (ADR D33), their operations,
 * scope keys and behaviours. A party's name is also the first segment of its wire paths ({@code
 * /<party>/...}), which the journal records. A new mock adds a line here and calls {@link
 * MockControl#decide} per operation. The SMPP and TCP parties ({@code smsc}, {@code ussdgw}, {@code
 * payshield}; ticket 102b, FRS MCT-04) carry out a subset of the behaviours and take their refusal
 * {@code code} in their own form (an SMPP command_status, a payShield error code).
 */
public final class MockParties {

  private static final List<MockBehaviour> SMSC_BEHAVIOURS =
      List.of(MockBehaviour.SUCCESS, MockBehaviour.BUSINESS_ERROR, MockBehaviour.TIMEOUT, MockBehaviour.DELAY,
          MockBehaviour.HTTP_5XX, MockBehaviour.CONNECTION_RESET, MockBehaviour.DUPLICATE_CALLBACK,
          MockBehaviour.LATE_CALLBACK, MockBehaviour.OUT_OF_ORDER_CALLBACK, MockBehaviour.NO_CALLBACK);

  private static final List<MockBehaviour> WIRE_BEHAVIOURS =
      List.of(MockBehaviour.SUCCESS, MockBehaviour.BUSINESS_ERROR, MockBehaviour.TIMEOUT, MockBehaviour.DELAY,
          MockBehaviour.MALFORMED_RESPONSE, MockBehaviour.CONNECTION_RESET);

  /** The push server (ticket 110): no callbacks. */
  private static final List<MockBehaviour> PUSH_BEHAVIOURS =
      List.of(MockBehaviour.SUCCESS, MockBehaviour.BUSINESS_ERROR, MockBehaviour.TIMEOUT, MockBehaviour.DELAY,
          MockBehaviour.HTTP_5XX, MockBehaviour.MALFORMED_RESPONSE, MockBehaviour.CONNECTION_RESET,
          MockBehaviour.PARTIAL_SUCCESS);

  public static final List<MockParty> ALL =
      List.of(
          new MockParty("ocs", "prepaid top-up OCS", List.of("topup", "status", "reverse"), List.of("msisdn", "txnRef")),
          new MockParty(
              "billagg",
              "utility bill aggregator (REST and SOAP front ends)",
              List.of("billers", "fetch", "pay", "status", "reverse"),
              List.of("biller", "billNumber", "txnRef")),
          new MockParty("c2wbank", "C2W partner bank", List.of("debit", "status", "reverse"), List.of("account", "txnRef")),
          new MockParty("merchant", "checkout merchant IPN receiver", List.of("ipn"), List.of("ipnId", "path")),
          new MockParty("smsc", "SMSC, the SMPP 3.4 server of mw-notify (the DLR is its callback)",
              List.of("bind", "submit"), List.of("systemId", "msisdn"), SMSC_BEHAVIOURS),
          new MockParty("ussdgw", "USSD gateway: SMPP server mw-ussd binds to; dial plays a session (SMPP or HTTP)",
              List.of("bind", "submit", "enquire_link", "dial"), List.of("systemId", "msisdn", "mno"), WIRE_BEHAVIOURS),
          new MockParty("payshield", "payShield 10K HSM, TCP host commands of mw-crypto",
              List.of("command"), List.of("command"), WIRE_BEHAVIOURS),
          new MockParty("push", "app-server push API (FCM / APNs style) of mw-notify; keeps every push",
              List.of("send"), List.of("token", "topic"), PUSH_BEHAVIOURS));

  /** Parties whose callback goes back on the caller's own connection (the SMSC's DLR): always on, no URL. */
  private static final Set<String> IN_BAND_CALLBACKS = Set.of("smsc");

  private static final Pattern SMPP_STATUS = Pattern.compile("0[xX][0-9A-Fa-f]{1,8}|[0-9]{1,10}");

  /** The form of a BUSINESS_ERROR {@code code} for the parties that have their own. */
  private static final Map<String, Pattern> CODES =
      Map.of("smsc", SMPP_STATUS, "ussdgw", SMPP_STATUS, "payshield", Pattern.compile("[0-9A-Z]{2}"));

  private MockParties() {}

  public static Optional<MockParty> find(String name) {
    return ALL.stream().filter(p -> p.name().equals(name)).findFirst();
  }

  public static boolean isParty(String name) {
    return find(name).isPresent();
  }

  /** Whether the party's callbacks go on the caller's connection (no {@code callbackUrl} needed). */
  public static boolean callsBackInBand(String name) {
    return IN_BAND_CALLBACKS.contains(name);
  }

  /** A scenario the party can carry out (operation, behaviour, code form), or 400 MW-VAL-001. */
  public static void check(String party, MockScenario s) {
    MockParty p = require(party);
    if (s.operation() != null && !p.operations().contains(s.operation())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Party " + party + " has no operation " + s.operation()
          + "; it has " + p.operations());
    }
    if (!p.behaviours().contains(s.behaviour())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Party " + party + " cannot do " + s.behaviour()
          + "; it does " + p.behaviours());
    }
    Pattern code = CODES.get(party);
    if (code != null && s.code() != null && !code.matcher(s.code()).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Party " + party + " takes a code matching " + code.pattern());
    }
  }

  /** An SMPP command_status written as {@code 0x58} or {@code 88}. */
  public static int smppStatus(String code) {
    return code.startsWith("0x") || code.startsWith("0X")
        ? Integer.parseUnsignedInt(code.substring(2), 16)
        : Integer.parseInt(code);
  }

  /** The party, or 404 MW-VAL-003. */
  public static MockParty require(String name) {
    return find(name)
        .orElseThrow(
            () -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No mock party named " + name).withStatus(HttpStatus.NOT_FOUND));
  }
}
