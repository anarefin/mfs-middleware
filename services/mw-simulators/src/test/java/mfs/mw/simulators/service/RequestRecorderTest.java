package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.RecordedRequest;
import org.junit.jupiter.api.Test;

class RequestRecorderTest {

  private final RequestRecorder recorder =
      new RequestRecorder(new SimulatorProperties(Duration.ofMillis(10), 3, List.of("ocs")));

  private void add(String path) {
    recorder.record("ocs", "POST", path, null, Map.of(), "{}", Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void recordsInOrderWithSequenceNumbers() {
    add("/a");
    add("/b");
    List<RecordedRequest> all = recorder.list("ocs", null);
    assertThat(all).extracting(RecordedRequest::path).containsExactly("/a", "/b");
    assertThat(all).extracting(RecordedRequest::seq).containsExactly(1L, 2L);
  }

  @Test
  void filtersByPathSubstring() {
    add("/ocs/v1/topup");
    add("/ocs/v1/topup/T-1");
    assertThat(recorder.list("ocs", "/topup/")).hasSize(1);
  }

  @Test
  void keepsOnlyTheLatestRecordLimit() {
    add("/1");
    add("/2");
    add("/3");
    add("/4");
    assertThat(recorder.list("ocs", null))
        .extracting(RecordedRequest::path)
        .containsExactly("/2", "/3", "/4");
  }

  @Test
  void clearEmptiesOnePartnerAndKeepsNumbering() {
    add("/1");
    recorder.clear("ocs");
    assertThat(recorder.list("ocs", null)).isEmpty();
    add("/2");
    assertThat(recorder.list("ocs", null).get(0).seq()).isEqualTo(2L);
  }
}
