package mfs.mw.simulators.dto;

/** Optional body of a callback replay; the URL overrides the scenario's callbackUrl. */
public record ReplayRequest(String callbackUrl) {}
