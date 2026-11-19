package mfs.mw.core.mock.dto;

import java.util.List;

/**
 * A mock party of {@code GET /mock-control/parties}: its operations (the {@code operation} of a
 * scenario) and the keys a scenario can be scoped to.
 */
public record MockParty(String name, String description, List<String> operations, List<String> keys) {}
