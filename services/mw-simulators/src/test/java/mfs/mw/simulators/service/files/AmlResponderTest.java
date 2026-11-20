package mfs.mw.simulators.service.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import mfs.mw.simulators.service.SeedFiles;
import mfs.mw.simulators.service.files.AmlResponder.AmlRules;
import mfs.mw.simulators.service.files.TrustStatementGenerator.Mismatch;
import mfs.mw.simulators.util.Hashing;
import org.junit.jupiter.api.Test;

/**
 * The AML vendor mock (ticket 123, FRS EXT-05): a transaction file in the draft format of {@code
 * contracts/files/aml/README.md} gets a response file with one ALERT / HOLD / BLOCK line per flagged
 * transaction (the strongest rule wins) and a trailer with the totals; a file whose trailer disagrees is
 * answered with an error line.
 */
class AmlResponderTest {

  private static final AmlRules RULES = new AmlRules(5_000_000, List.of("01711000777"), "6666");

  private final AmlResponder responder = new AmlResponder(RULES);

  private static String file(String... rows) {
    StringBuilder body = new StringBuilder("H|AML_TXN|1|F-2026112010|2026-11-20T10:00:00+06:00\n");
    long total = 0;
    for (String r : rows) {
      body.append(r).append('\n');
      total += Long.parseLong(r.split("\\|")[6]);
    }
    String sha = Hashing.sha256Hex(body.toString().getBytes(StandardCharsets.UTF_8));
    return body + "T|" + rows.length + "|" + total + "|" + sha + "\n";
  }

  private static final String CLEAN =
      "D|0196a1b2-0000-7000-8000-000000000001|P2P|APP|WALLET:01712345678|WALLET:01812345678|10000|2026-11-20T09:00:00+06:00";
  private static final String BIG =
      "D|0196a1b2-0000-7000-8000-000000000002|P2P|APP|WALLET:01712345678|WALLET:01812345678|6000000|2026-11-20T09:05:00+06:00";
  private static final String WATCHED =
      "D|0196a1b2-0000-7000-8000-000000000003|CASH_OUT|USSD|WALLET:01711000777|WALLET:01900000001|20000|2026-11-20T09:10:00+06:00";
  private static final String BLOCKED =
      "D|0196a1b2-0000-7000-8000-000000000004|P2P|APP|WALLET:01712345678|WALLET:01811116666|7000000|2026-11-20T09:15:00+06:00";

  @Test
  void eachRuleGivesItsLineAndTheTrailerCountsThem() {
    String in = file(CLEAN, BIG, WATCHED, BLOCKED);

    AmlResponder.Response r = responder.respond("AML_TXN_2026112010.txt", in.getBytes(StandardCharsets.UTF_8), Mismatch.NONE);

    assertThat(r.fileName()).isEqualTo("AML_RESP_2026112010.txt");
    String ref = Hashing.sha256Hex(in.getBytes(StandardCharsets.UTF_8)).substring(0, 8);
    assertThat(new String(r.content(), StandardCharsets.UTF_8).lines().toList()).containsExactly(
        "H|AML_RESP|1|AML_TXN_2026112010.txt|" + Hashing.sha256Hex(in.getBytes(StandardCharsets.UTF_8)),
        "ALERT|0196a1b2-0000-7000-8000-000000000002|01712345678|AMOUNT_THRESHOLD|AML-" + ref + "-1",
        "HOLD|0196a1b2-0000-7000-8000-000000000003|01711000777|WATCH_LIST|AML-" + ref + "-2",
        "BLOCK|0196a1b2-0000-7000-8000-000000000004|01811116666|BLOCK_SUFFIX|AML-" + ref + "-3",
        "T|3|1|1|1");
  }

  @Test
  void theSameFileGivesTheSameBytes() {
    byte[] in = file(BIG, BLOCKED).getBytes(StandardCharsets.UTF_8);
    assertThat(responder.respond("AML_TXN_1.txt", in, Mismatch.NONE).content())
        .isEqualTo(responder.respond("AML_TXN_1.txt", in, Mismatch.NONE).content());
  }

  @Test
  void aTrailerThatDisagreesIsAnsweredWithAnErrorLine() {
    String in = file(CLEAN).replace("T|1|", "T|2|");

    AmlResponder.Response r = responder.respond("AML_TXN_x.txt", in.getBytes(StandardCharsets.UTF_8), Mismatch.NONE);

    List<String> lines = new String(r.content(), StandardCharsets.UTF_8).lines().toList();
    assertThat(lines.get(1)).isEqualTo("E|COUNT_MISMATCH|trailer says 2 rows, file has 1");
    assertThat(lines.get(2)).isEqualTo("T|0|0|0|0");
  }

  @Test
  void anInjectedMismatchAddsLinesForUnknownTransactions() {
    AmlResponder.Response r = responder.respond("AML_TXN_y.txt", file(CLEAN).getBytes(StandardCharsets.UTF_8),
        new Mismatch("UNKNOWN_TXN", 2));

    List<String> lines = new String(r.content(), StandardCharsets.UTF_8).lines().toList();
    assertThat(lines).hasSize(4);
    assertThat(lines.get(1)).startsWith("ALERT|UNKNOWN-1|-|INJECTED|");
    assertThat(lines.get(3)).isEqualTo("T|2|2|0|0");
  }

  @Test
  void theSeededRulesAreTheDocumentedOnes() {
    AmlRules seeded = SeedFiles.amlRules("v1");

    assertThat(seeded.alertAboveMinor()).isEqualTo(5_000_000L);
    assertThat(seeded.blockSuffix()).isEqualTo("6666");
    assertThat(seeded.watchList()).contains("01711000777");
  }
}
