package mfs.mw.simulators.service;

import java.util.concurrent.Executor;
import mfs.mw.simulators.client.CallbackClient;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.mapper.OcsMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Duplicate, late and on-demand callbacks (spec 22.2). */
@Service
public class CallbackService {

  private static final String PARTNER = "ocs";

  private final OcsLedger ledger;
  private final ScenarioStore scenarios;
  private final CallbackClient client;
  private final OcsMapper mapper;
  private final JsonMapper json;
  private final Executor executor;

  public CallbackService(
      OcsLedger ledger,
      ScenarioStore scenarios,
      CallbackClient client,
      OcsMapper mapper,
      JsonMapper json,
      Executor callbackExecutor) {
    this.ledger = ledger;
    this.scenarios = scenarios;
    this.client = client;
    this.mapper = mapper;
    this.json = json;
    this.executor = callbackExecutor;
  }

  /** Sends what the current scenario asks for after a top-up was applied. */
  public void afterTopup(TopupRecord record) {
    Scenario s = scenarios.get(PARTNER);
    if (s.callbackMode() == CallbackMode.NONE
        || s.callbackUrl() == null
        || s.callbackUrl().isBlank()) {
      return;
    }
    int copies = s.callbackMode() == CallbackMode.DUPLICATE ? 2 : 1;
    long delay = s.callbackMode() == CallbackMode.LATE ? s.callbackDelayMs() : 0;
    String body = json.writeValueAsString(mapper.toResponse(record));
    executor.execute(
        () -> {
          sleep(delay);
          for (int i = 0; i < copies; i++) {
            client.post(s.callbackUrl(), body);
          }
        });
  }

  /** Sends the callback for a reference now; returns the target's HTTP status, -1 if unreachable. */
  public int replay(String partner, String reference, String urlOverride) {
    scenarios.require(partner);
    TopupRecord record =
        ledger
            .find(reference)
            .orElseThrow(
                () ->
                    new MwException(ErrorCodes.VAL_NOT_FOUND, "No such reference " + reference)
                        .withStatus(HttpStatus.NOT_FOUND));
    String url =
        urlOverride != null && !urlOverride.isBlank()
            ? urlOverride
            : scenarios.get(partner).callbackUrl();
    if (url == null || url.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "No callbackUrl in the request or the scenario");
    }
    return client.post(url, json.writeValueAsString(mapper.toResponse(record)));
  }

  private static void sleep(long ms) {
    if (ms <= 0) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
