package mfs.mw.simulators.service.mockcontrol;

import java.util.List;
import java.util.Optional;
import mfs.mw.simulators.dto.MockParty;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import org.springframework.http.HttpStatus;

/**
 * The mock parties of mw-simulators that run on the mock-control engine (ADR D33), their operations
 * and scope keys. A party's name is also the first segment of its wire paths ({@code /<party>/...}),
 * which the journal records. A new mock adds a line here and calls {@link MockControl#decide} per
 * operation.
 */
public final class MockParties {

  public static final List<MockParty> ALL =
      List.of(
          new MockParty("ocs", "prepaid top-up OCS", List.of("topup", "status", "reverse"), List.of("msisdn", "txnRef")),
          new MockParty(
              "billagg",
              "utility bill aggregator (REST and SOAP front ends)",
              List.of("billers", "fetch", "pay", "status", "reverse"),
              List.of("biller", "billNumber", "txnRef")),
          new MockParty("c2wbank", "C2W partner bank", List.of("debit", "status", "reverse"), List.of("account", "txnRef")),
          new MockParty("merchant", "checkout merchant IPN receiver", List.of("ipn"), List.of("ipnId", "path")));

  private MockParties() {}

  public static Optional<MockParty> find(String name) {
    return ALL.stream().filter(p -> p.name().equals(name)).findFirst();
  }

  public static boolean isParty(String name) {
    return find(name).isPresent();
  }

  /** The party, or 404 MW-VAL-003. */
  public static MockParty require(String name) {
    return find(name)
        .orElseThrow(
            () -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No mock party named " + name).withStatus(HttpStatus.NOT_FOUND));
  }
}
