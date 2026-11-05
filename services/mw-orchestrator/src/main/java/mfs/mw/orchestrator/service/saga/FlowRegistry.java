package mfs.mw.orchestrator.service.saga;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The flows by transaction type; a type without a flow is rejected at intake. */
@Component
public class FlowRegistry {

  private final Map<String, Flow> byType = new HashMap<>();

  public FlowRegistry(List<Flow> flows) {
    for (Flow f : flows) {
      byType.put(f.txnType(), f);
    }
  }

  public Optional<Flow> find(String txnType) {
    return Optional.ofNullable(byType.get(txnType));
  }
}
