package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import mfs.mw.audit.dw.entity.AuditRow;
import mfs.mw.audit.dw.entity.ChainHead;
import org.junit.jupiter.api.Test;

class HashChainTest {

  private static final HexFormat HEX = HexFormat.of();

  private static AuditRow unhashed(long seq, String before, String after) {
    return new AuditRow(
        "2026-11",
        seq,
        UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"),
        Instant.parse("2026-11-03T08:15:31.123456Z"),
        Instant.parse("2026-11-03T08:15:31Z"),
        UUID.fromString("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b"),
        "corr-0001",
        "system",
        "mw-orchestrator",
        "txn.state.changed",
        "txn/01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b",
        "SUCCESS",
        "mw-orchestrator",
        null,
        null,
        before,
        after,
        null,
        null);
  }

  /** A chain of n rows as the writer builds it. */
  private static List<AuditRow> chain(int n) {
    List<AuditRow> rows = new ArrayList<>();
    byte[] prev = HashChain.GENESIS;
    for (int i = 1; i <= n; i++) {
      AuditRow row = HashChain.seal(unhashed(i, "{\"status\": \"S" + (i - 1) + "\"}", "{\"status\": \"S" + i + "\"}"), prev);
      rows.add(row);
      prev = row.hash();
    }
    return rows;
  }

  private static ChainHead headOf(List<AuditRow> rows) {
    AuditRow last = rows.get(rows.size() - 1);
    return new ChainHead(last.month(), last.seq(), last.hash());
  }

  @Test
  void hashIsSha256OverTheDocumentedCanonicalForm() {
    // Golden vector: SHA-256 of the JSON array
    // [month, seq, auditId, recordedAt, at, txnId, correlationId, actorType, actorId, action,
    //  resource, outcome, service, sourceIp, payloadRef, before, after, prevHashHex]
    // computed outside Java (shasum -a 256). Pins the format for any later verifier.
    AuditRow row =
        HashChain.seal(unhashed(1, "{\"status\": \"POSTING\"}", "{\"status\": \"COMPLETED\"}"), HashChain.GENESIS);

    assertThat(HEX.formatHex(row.hash()))
        .isEqualTo("5e85d1909ef4cc8c851508c3e020b5d1dab92537c1d5b37c26417e1d79150461");
    assertThat(row.prevHash()).isEqualTo(HashChain.GENESIS);
  }

  @Test
  void eachHashDependsOnThePreviousOne() {
    AuditRow first = HashChain.seal(unhashed(1, null, null), HashChain.GENESIS);
    AuditRow otherPrev = HashChain.seal(unhashed(1, null, null), first.hash());
    assertThat(otherPrev.hash()).isNotEqualTo(first.hash());
  }

  @Test
  void anIntactChainVerifies() {
    List<AuditRow> rows = chain(5);
    assertThat(HashChain.verify(rows, headOf(rows)).intact()).isTrue();
  }

  @Test
  void anEmptyChainWithoutHeadVerifies() {
    assertThat(HashChain.verify(List.of(), null).intact()).isTrue();
  }

  @Test
  void aChangedFieldFailsAtThatRow() {
    for (int k = 1; k <= 5; k++) {
      List<AuditRow> rows = new ArrayList<>(chain(5));
      ChainHead head = headOf(rows);
      AuditRow r = rows.get(k - 1);
      rows.set(k - 1, r.withAfter("{\"status\": \"FORGED\"}"));

      ChainVerification v = HashChain.verify(rows, head);
      assertThat(v.intact()).as("row %d", k).isFalse();
      assertThat(v.failedSeq()).as("row %d", k).isEqualTo(k);
    }
  }

  @Test
  void aDeletedRowFailsAtThatRow() {
    for (int k = 1; k <= 5; k++) {
      List<AuditRow> rows = new ArrayList<>(chain(5));
      ChainHead head = headOf(rows);
      rows.remove(k - 1);

      ChainVerification v = HashChain.verify(rows, head);
      assertThat(v.intact()).as("row %d", k).isFalse();
      assertThat(v.failedSeq()).as("row %d", k).isEqualTo(k);
    }
  }

  @Test
  void aRowRehashedAfterAnEditBreaksTheLinkOfTheNextRow() {
    List<AuditRow> rows = new ArrayList<>(chain(3));
    ChainHead head = headOf(rows);
    AuditRow forged = HashChain.seal(rows.get(1).withAfter("{\"status\": \"FORGED\"}"), rows.get(0).hash());
    rows.set(1, forged);

    ChainVerification v = HashChain.verify(rows, head);
    assertThat(v.intact()).isFalse();
    assertThat(v.failedSeq()).isEqualTo(3);
  }

  @Test
  void rowsWithoutAHeadAreReportedAsTampered() {
    List<AuditRow> rows = chain(2);
    ChainVerification v = HashChain.verify(rows, null);
    assertThat(v.intact()).isFalse();
    assertThat(v.failedSeq()).isEqualTo(1);
  }
}
