package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import mfs.mw.recon.dto.ExternalLine;
import mfs.mw.recon.dto.Finding;
import mfs.mw.recon.dto.MatchRule;
import mfs.mw.recon.dto.ReconClass;
import mfs.mw.recon.dto.TxnProjection;
import org.junit.jupiter.api.Test;

/**
 * One case per FRS REC-04 class, the tolerance edge (exactly at, one poisha over) and the date
 * window (ticket 126). Expected classes are the literals from the requirement, not recomputed.
 */
class MatchingServiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
  private static final String PARTNER = "billagg";
  private static final MatchRule EXACT = new MatchRule(0, 1);
  private static final MatchingService MATCHING = new MatchingService();

  @Test
  void matchedWhenPartnerSuccessUpayCompletedAndTheLedgerPostedTheSameAmount() {
    Finding only = one(match(List.of(line("AGG-1", "REF-1", 50_000, "SUCCESS", DAY)),
        List.of(txn("0196a1b2-0000-7000-8000-000000000001", "AGG-1", "REF-1", 50_000, "COMPLETED", DAY)),
        Set.of("0196a1b2-0000-7000-8000-000000000001"), EXACT, true));

    assertThat(only.classification()).isEqualTo(ReconClass.MATCHED);
    assertThat(only.detail()).isNull();
    assertThat(only.txnId()).isEqualTo("0196a1b2-0000-7000-8000-000000000001");
    assertThat(only.fileAmountMinor()).isEqualTo(50_000L);
    assertThat(only.upayAmountMinor()).isEqualTo(50_000L);
    assertThat(only.ledgerAmountMinor()).isEqualTo(50_000L);
  }

  @Test
  void missingInPartnerIsACompletedTxnAbsentFromTheFileAfterCutOff() {
    List<Finding> findings = match(List.of(),
        List.of(txn("0196a1b2-0000-7000-8000-000000000002", "AGG-2", "REF-2", 20_000, "COMPLETED", DAY)),
        Set.of("0196a1b2-0000-7000-8000-000000000002"), EXACT, true);

    assertThat(findings).singleElement().satisfies(f -> {
      assertThat(f.classification()).isEqualTo(ReconClass.MISSING_IN_PARTNER);
      assertThat(f.owner()).isEqualTo("partner-ops");
      assertThat(f.txnId()).isEqualTo("0196a1b2-0000-7000-8000-000000000002");
      assertThat(f.upayAmountMinor()).isEqualTo(20_000L);
    });
  }

  @Test
  void anIntradayFileDoesNotCallACompletedTxnMissingInPartner() {
    assertThat(match(List.of(),
        List.of(txn("0196a1b2-0000-7000-8000-000000000002", "AGG-2", null, 20_000, "COMPLETED", DAY)),
        Set.of(), EXACT, false)).isEmpty();
  }

  @Test
  void missingInUpayIsAFileLineNoTransactionHas() {
    Finding only = one(match(List.of(line(null, "UNKNOWN-9", 10_000, "SUCCESS", DAY)), List.of(), Set.of(), EXACT, true));

    assertThat(only.classification()).isEqualTo(ReconClass.MISSING_IN_UPAY);
    assertThat(only.owner()).isEqualTo("ops");
    assertThat(only.txnId()).isNull();
    assertThat(only.fileAmountMinor()).isEqualTo(10_000L);
    assertThat(only.reference()).isEqualTo("UNKNOWN-9");
  }

  @Test
  void amountMismatchIsOnePoishaOverToleranceAndExactlyAtToleranceStillMatches() {
    TxnProjection txn = txn("0196a1b2-0000-7000-8000-000000000003", "AGG-3", null, 50_000, "COMPLETED", DAY);
    MatchRule tolerance = new MatchRule(100, 1);
    Set<String> posted = Set.of(txn.txnId());

    Finding at = one(match(List.of(line("AGG-3", null, 50_100, "SUCCESS", DAY)), List.of(txn), posted, tolerance, true));
    Finding over = one(match(List.of(line("AGG-3", null, 50_101, "SUCCESS", DAY)), List.of(txn), posted, tolerance, true));

    assertThat(at.classification()).isEqualTo(ReconClass.MATCHED);
    assertThat(over.classification()).isEqualTo(ReconClass.AMOUNT_MISMATCH);
    assertThat(over.fileAmountMinor()).isEqualTo(50_101L);
    assertThat(over.upayAmountMinor()).isEqualTo(50_000L);
  }

  @Test
  void statusMismatchWhenUpayReversedAndThePartnerSaysSuccess() {
    Finding only = one(match(
        List.of(line("AGG-4", null, 30_000, "SUCCESS", DAY)),
        List.of(txn("0196a1b2-0000-7000-8000-000000000004", "AGG-4", null, 30_000, "REVERSED", DAY)),
        Set.of("0196a1b2-0000-7000-8000-000000000004"), EXACT, true));

    assertThat(only.classification()).isEqualTo(ReconClass.STATUS_MISMATCH);
    assertThat(only.detail()).isNull();
    assertThat(only.txnId()).isEqualTo("0196a1b2-0000-7000-8000-000000000004");
  }

  @Test
  void aCompletedTxnWithNoPostingIsStatusMismatchLedgerMissing() {
    Finding only = one(match(
        List.of(line("AGG-5", null, 40_000, "SUCCESS", DAY)),
        List.of(txn("0196a1b2-0000-7000-8000-000000000005", "AGG-5", null, 40_000, "COMPLETED", DAY)),
        Set.of(), EXACT, true));

    assertThat(only.classification()).isEqualTo(ReconClass.STATUS_MISMATCH);
    assertThat(only.detail()).isEqualTo(MatchingService.LEDGER_MISSING);
    assertThat(only.ledgerAmountMinor()).isNull();
  }

  @Test
  void aLineOutsideTheDateWindowDoesNotTakeTheTransaction() {
    TxnProjection txn = txn("0196a1b2-0000-7000-8000-000000000006", "AGG-6", null, 15_000, "COMPLETED", DAY);
    List<Finding> findings = match(
        List.of(line("AGG-6", null, 15_000, "SUCCESS", DAY.plusDays(2))),
        List.of(txn), Set.of(txn.txnId()), EXACT, true);

    assertThat(findings).extracting(Finding::classification)
        .containsExactly(ReconClass.MISSING_IN_UPAY, ReconClass.MISSING_IN_PARTNER);
  }

  @Test
  void theReferenceMatchesWhenThePartnerRefDoesNot() {
    Finding only = one(match(
        List.of(line("OTHER", "REF-7", 12_000, "SUCCESS", DAY)),
        List.of(txn("0196a1b2-0000-7000-8000-000000000007", "AGG-7", "REF-7", 12_000, "COMPLETED", DAY)),
        Set.of("0196a1b2-0000-7000-8000-000000000007"), EXACT, true));

    assertThat(only.classification()).isEqualTo(ReconClass.MATCHED);
    assertThat(only.txnId()).isEqualTo("0196a1b2-0000-7000-8000-000000000007");
  }

  @Test
  void aStatementLineMatchesTheTransactionItsReferenceNames() {
    String txnId = "0196a1b2-0000-7000-8000-000000000008";
    Finding only = one(match(
        List.of(new ExternalLine(null, txnId, 25_000, null, DAY)),
        List.of(txn(txnId, null, null, 25_000, "COMPLETED", DAY)),
        Set.of(txnId), EXACT, true));

    assertThat(only.classification()).isEqualTo(ReconClass.MATCHED);
    assertThat(only.reference()).isEqualTo(txnId);
  }

  private static List<Finding> match(List<ExternalLine> lines, List<TxnProjection> txns, Set<String> posted,
      MatchRule rule, boolean afterCutOff) {
    return MATCHING.match(DAY, lines, txns, posted, rule, afterCutOff);
  }

  private static Finding one(List<Finding> findings) {
    assertThat(findings).hasSize(1);
    return findings.get(0);
  }

  private static ExternalLine line(String partnerRef, String reference, long amount, String status, LocalDate date) {
    return new ExternalLine(partnerRef, reference, amount, status, date);
  }

  private static TxnProjection txn(String txnId, String partnerRef, String reference, long amount, String state, LocalDate date) {
    return new TxnProjection(txnId, PARTNER, partnerRef, reference, amount, state, date);
  }
}
