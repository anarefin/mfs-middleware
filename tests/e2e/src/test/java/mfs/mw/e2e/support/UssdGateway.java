package mfs.mw.e2e.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The USSD gateway simulator in HTTP mode (mw-simulators, ticket 26) through the test-only route
 * {@code /test/sim/*}: it plays the MNO, POSTing every step of one session to mw-ussd through
 * APISIX ({@code http://apisix:9080/ussd/<mno>}, route {@code ussd-http}) and returns the screens.
 */
public final class UssdGateway {

  private UssdGateway() {}

  /** One answer of mw-ussd: HTTP status, screen text and continue/end flag. */
  public record Screen(int status, String message, String flag) {}

  /** A played session: the screens in order (dial-in answer first) and whether mw-ussd ended it. */
  public record Session(String sessionId, List<Screen> screens, boolean ended) {

    public Screen last() {
      return screens.getLast();
    }
  }

  /** Dials {@code serviceCode} as {@code msisdn} on {@code mno} and sends {@code inputs} in order. */
  public static Session dial(String mno, String msisdn, String serviceCode, List<String> inputs) {
    ObjectNode body = Http.JSON.createObjectNode();
    body.put("mno", mno).put("msisdn", msisdn).put("serviceCode", serviceCode);
    inputs.forEach(body.putArray("inputs")::add);
    Http.Response r =
        Http.postJson(
            Stack.get().url("test/sim/ussdgw/sessions"),
            Map.of("apikey", Stack.get().devCredential("MW_E2E_API_KEY")),
            body);
    if (r.status() != 200) {
      throw new IllegalStateException("USSD gateway simulator refused the session: " + r);
    }
    List<Screen> screens = new ArrayList<>();
    for (JsonNode s : r.body().path("screens")) {
      screens.add(new Screen(s.path("status").asInt(), s.path("message").asString(null), s.path("flag").asString(null)));
    }
    return new Session(r.body().path("sessionId").asString(), List.copyOf(screens), r.body().path("ended").asBoolean());
  }
}
