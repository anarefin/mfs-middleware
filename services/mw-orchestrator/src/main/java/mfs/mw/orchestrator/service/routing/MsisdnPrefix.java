package mfs.mw.orchestrator.service.routing;

/** A configurable MSISDN prefix and the MNO it belongs to (FRS PLT-042). */
public record MsisdnPrefix(String prefix, String mno, boolean enabled, int version) {}
