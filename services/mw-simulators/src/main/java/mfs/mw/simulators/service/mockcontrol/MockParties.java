package mfs.mw.simulators.service.mockcontrol;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.simulators.dto.BankProfile;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockParty;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.SeedFiles;
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
  private static final List<MockBehaviour> FILE_BEHAVIOURS =
      List.of(MockBehaviour.MISSING_FILE, MockBehaviour.LATE_FILE, MockBehaviour.DUPLICATE_FILE,
          MockBehaviour.CORRUPT_FILE, MockBehaviour.INJECTED_MISMATCH);

  public static final List<MockParty> ALL = withBanks(
      List.of(
          new MockParty("ocs", "prepaid top-up OCS (legacy single profile)", List.of("topup", "status", "reverse"), List.of("msisdn", "txnRef")),
          new MockParty("ocs-gp", "Grameenphone OCS", List.of("topup", "inquiry", "postpaid", "status", "reverse"), List.of("msisdn", "txnRef")),
          new MockParty("ocs-robi", "Robi OCS", List.of("topup", "inquiry", "postpaid", "status", "reverse"), List.of("msisdn", "txnRef")),
          new MockParty("ocs-teletalk", "Teletalk OCS", List.of("topup", "inquiry", "postpaid", "status", "reverse"), List.of("msisdn", "txnRef")),
          new MockParty("ocs-bl", "Banglalink OCS", List.of("topup", "inquiry", "postpaid", "status", "reverse"), List.of("msisdn", "txnRef")),
          new MockParty(
              "billagg",
              "utility bill aggregator (REST and SOAP front ends)",
              List.of("billers", "fetch", "pay", "status", "reverse"),
              List.of("biller", "billNumber", "txnRef")),
          new MockParty("c2wbank", "C2W partner bank", List.of("debit", "status", "reverse"), List.of("account", "txnRef")),
          // the bill aggregator mocks of ticket 114 (BillAggregatorMockController, seeded stateful bills)
          new MockParty("sslcommerz", "SSLCommerz bill aggregator (REST JSON, api key + HMAC)",
              List.of("fetch", "pay", "status", "reverse"), List.of("biller", "billNumber", "txnRef")),
          new MockParty("ekpay", "eKpay bill aggregator (REST JSON, OAuth2 client credentials)",
              List.of("fetch", "pay", "status", "reverse"), List.of("biller", "billNumber", "txnRef")),
          new MockParty("agg3", "aggregator 3 (SOAP 1.1, WS-Security)",
              List.of("fetch", "pay", "status", "reverse"), List.of("biller", "billNumber", "txnRef")),
          new MockParty("agg4", "aggregator 4 (REST JSON envelope, Basic)",
              List.of("fetch", "pay", "status", "reverse"), List.of("biller", "billNumber", "txnRef")),
          new MockParty("merchant", "checkout merchant IPN receiver", List.of("ipn"), List.of("ipnId", "path")),
          new MockParty("smsc", "SMSC, the SMPP 3.4 server of mw-notify (the DLR is its callback)",
              List.of("bind", "submit"), List.of("systemId", "msisdn"), SMSC_BEHAVIOURS),
          new MockParty("ussdgw", "USSD gateway: SMPP server mw-ussd binds to; dial plays a session (SMPP or HTTP)",
              List.of("bind", "submit", "enquire_link", "dial"), List.of("systemId", "msisdn", "mno"), WIRE_BEHAVIOURS),
          new MockParty("ussd-gp", "Grameenphone USSD SMPP gateway (port 2776)",
              List.of("bind", "submit", "enquire_link"), List.of("systemId", "msisdn"), WIRE_BEHAVIOURS),
          new MockParty("ussd-robi", "Robi USSD SMPP gateway (port 2777)",
              List.of("bind", "submit", "enquire_link"), List.of("systemId", "msisdn"), WIRE_BEHAVIOURS),
          new MockParty("ussd-bl", "Banglalink USSD SMPP gateway (port 2778)",
              List.of("bind", "submit", "enquire_link"), List.of("systemId", "msisdn"), WIRE_BEHAVIOURS),
          new MockParty("ussd-teletalk", "Teletalk USSD SMPP gateway (port 2779)",
              List.of("bind", "submit", "enquire_link"), List.of("systemId", "msisdn"), WIRE_BEHAVIOURS),
          new MockParty("payshield", "payShield 10K HSM, TCP host commands of mw-crypto",
              List.of("command"), List.of("command"), WIRE_BEHAVIOURS),
          new MockParty("push", "app-server push API (FCM / APNs style) of mw-notify; keeps every push",
              List.of("send"), List.of("token", "topic"), PUSH_BEHAVIOURS),
          // the file mocks of ticket 123 (FRS EXT-05, EXT-06, EXT-17, MCT-05) on the partner-sftp host
          new MockParty("aml", "AML vendor: answers each transaction file in aml/in with a response file in aml/out",
              List.of("respond"), List.of("file", "date"), FILE_BEHAVIOURS),
          new MockParty("trustbank", "trust bank: daily MT940 and intraday MT942 of the trust account in trustbank/out",
              List.of("mt940", "mt942"), List.of("date", "account"), FILE_BEHAVIOURS),
          new MockParty("dw", "Data Warehouse drop: validates each batch and control file in dw/in (verdict in the journal)",
              List.of("validate"), List.of("file", "date"), List.of(MockBehaviour.SUCCESS, MockBehaviour.BUSINESS_ERROR))));

  /** The simulated bank gateway (ticket 117d): one party {@code bank-<code>} per bank of the seed file. */
  private static List<MockParty> withBanks(List<MockParty> parties) {
    List<MockParty> all = new java.util.ArrayList<>(parties);
    for (BankProfile b : SeedFiles.bankProfiles(SeedFiles.VERSION)) {
      all.add(new MockParty(b.party(), b.name() + " (bank gateway, " + b.protocol() + ", " + b.auth() + ")",
          List.of("verify", "transfer", "status", "reverse", "health"), List.of("account", "txnRef")));
    }
    return List.copyOf(all);
  }

  /** The kinds of INJECTED_MISMATCH (its {@code code}) per file party. */
  private static final Map<String, Set<String>> MISMATCH_KINDS = Map.of(
      "aml", mfs.mw.simulators.service.files.AmlResponder.MISMATCH_KINDS,
      "trustbank", mfs.mw.simulators.service.files.TrustStatementGenerator.MISMATCH_KINDS);

  /** The file mocks (ticket 123): run by their scheduler jobs or {@code POST /mock-control/{party}/run}. */
  private static final Set<String> FILE_PARTIES = Set.of("aml", "trustbank", "dw");

  /** Parties whose callback goes back on the caller's own connection (the SMSC's DLR): always on, no URL. */
  private static final Set<String> IN_BAND_CALLBACKS = Set.of("smsc");

  private static final Pattern SMPP_STATUS = Pattern.compile("0[xX][0-9A-Fa-f]{1,8}|[0-9]{1,10}");

  /** The form of a BUSINESS_ERROR {@code code} for the parties that have their own. */
  private static final Map<String, Pattern> CODES = Map.of(
      "smsc", SMPP_STATUS,
      "ussdgw", SMPP_STATUS,
      "ussd-gp", SMPP_STATUS,
      "ussd-robi", SMPP_STATUS,
      "ussd-bl", SMPP_STATUS,
      "ussd-teletalk", SMPP_STATUS,
      "payshield", Pattern.compile("[0-9A-Z]{2}"));

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
    if (s.behaviour() == MockBehaviour.INJECTED_MISMATCH) {
      Set<String> kinds = MISMATCH_KINDS.getOrDefault(party, Set.of());
      if (s.code() == null || !kinds.contains(s.code())) {
        throw new MwException(ErrorCodes.VAL_INVALID, "INJECTED_MISMATCH on " + party + " takes a code of "
            + kinds.stream().sorted().toList());
      }
    }
  }

  /** Whether the party is a file mock (ticket 123). */
  public static boolean isFileParty(String name) {
    return FILE_PARTIES.contains(name);
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
