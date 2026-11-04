package mfs.mw.orchestrator.service.saga;

import org.springframework.stereotype.Component;

/**
 * One child of an org bulk disbursement (spec 5.2 BULK_DISBURSE, 9.4, WP22, ticket 34): the org's
 * wallet pays one customer wallet. Validate, then PostTransfer, like P2P; no hold and no partner
 * leg. The org authorised the whole batch by maker-checker in the portal, so there is no PIN;
 * only the channels in {@code mw.orchestrator.bulk-channels} may send it, and the core restricts
 * the type to ORG -> CUSTOMER.
 */
@Component
public class BulkDisburseFlow implements Flow {

  @Override
  public String txnType() {
    return "BULK_DISBURSE";
  }

  @Override
  public boolean needsHold() {
    return false;
  }
}
