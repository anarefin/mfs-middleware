package mfs.mw.app.gateway.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.HistoryItem;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.QueryTxn;
import org.junit.jupiter.api.Test;

/** An orchestrator item as the wallet sees it (ADR D25): the other side, no own side, no fee on IN. */
class HistoryMapperTest {

  static final Instant AT = Instant.parse("2026-11-16T06:00:00Z");
  static final Party ME = new Party("WALLET", "0171****001", null, "CUSTOMER");
  static final Party OTHER = new Party("WALLET", "0171****002", "R", "CUSTOMER");
  static final ErrorInfo ERROR = new ErrorInfo("MW-CORE-INSUFFICIENT_BALANCE", "Insufficient balance", null, false);

  private final HistoryMapper mapper = new HistoryMapper();

  static QueryTxn txn(String direction, Party debit, Party credit) {
    return new QueryTxn("019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "P2P", direction, "FAILED", AT, AT.plusSeconds(2),
        new Amount(5_000L, "BDT"), new Amount(500L, "BDT"), debit, credit, "CORE-1", ERROR);
  }

  @Test
  void whatTheWalletPaidShowsThePayeeTheFeeAndTheError() {
    HistoryItem item = mapper.toItem(txn("OUT", ME, OTHER));

    assertThat(item.direction()).isEqualTo("OUT");
    assertThat(item.counterparty()).isEqualTo(OTHER);
    assertThat(item.fee()).isEqualTo(new Amount(500L, "BDT"));
    assertThat(item.error()).isEqualTo(ERROR);
    assertThat(item.requestedAt()).isEqualTo(AT);
    assertThat(item.updatedAt()).isEqualTo(AT.plusSeconds(2));
    assertThat(item.coreRef()).isEqualTo("CORE-1");
    assertThat(item.amount()).isEqualTo(new Amount(5_000L, "BDT"));
  }

  @Test
  void whatReachedTheWalletShowsThePayerAndNeitherFeeNorError() {
    HistoryItem item = mapper.toItem(txn("IN", OTHER, ME));

    assertThat(item.direction()).isEqualTo("IN");
    assertThat(item.counterparty()).isEqualTo(OTHER);
    assertThat(item.fee()).isNull();
    assertThat(item.error()).isNull();
  }

  @Test
  void aBulkCreditShowsTheOrgAsTheOrchestratorMaskedIt() {
    Party org = new Party("ORG", "*******0001", null, null);
    assertThat(mapper.toItem(txn("IN", org, ME)).counterparty()).isEqualTo(org);
  }

  @Test
  void aTransactionWithoutTheOtherSideHasNoCounterparty() {
    assertThat(mapper.toItem(txn("OUT", ME, null)).counterparty()).isNull();
  }
}
