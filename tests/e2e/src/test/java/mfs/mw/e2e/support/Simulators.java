package mfs.mw.e2e.support;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * mw-simulators' control API (spec 22.2) through the dev-only APISIX route {@code /test/sim/*}
 * ({@code deploy/apisix/routes-test/test-simulators.json}, key-auth with {@code MW_E2E_API_KEY}).
 * A scenario applies to every request of that partner until reset: reset it in an {@code @After}
 * hook.
 */
public final class Simulators {

  private static final String BASE = "test/sim/";

  private Simulators() {}

  /** {@code POST /sim/{partner}/scenario} ({@code forceMode, latencyMs, errorRate, outage, ...}). */
  public static void setScenario(String partner, JsonNode scenario) {
    Http.Response r = Http.postJson(Stack.get().url(BASE + partner + "/scenario"), auth(), scenario);
    if (r.status() / 100 != 2) {
      throw new IllegalStateException("simulator scenario not accepted: " + r);
    }
  }

  /** {@code DELETE /sim/{partner}/scenario}: back to the magic-value behaviour. */
  public static void reset(String partner) {
    Http.Response r = Http.delete(Stack.get().url(BASE + partner + "/scenario"), auth());
    if (r.status() != 204) {
      throw new IllegalStateException("simulator reset failed: " + r);
    }
  }

  private static Map<String, String> auth() {
    return Map.of("apikey", Stack.get().devCredential("MW_E2E_API_KEY"));
  }
}
