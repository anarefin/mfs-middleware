package mfs.mw.orchestrator.service.routing;

/**
 * What routing matches a rule against. {@code operatorCode} is the named operator, or the one
 * resolved from the MSISDN when the caller named none. {@code requestedPartnerId} is the partner
 * the channel sent, null when it left the choice to routing.
 */
public record RoutingQuery(
    String txnType,
    String productCode,
    String billerCode,
    String bankCode,
    String operatorCode,
    long amountMinor,
    String channel,
    String requestedPartnerId) {}
