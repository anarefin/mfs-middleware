package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.config.PartnerRouteProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The maker-side copy of the adapters' rules for the auth options of ticket 87 ({@code
 * mw-http-call} {@code auth=basic|oauth2-cc|jws}, {@code sslContextRef}), as the adapters' {@code
 * AuthRouteLoaderTest}: the Kamelets are in the library, each scheme needs its parameters, the
 * token URL is held to the record's hosts, a TLS client context needs https. Whether the context
 * belongs to the partner only the adapter knows (static config): it reports REJECTED otherwise.
 */
class PartnerRouteValidatorAuthTest {

  final PartnerRouteValidator validator =
      new PartnerRouteValidator(new PartnerRouteProperties(null), JsonMapper.builder().build());

  static final Map<String, String> MAPPINGS = Map.of("PAY.request", "{\"a\": reference}", "PAY.response", "{\"outcome\": \"UNKNOWN\"}");

  static String route(String httpCall) {
    return """
        - route:
            from:
              uri: direct:partner-acme
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "kamelet:mw-http-call?method=POST&path=/pay&%s"
                - to: "kamelet:mw-classify?mapping=PAY.response"
                - to: "kamelet:mw-audit"
        """.formatted(httpCall);
  }

  List<String> problems(String httpCall) {
    return validator.problems("acme", route(httpCall), MAPPINGS, List.of("api.acme.example", "auth.acme.example"))
        .stream().map(PartnerRouteValidator.Problem::toString).toList();
  }

  @Test
  void theAuthKameletsAreInTheLibrary() {
    assertThat(validator.kamelets()).contains("mw-auth-basic", "mw-auth-oauth2-cc", "mw-auth-jws");
  }

  @Test
  void eachNewSchemeWithWhatItNeedsHasNoProblems() {
    assertThat(problems("url=https://api.acme.example&auth=basic&userField=user&secretField=password")).isEmpty();
    assertThat(problems("url=https://api.acme.example&auth=oauth2-cc&secretField=client-secret&clientId=bits"
        + "&tokenUrl=https://auth.acme.example/oauth/token&scope=pay+read&audience=https://api.acme.example")).isEmpty();
    assertThat(problems("url=https://api.acme.example&auth=jws&secretField=jws-key&alg=RS256&keyId=k1")).isEmpty();
    assertThat(problems("url=https://api.acme.example&sslContextRef=acme-client")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "url=https://api.acme.example&auth=basic&secretField=password | auth basic needs a userField",
      "url=https://api.acme.example&auth=oauth2-cc&secretField=s&tokenUrl=https://auth.acme.example/t | auth oauth2-cc needs a clientId",
      "url=https://api.acme.example&auth=oauth2-cc&secretField=s&clientId=c | auth oauth2-cc needs a tokenUrl",
      "url=https://api.acme.example&auth=jws&secretField=k&alg=HS256 | auth jws needs alg RS256 or ES256",
      "url=https://api.acme.example&auth=oauth2-cc&secretField=s&clientId=c&tokenUrl=https://evil.example/t | allowedHosts",
      "url=https://api.acme.example&auth=oauth2-cc&secretField=s&clientId=c&tokenUrl=https://auth.acme.example/t?x=1 | character",
      "url=http://api.acme.example&sslContextRef=acme-client | sslContextRef needs an https url",
  })
  void aSchemeMissingWhatItNeedsOrReachingElsewhereIsRefused(String httpCall, String problem) {
    assertThat(String.join("; ", problems(httpCall))).contains(problem);
  }
}
