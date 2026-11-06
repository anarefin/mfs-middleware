package mfs.mw.admin.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** A missing correlation id becomes a plain RFC 9562 UUID, which logs and audit keep whole (18.3). */
class CorrelationIdsTest {

  @AfterEach
  void clear() {
    MDC.remove("correlationId");
  }

  @Test
  void keepsAGivenId() {
    assertThat(CorrelationIds.orNew("corr-1")).isEqualTo("corr-1");
  }

  @Test
  void blankOrMissingBecomesAPlainUuid() {
    for (String in : new String[] {null, "", "  "}) {
      String id = CorrelationIds.orNew(in);
      assertThat(UUID.fromString(id).toString()).isEqualTo(id);
      assertThat(UUID.fromString(id).version()).isEqualTo(4);
    }
  }

  @Test
  void currentReadsTheMdcOrMintsOne() {
    MDC.put("correlationId", "from-mdc");
    assertThat(CorrelationIds.current()).isEqualTo("from-mdc");
    MDC.remove("correlationId");
    String id = CorrelationIds.current();
    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
  }
}
