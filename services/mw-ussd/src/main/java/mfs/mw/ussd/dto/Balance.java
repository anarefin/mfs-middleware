package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * The balance a BALANCE inquiry's COMPLETED status carries (ADR D26; {@code balance} of {@code
 * mw.txn.status.v1}): the core's available balance, passed through for the screen only. Never
 * stored (not in the session) and never logged: every field that holds one is {@code @Sensitive}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Balance(Amount available, Instant asOf) {}
