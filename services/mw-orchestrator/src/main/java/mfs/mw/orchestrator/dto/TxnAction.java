package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * An operator action on {@code mw.txn.action.v1} (contract owned by mw-admin-api, ticket 37):
 * {@code INQUIRE}, {@code FORCE_COMMIT} or {@code FORCE_RELEASE}, approved by maker-checker.
 * Unknown fields are ignored. {@code reason} is the maker's free text and is never logged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxnAction(
    int schemaVersion,
    String actionId,
    String txnId,
    String action,
    String correlationId,
    String changeRequestId,
    String makerId,
    String checkerId,
    String reason,
    Instant requestedAt) {

  public static final String INQUIRE = "INQUIRE";
  public static final String FORCE_COMMIT = "FORCE_COMMIT";
  public static final String FORCE_RELEASE = "FORCE_RELEASE";
}
