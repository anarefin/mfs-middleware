package mfs.mw.simulators.service;

import java.util.concurrent.Executor;
import mfs.mw.simulators.client.CallbackClient;
import mfs.mw.simulators.client.CallbackSigner;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.mapper.BillAggregatorMapper;
import mfs.mw.simulators.mapper.OcsMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Duplicate, late, out-of-order and on-demand callbacks (spec 22.2) for the OCS and the bill
 * aggregator. The callback body is the partner's own reply for the reference (billagg: {@code
 * {transactionId, status, code, message, paymentRef}}), posted to the scenario's {@code
 * callbackUrl} and signed when the partner has a secret ({@link CallbackSigner}; signed at send
 * time, so a late callback carries a fresh timestamp). Sent off the request thread.
 */
@Service
public class CallbackService {

  private static final String OCS = "ocs";

  private final OcsLedger ledger;
  private final BillPaymentLedger payments;
  private final ScenarioStore scenarios;
  private final CallbackClient client;
  private final CallbackSigner signer;
  private final OcsMapper mapper;
  private final BillAggregatorMapper billMapper;
  private final JsonMapper json;
  private final Executor executor;

  public CallbackService(
      OcsLedger ledger,
      BillPaymentLedger payments,
      ScenarioStore scenarios,
      CallbackClient client,
      CallbackSigner signer,
      OcsMapper mapper,
      BillAggregatorMapper billMapper,
      JsonMapper json,
      Executor callbackExecutor) {
    this.ledger = ledger;
    this.payments = payments;
    this.scenarios = scenarios;
    this.client = client;
    this.signer = signer;
    this.mapper = mapper;
    this.billMapper = billMapper;
    this.json = json;
    this.executor = callbackExecutor;
  }

  /** Sends what the current scenario asks for after a top-up was applied. */
  public void afterTopup(TopupRecord record) {
    send(OCS, json.writeValueAsString(mapper.toResponse(record)), null);
  }

  /**
   * Sends what the current billagg scenario asks for after a payment was applied; OUT_OF_ORDER
   * follows the outcome with a stale PENDING.
   */
  public void afterPayment(BillPaymentRecord record) {
    send(
        BillAggregatorService.PARTNER,
        json.writeValueAsString(billMapper.toResponse(record)),
        json.writeValueAsString(billMapper.pending(record)));
  }

  private void send(String partner, String body, String staleBody) {
    Scenario s = scenarios.get(partner);
    if (s.callbackMode() == CallbackMode.NONE || s.callbackUrl() == null || s.callbackUrl().isBlank()) {
      return;
    }
    int copies = s.callbackMode() == CallbackMode.DUPLICATE ? 2 : 1;
    long delay = s.callbackMode() == CallbackMode.LATE ? s.callbackDelayMs() : 0;
    boolean stale = s.callbackMode() == CallbackMode.OUT_OF_ORDER && staleBody != null;
    String url = s.callbackUrl();
    executor.execute(
        () -> {
          sleep(delay);
          for (int i = 0; i < copies; i++) {
            client.post(url, body, signer.headers(partner, url, body));
          }
          if (stale) {
            client.post(url, staleBody, signer.headers(partner, url, staleBody));
          }
        });
  }

  /** Sends the callback for a reference now; returns the target's HTTP status, -1 if unreachable. */
  public int replay(String partner, String reference, String urlOverride) {
    scenarios.require(partner);
    String body =
        BillAggregatorService.PARTNER.equals(partner)
            ? payments.find(reference).map(r -> json.writeValueAsString(billMapper.toResponse(r))).orElse(null)
            : ledger.find(reference).map(r -> json.writeValueAsString(mapper.toResponse(r))).orElse(null);
    if (body == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "No such reference " + reference)
          .withStatus(HttpStatus.NOT_FOUND);
    }
    String url =
        urlOverride != null && !urlOverride.isBlank()
            ? urlOverride
            : scenarios.get(partner).callbackUrl();
    if (url == null || url.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "No callbackUrl in the request or the scenario");
    }
    return client.post(url, body, signer.headers(partner, url, body));
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
