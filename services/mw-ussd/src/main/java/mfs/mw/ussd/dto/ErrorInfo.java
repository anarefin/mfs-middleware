package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The {@code error} object of {@code mw.txn.status.v1} (spec 5.3). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorInfo(String code, String message, String messageBn, boolean retryable) {}
