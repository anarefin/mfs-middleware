package mfs.mw.adapter.remit.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one query-string reader of the host guard and the batch Kamelet rules (review of tickets
 * 84-87): names and values are read URL-decoded, as Camel reads them, so an encoded value cannot
 * pass a check its decoded form would fail.
 */
class QueryParamsTest {

  @Test
  void namesAndValuesAreReadDecodedInOrder() {
    assertThat(QueryParams.parse("a=1&dir=recon%2Fdaily&flag&e%6Ed=%23x&&"))
        .containsExactly(
            Map.entry("a", "1"), Map.entry("dir", "recon/daily"), Map.entry("flag", ""), Map.entry("end", "#x"));
  }

  @Test
  void anEmptyQueryHasNoParameters() {
    assertThat(QueryParams.parse("")).isEmpty();
    assertThat(QueryParams.parse(null)).isEmpty();
  }

  @Test
  void aBrokenEncodingIsRefused() {
    assertThatThrownBy(() -> QueryParams.parse("a=%zz")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theGuardReadsAnEncodedOptionDecoded() {
    // %66alse is "false": accepted as mw-http-call sets it; %74rue is "true": refused
    assertThat(PartnerHostGuard.optionProblem("followRedirects=%66alse&connectTimeout=3000")).isNull();
    assertThat(PartnerHostGuard.optionProblem("followRedirects=%74rue")).contains("followRedirects");
    assertThat(PartnerHostGuard.soapOptionProblem("dataFormat=PAYLOA%44&headerFilterStrategy=%23mwSoapHeaderFilter")).isNull();
    assertThat(PartnerHostGuard.soapOptionProblem("dataFormat=RA%57&headerFilterStrategy=%23mwSoapHeaderFilter"))
        .contains("dataFormat");
  }

  @Test
  void aBatchKameletParameterIsCheckedDecoded() {
    List<String> security = new java.util.ArrayList<>();
    PartnerBatchRoutes.Calls calls = new PartnerBatchRoutes.Calls("acme", "partner-acme-v1", true, new java.util.LinkedHashSet<>());
    String encodedDotDot = "kamelet:mw-sftp-sink?host=sftp.acme.test&username=bits&directory=out%2F%2E%2E"
        + "&secretField=sftp-password&knownHostsField=sftp-known-hosts&fileName=f.csv";
    assertThat(PartnerBatchRoutes.kamelet(encodedDotDot, false, calls, security)).isNull();
    assertThat(security).singleElement().asString().contains("directory");

    security.clear();
    String encoded = "kamelet:mw-sftp-sink?host=sftp.acme.test&username=bits&directory=out%2Fdaily"
        + "&secretField=sftp-password&knownHostsField=sftp-known-hosts&fileName=f.csv";
    assertThat(PartnerBatchRoutes.kamelet(encoded, false, calls, security)).contains("&directory=out/daily&");
    assertThat(security).isEmpty();
  }
}
