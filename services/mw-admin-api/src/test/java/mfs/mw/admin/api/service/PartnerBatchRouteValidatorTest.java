package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.config.PartnerRouteProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Batch partner routes at submit (ticket 86): the maker-side copy of the adapters' rules for
 * {@code from: kamelet:mw-sftp-source} routes ending in {@code mw-recon} and for the {@code
 * mw-sftp-sink} Kamelet, as mw-adapter-billpay's {@code PartnerBatchRouteLoaderTest}.
 */
class PartnerBatchRouteValidatorTest {

  final PartnerRouteValidator validator =
      new PartnerRouteValidator(new PartnerRouteProperties(null), JsonMapper.builder().build());

  static final String SOURCE =
      "kamelet:mw-sftp-source?host=sftp.acme.example&port=2222&username=bits&directory=recon/daily"
          + "&secretField=sftp-password&knownHostsField=sftp-known-hosts&delay=60000";
  static final String RECON = "- to: \"kamelet:mw-recon?columns=reference,amount,status&delimiter=comma\"";

  static String batch(String from, String steps) {
    return """
        - route:
            from:
              uri: "%s"
              steps:
        %s
        """.formatted(from, steps.indent(8).stripTrailing());
  }

  List<PartnerRouteValidator.Problem> check(String yaml) {
    return validator.problems("acme", yaml, Map.of(), List.of("sftp.acme.example"));
  }

  @Test
  void aBatchRouteHasNoProblems() {
    String steps = """
        - log: "file received"
        - to: "kamelet:mw-sftp-sink?host=sftp.acme.example&username=bits&directory=out&fileName=ack-${date:now:yyyyMMdd}.csv&secretField=sftp-password&knownHostsField=sftp-known-hosts"
        """ + RECON;
    assertThat(check(batch(SOURCE, steps))).isEmpty();
  }

  @Test
  void theLibraryHasTheBatchKamelets() {
    assertThat(validator.kamelets()).contains("mw-sftp-source", "mw-sftp-sink", "mw-recon");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "&delete=true", "&strictHostKeyChecking=no", "&knownHosts=x", "&password=x", "&auth=none", "&port=99999",
      "&maxMessagesPerPoll=0"})
  void anOptionOutsideTheListOrAWrongValueIsRefused(String extra) {
    assertThat(check(batch(SOURCE + extra, RECON)).toString()).contains("mw-sftp-source");
  }

  @Test
  void aHostOutsideTheAllowedHostsIsRefused() {
    assertThat(check(batch(SOURCE.replace("sftp.acme.example", "sftp.evil.example"), RECON)).toString())
        .contains("allowedHosts");
  }

  @Test
  void aBatchRouteMustEndWithMwRecon() {
    assertThat(check(batch(SOURCE, "- log: \"file\"")).toString()).contains("a batch route must end with mw-recon");
  }

  @Test
  void theSourceIsOnlyTheStartOfARoute() {
    assertThat(check(batch(SOURCE, "- to: \"" + SOURCE + "\"\n" + RECON)).toString()).contains("mw-sftp-source may only start");
  }

  @Test
  void isBatchTellsTheTwoShapesApart() {
    assertThat(PartnerRouteValidator.isBatch(batch(SOURCE, RECON))).isTrue();
    assertThat(PartnerRouteValidator.isBatch("- route:\n    from:\n      uri: direct:partner-acme\n")).isFalse();
  }
}
