package mfs.mw.simulators.dto;

/** OCS reply: status SUCCESS | FAILED | NOT_FOUND | UNAVAILABLE | ERROR, with the OCS' own code. */
public record OcsResponse(String transactionId, String status, String code, String message, String ocsRef) {}
