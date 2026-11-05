package mfs.mw.simulators.mapper;

import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.OcsResponse;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.service.TopupRecord;
import org.springframework.stereotype.Component;

@Component
public class OcsMapper {

  /** Only business failure and unknown code fail the top-up; every other mode applies it. */
  public TopupRecord toRecord(OcsTopupRequest req, SimMode mode, String ocsRef) {
    return switch (mode) {
      case BUSINESS_FAILURE ->
          new TopupRecord(req.transactionId(), req.msisdn(), req.amount(), "FAILED", "2001",
              "Subscriber not eligible for recharge", ocsRef, mode);
      case UNKNOWN_CODE ->
          new TopupRecord(req.transactionId(), req.msisdn(), req.amount(), "FAILED", "9876",
              "Unmapped OCS error", ocsRef, mode);
      default ->
          new TopupRecord(req.transactionId(), req.msisdn(), req.amount(), "SUCCESS", "0000",
              "Recharge successful", ocsRef, mode);
    };
  }

  public OcsResponse toResponse(TopupRecord r) {
    return new OcsResponse(r.transactionId(), r.status(), r.code(), r.message(), r.ocsRef());
  }

  public OcsResponse notFound(String transactionId) {
    return new OcsResponse(transactionId, "NOT_FOUND", "4040", "Unknown transaction", null);
  }

  public OcsResponse unavailable(String transactionId) {
    return new OcsResponse(transactionId, "UNAVAILABLE", "5030", "Service unavailable", null);
  }

  public OcsResponse internalError(String transactionId) {
    return new OcsResponse(transactionId, "ERROR", "5000", "Internal error", null);
  }
}
