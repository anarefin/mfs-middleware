package mfs.mw.adapter.telco.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** OCS reply body: status SUCCESS | FAILED | NOT_FOUND | UNAVAILABLE | ERROR, with the OCS' own code. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OcsResponse(
    String transactionId, String status, String code, String message, String ocsRef) {}
