package mfs.mw.orchestrator.service.routing;

/** One ported number: the MNO that serves it, ahead of the prefix table (FRS PLT-042). */
public record MnpOverride(String msisdn, String mno, boolean enabled, int version) {}
