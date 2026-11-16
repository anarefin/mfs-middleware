package mfs.mw.app.gateway.dto;

import java.time.Instant;

/**
 * The answer of {@code POST /app/v1/balance} (ADR D26; contract {@code Balance}): the core's
 * available balance (balance minus open holds) at {@code asOf}, and the txnId of the check for
 * support. Never stored and never logged.
 */
public record BalanceView(String txnId, Amount available, Instant asOf) {}
