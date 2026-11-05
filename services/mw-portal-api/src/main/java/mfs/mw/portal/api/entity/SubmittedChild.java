package mfs.mw.portal.api.entity;

import java.time.Instant;
import java.util.UUID;

/** A SUBMITTED row the reconcile job asks the orchestrator about, with its batch's org. */
public record SubmittedChild(UUID batchId, int lineNo, UUID txnId, String orgId, Instant submittedAt) {}
