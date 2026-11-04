package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Answer to confirm and cancel: the session's status, the payment's txnId, the error of a failed
 * payment and, once terminal, the signed merchant URL the page sends the browser to. PAYING (202)
 * means the outcome is not known yet: the page polls {@code GET /checkout/pay/v1/{token}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayResult(SessionStatus status, String txnId, ErrorInfo error, String redirectUrl) {}
