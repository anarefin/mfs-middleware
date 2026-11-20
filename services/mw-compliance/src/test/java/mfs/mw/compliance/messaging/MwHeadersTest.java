package mfs.mw.compliance.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

/** Kafka record headers arrive as UTF-8 bytes; {@link MwHeaders#text} reads them as text. */
class MwHeadersTest {

  @Test
  void bytesAreDecodedAsUtf8() {
    assertThat(MwHeaders.text(new RecordHeader("mw-correlation-id", "corr-আ-1".getBytes(StandardCharsets.UTF_8))))
        .isEqualTo("corr-আ-1");
  }

  @Test
  void absentHeaderOrValueIsNull() {
    assertThat(MwHeaders.text(new RecordHeaders().lastHeader("mw-txn-id"))).isNull();
    assertThat(MwHeaders.text(new RecordHeader("mw-txn-id", null))).isNull();
  }
}
