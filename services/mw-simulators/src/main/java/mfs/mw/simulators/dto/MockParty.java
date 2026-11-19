package mfs.mw.simulators.dto;

import java.util.List;

/**
 * A mock party of {@code GET /mock-control/parties}: its operations (the {@code operation} of a
 * scenario), the keys a scenario can be scoped to and the behaviours it carries out (a scenario with
 * another one is refused).
 */
public record MockParty(
    String name, String description, List<String> operations, List<String> keys, List<MockBehaviour> behaviours) {

  /** A party that carries out every behaviour (the HTTP parties). */
  public MockParty(String name, String description, List<String> operations, List<String> keys) {
    this(name, description, operations, keys, List.of(MockBehaviour.values()));
  }
}
