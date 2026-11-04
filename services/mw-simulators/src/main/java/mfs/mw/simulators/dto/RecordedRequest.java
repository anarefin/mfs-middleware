package mfs.mw.simulators.dto;

import java.time.Instant;
import java.util.Map;

/** A request a simulator received, kept for assertions (spec 22.2). Test-only data, unmasked. */
public record RecordedRequest(
    long seq, Instant at, String method, String path, String query, Map<String, String> headers, String body) {}
