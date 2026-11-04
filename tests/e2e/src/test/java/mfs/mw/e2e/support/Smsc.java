package mfs.mw.e2e.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The SMSC simulator in mw-simulators (ticket 24) through the dev-only APISIX route {@code
 * /test/sim/*}: {@code GET /sim/smsc/messages?msisdn=} lists the SMS it received over SMPP,
 * reassembled from their parts, with the encoding and the DLR it sent.
 */
public final class Smsc {

  /** One received SMS. */
  public record Sms(String destination, String source, String text, String encoding, int segments, String dlrStatus) {}

  private Smsc() {}

  public static List<Sms> messagesTo(String msisdn) {
    Http.Response r =
        Http.get(Stack.get().url("test/sim/smsc/messages?msisdn=" + msisdn),
            Map.of("apikey", Stack.get().devCredential("MW_E2E_API_KEY")));
    if (r.status() != 200) {
      throw new IllegalStateException("SMSC simulator query failed: " + r);
    }
    List<Sms> out = new ArrayList<>();
    for (JsonNode m : r.body()) {
      out.add(new Sms(m.path("destination").asString(), m.path("source").asString(), m.path("text").asString(),
          m.path("encoding").asString(), m.path("segments").asInt(), m.path("dlrStatus").asString(null)));
    }
    return out;
  }
}
