package mfs.mw.simulators.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A received config snapshot cannot be changed behind the consumer's back. */
class ConfigChangedEventTest {

  ConfigChangedEvent event(Map<String, Object> value) {
    return new ConfigChangedEvent(
        1, "partner/gp", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT,
        3, value, null, "ops.maker1", null, null);
  }

  @Test
  void valueIsACopyThatCannotBeModified() {
    Map<String, Object> source = new HashMap<>();
    source.put("timeoutMs", 5000);
    source.put("note", null);
    ConfigChangedEvent e = event(source);

    source.put("timeoutMs", 1);
    assertThat(e.value()).containsEntry("timeoutMs", 5000).containsKey("note");
    assertThatThrownBy(() -> e.value().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void deleteCarriesNoValue() {
    assertThat(event(null).value()).isNull();
  }
}
