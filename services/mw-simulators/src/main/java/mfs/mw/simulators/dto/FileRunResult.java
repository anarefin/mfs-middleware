package mfs.mw.simulators.dto;

import java.util.List;

/** What one run of a file mock read and wrote (ticket 123): the journal entries it recorded. */
public record FileRunResult(String party, String operation, List<RecordedRequest> entries) {}
