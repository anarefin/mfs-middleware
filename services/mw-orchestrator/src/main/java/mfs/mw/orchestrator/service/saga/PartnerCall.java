package mfs.mw.orchestrator.service.saga;

/**
 * The partner leg of a flow (spec 6.2): the {@code family} of {@code mw.partner.request.v1} and the
 * topic the request goes to. Replies come back on {@code mw.partner.reply.v1}.
 */
public record PartnerCall(String family, String topic) {}
