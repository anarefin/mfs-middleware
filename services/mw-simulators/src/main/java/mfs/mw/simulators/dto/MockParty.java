package mfs.mw.simulators.dto;

import java.util.List;

/**
 * A mock party of {@code GET /mock-control/parties}: its operations (the {@code operation} of a
 * scenario), the keys a scenario can be scoped to and the behaviours it carries out (a scenario with
 * another one is refused).
 */
public record MockParty(
    String name, String description, List<String> operations, List<String> keys, List<MockBehaviour> behaviours) {

  /** A party that carries out every call behaviour (the HTTP parties; the file faults are for the file mocks). */
  public MockParty(String name, String description, List<String> operations, List<String> keys) {
    this(name, description, operations, keys, java.util.Arrays.stream(MockBehaviour.values())
        .filter(b -> b.dimension() != MockBehaviour.Dimension.FILE).toList());
  }
}
