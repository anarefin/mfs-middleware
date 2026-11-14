package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * {@code GET /admin/v1/transactions/{txnId}} (contract {@code admin.v1}): the transaction, its saga
 * timeline and partner calls (mw-orchestrator), its audit trail and the status of the signed daily
 * audit anchors (mw-audit-dw, ticket 74; absent when mw-audit-dw did not say), and whether MSISDN
 * party ids are unmasked ({@code revealed}, audited).
 */
public record TxnDetailView(
    AdminTxn transaction,
    List<AdminTxn.TimelineEntry> timeline,
    List<AdminTxn.PartnerCall> partnerCalls,
    List<AuditTrail.Entry> auditTrail,
    @JsonInclude(JsonInclude.Include.NON_NULL) AuditTrail.Anchor auditAnchor,
    boolean revealed) {

  public TxnDetailView {
    timeline = List.copyOf(timeline);
    partnerCalls = List.copyOf(partnerCalls);
    auditTrail = List.copyOf(auditTrail);
  }
}
