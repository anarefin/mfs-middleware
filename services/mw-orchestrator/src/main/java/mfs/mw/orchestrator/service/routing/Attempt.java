package mfs.mw.orchestrator.service.routing;

/**
 * One partner a transaction may be sent to. {@code wirePartnerId} is the id on the partner
 * request (the operator the channel named, when a rule allows it). {@code healthPartnerId} is
 * the connector whose health and circuit decide whether this attempt is usable.
 */
public record Attempt(String wirePartnerId, String healthPartnerId) {}
