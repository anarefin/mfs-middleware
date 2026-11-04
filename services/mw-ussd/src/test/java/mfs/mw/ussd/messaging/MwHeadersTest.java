package mfs.mw.ussd.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Kafka record headers arrive as UTF-8 bytes; values set in the route are plain objects. */
class MwHeadersTest {

  @Test
  void bytesAreDecodedAsUtf8() {
    assertThat(MwHeaders.text("corr-আ-1".getBytes(StandardCharsets.UTF_8))).isEqualTo("corr-আ-1");
  }

  @Test
  void otherValuesUseToString() {
    assertThat(MwHeaders.text(42L)).isEqualTo("42");
    assertThat(MwHeaders.text("txn-1")).isEqualTo("txn-1");
  }

  @Test
  void absentHeaderIsNull() {
    assertThat(MwHeaders.text(null)).isNull();
  }
}
