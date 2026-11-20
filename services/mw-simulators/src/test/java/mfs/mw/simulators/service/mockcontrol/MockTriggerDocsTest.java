package mfs.mw.simulators.service.mockcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.dto.MockParty;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.service.MagicAmounts;
import org.junit.jupiter.api.Test;

/**
 * The trigger docs of every mock (ticket 136, FRS MCK-03): {@code src/main/resources/mock-triggers.md} is the
 * rendering of {@link MockTriggers}, so it lists every trigger of the registry and none that does not
 * exist; {@code docs/mocks/README.md} carries the same text ({@code scripts/test/test_mock_triggers_doc.sh}).
 */
class MockTriggerDocsTest {

  @Test
  void everyMockPartyHasExactlyOneSection() {
    List<String> documented = new ArrayList<>();
    MockTriggers.SECTIONS.forEach(s -> documented.addAll(s.parties()));
    assertThat(documented).doesNotHaveDuplicates();
    assertThat(documented).containsExactlyInAnyOrderElementsOf(MockParties.ALL.stream().map(MockParty::name).toList());
  }

  @Test
  void everySectionNamesItsFrsPartyAndHasTriggersOrSaysWhyNot() {
    for (MockTriggers.Section s : MockTriggers.SECTIONS) {
      assertThat(s.ext()).as(s.title()).isNotEmpty().allMatch(e -> e.matches("EXT-(0[1-9]|1[0-8])|CON-[0-9]{2}|spec [0-9]+\\.[0-9]+"));
      assertThat(s.triggers().isEmpty() ? s.noTriggers() : "ok").as(s.title()).isNotBlank();
    }
  }

  @Test
  void theAmountTriggersAreTheOnesTheSimulatorsRun() {
    // the published amount fractions and what MagicAmounts does with them (a worked example each)
    Map<String, SimMode> published = Map.of(
        "13", SimMode.TIMEOUT, "77", SimMode.SERVER_ERROR_AFTER_SEND, "99", SimMode.UNKNOWN_CODE, "55", SimMode.MALFORMED);
    published.forEach((cents, mode) -> assertThat(MagicAmounts.mode("250." + cents)).as(cents).isEqualTo(mode));
    assertThat(MagicAmounts.mode("250.12")).isEqualTo(SimMode.SUCCESS);
    assertThat(MockTriggers.AMOUNT_FRACTIONS).containsOnlyKeys(published.keySet());
  }

  @Test
  void theCommittedTriggerDocIsTheRenderingOfTheRegistry() throws IOException {
    String rendered = MockTriggers.markdown();
    String committed;
    try (InputStream in = getClass().getResourceAsStream("/mock-triggers.md")) {
      committed = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (!committed.equals(rendered)) {
      Path out = Path.of("build", "mock-triggers.md");
      Files.createDirectories(out.getParent());
      Files.writeString(out, rendered);
    }
    assertThat(committed)
        .as("src/main/resources/mock-triggers.md is stale: copy build/mock-triggers.md over it and into docs/mocks/README.md")
        .isEqualTo(rendered);
  }
}
