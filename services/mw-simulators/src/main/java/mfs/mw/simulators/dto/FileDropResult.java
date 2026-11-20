package mfs.mw.simulators.dto;

/** Where a dropped file landed (ticket 123): its path on the file host, SHA-256 and size. */
public record FileDropResult(String path, String sha256, int bytes) {}
