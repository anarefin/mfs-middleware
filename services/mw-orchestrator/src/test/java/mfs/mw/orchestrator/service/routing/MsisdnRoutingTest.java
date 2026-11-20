package mfs.mw.orchestrator.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Prefix to MNO, and an MNP override that beats the prefix (ticket 98, FRS PLT-042). */
class MsisdnRoutingTest {

  private static final List<MsisdnPrefix> PREFIXES =
      List.of(
          new MsisdnPrefix("017", "gp", true, 1),
          new MsisdnPrefix("013", "gp", true, 1),
          new MsisdnPrefix("018", "robi", true, 1),
          new MsisdnPrefix("015", "teletalk", true, 1),
          new MsisdnPrefix("019", "banglalink", true, 1),
          new MsisdnPrefix("014", "banglalink", true, 1),
          new MsisdnPrefix("016", "airtel", false, 1));

  @Test
  void everySeededPrefixResolvesItsMnoAndAnOverrideBeatsThePrefix() {
    assertThat(MsisdnRouting.mno("01711000002", PREFIXES, List.of())).contains("gp");
    assertThat(MsisdnRouting.mno("01311000002", PREFIXES, List.of())).contains("gp");
    assertThat(MsisdnRouting.mno("01811000002", PREFIXES, List.of())).contains("robi");
    assertThat(MsisdnRouting.mno("01511000002", PREFIXES, List.of())).contains("teletalk");
    assertThat(MsisdnRouting.mno("01911000002", PREFIXES, List.of())).contains("banglalink");
    assertThat(MsisdnRouting.mno("01411000002", PREFIXES, List.of())).contains("banglalink");
    assertThat(MsisdnRouting.mno("+8801711000002", PREFIXES, List.of())).contains("gp");
    assertThat(MsisdnRouting.mno("01611000002", PREFIXES, List.of())).isEmpty();

    List<MnpOverride> overrides = List.of(new MnpOverride("01711000002", "robi", true, 1));
    assertThat(MsisdnRouting.mno("01711000002", PREFIXES, overrides)).contains("robi");
    assertThat(MsisdnRouting.mno("8801711000002", PREFIXES, overrides)).contains("robi");
  }
}
