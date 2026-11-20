package mfs.mw.admin.api.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.ChangeRequestBody.TxnActionType;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.ReconExceptionChangeBody;
import mfs.mw.admin.api.exception.ForbiddenException;
import org.springframework.stereotype.Service;

/**
 * The maker side of an action on a reconciliation exception (ticket 127b, UC-22): a {@code recon-maker} opens a
 * PENDING {@code recon_exception} change request; nothing is sent until a {@code recon-checker} approves ({@link
 * ReconExceptionChangeHandler}).
 */
@Service
public class ReconExceptionActionService {

  private final ChangeRequestService changeRequests;
  private final ReconExceptionChangeHandler handler;

  public ReconExceptionActionService(ChangeRequestService changeRequests, ReconExceptionChangeHandler handler) {
    this.changeRequests = changeRequests;
    this.handler = handler;
  }

  public ChangeRequestView open(Operator maker, UUID exceptionId, ReconExceptionChangeBody body) {
    if (!maker.hasAny(AdminRole.RECON_EXCEPTION_MAKER)) {
      throw new ForbiddenException("Asking for an action on a reconciliation exception needs the recon-maker role");
    }
    TxnActionType action = TxnActionType.valueOf(body.action().name());
    ReconExceptionChangeHandler.Checked checked = handler.check(exceptionId, action);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("txnId", checked.txn().txnId());
    payload.put("reconClass", checked.exception().reconClass());
    if (action == TxnActionType.REVERSE) {
      payload.put("amount", Map.of("valueMinor", checked.txn().amount().valueMinor(),
          "currency", checked.txn().amount().currency()));
    }
    Map<String, Object> diff = Map.of(
        "before", Map.of("exceptionState", checked.exception().state(), "txnStatus", checked.txn().status()),
        "after", Map.of("exceptionState", "ACTIONED", "txnStatus", checked.target()));
    return changeRequests.open(maker, ReconExceptionChangeHandler.RESOURCE, exceptionId.toString(), action.name(),
        payload, diff, body.reason());
  }
}
