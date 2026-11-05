package mfs.mw.admin.api.dto;

import java.util.List;

/**
 * {@code GET /admin/v1/transactions/{txnId}} (contract {@code admin.v1}): the transaction, its saga
 * timeline and partner calls (mw-orchestrator), its audit trail (mw-audit-dw), and whether MSISDN
 * party ids are unmasked ({@code revealed}, audited).
 */
public record TxnDetailView(
    AdminTxn transaction,
    List<AdminTxn.TimelineEntry> timeline,
    List<AdminTxn.PartnerCall> partnerCalls,
    List<AuditTrail.Entry> auditTrail,
    boolean revealed) {

  public TxnDetailView {
    timeline = List.copyOf(timeline);
    partnerCalls = List.copyOf(partnerCalls);
    auditTrail = List.copyOf(auditTrail);
  }
}
