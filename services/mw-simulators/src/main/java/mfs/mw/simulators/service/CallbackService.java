package mfs.mw.simulators.service;

import java.util.concurrent.Executor;
import mfs.mw.simulators.client.CallbackClient;
import mfs.mw.simulators.client.CallbackSigner;
import mfs.mw.simulators.dto.BankProfile;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.mapper.BankDebitMapper;
import mfs.mw.simulators.mapper.BillAggregatorMapper;
import mfs.mw.simulators.mapper.OcsMapper;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Duplicate, late, out-of-order and on-demand callbacks (spec 22.2) for the OCS and the bill
 * aggregator. The callback body is the partner's own reply for the reference (billagg: {@code
 * {transactionId, status, code, message, paymentRef}}), posted to the party's mock-control
 * {@code callbackUrl} as the call's {@link Decision} says (once, twice, late, or followed by a stale
 * PENDING) and signed when the partner has a secret ({@link CallbackSigner}; signed at send
 * time, so a late callback carries a fresh timestamp). Sent off the request thread.
 */
@Service
public class CallbackService {

  private static final String OCS = "ocs";

  private final OcsLedger ledger;
  private final BillPaymentLedger payments;
  private final MockControl control;
  private final CallbackClient client;
  private final CallbackSigner signer;
  private final OcsMapper mapper;
  private final BillAggregatorMapper billMapper;
  private final BankDebitLedger debits;
  private final BankDebitMapper bankMapper;
  private final JsonMapper json;
  private final Executor executor;
  private final BankGatewayLedger bankTransfers;

  @SuppressWarnings("java:S107")
  public CallbackService(
      OcsLedger ledger,
      BillPaymentLedger payments,
      MockControl control,
      CallbackClient client,
      CallbackSigner signer,
      OcsMapper mapper,
      BillAggregatorMapper billMapper,
      BankDebitLedger debits,
      BankDebitMapper bankMapper,
      JsonMapper json,
      Executor callbackExecutor,
      BankGatewayLedger bankTransfers) {
    this.ledger = ledger;
    this.payments = payments;
    this.control = control;
    this.client = client;
    this.signer = signer;
    this.mapper = mapper;
    this.billMapper = billMapper;
    this.debits = debits;
    this.bankMapper = bankMapper;
    this.json = json;
    this.executor = callbackExecutor;
    this.bankTransfers = bankTransfers;
  }

  /**
   * Sends what the current c2wbank scenario asks for after a debit was applied (ticket 23);
   * OUT_OF_ORDER follows the outcome with a stale PENDING.
   */
  public void afterDebit(BankDebitRecord record, Decision d) {
    send(
        BankC2wService.PARTNER,
        d,
        json.writeValueAsString(bankMapper.toResponse(record)),
        json.writeValueAsString(bankMapper.pending(record)));
  }

  /** Sends what the current scenario asks for after a top-up was applied. */
  public void afterTopup(TopupRecord record, Decision d) {
    send(OCS, d, json.writeValueAsString(mapper.toResponse(record)), null);
  }

  /**
   * Sends what the current billagg scenario asks for after a payment was applied; OUT_OF_ORDER
   * follows the outcome with a stale PENDING.
   */
  public void afterPayment(BillPaymentRecord record, Decision d) {
    send(
        BillAggregatorService.PARTNER,
        d,
        json.writeValueAsString(billMapper.toResponse(record)),
        json.writeValueAsString(billMapper.pending(record)));
  }

  /**
   * Sends what the current scenario of a bank gateway party ({@code bank-<code>}, ticket 117d) asks for after
   * a transfer was applied; OUT_OF_ORDER follows the outcome with a stale PENDING.
   */
  public void afterBankTransfer(String party, Decision d, String body, String staleBody) {
    send(party, d, body, staleBody);
  }

  /** Sends the card acquirer's 3-D Secure webhook (ticket 118) as the current scenario asks. */
  public void afterCardAuthentication(String party, Decision d, String body) {
    send(party, d, body, null);
  }

  private void send(String partner, Decision d, String body, String staleBody) {
    String url = control.settings(partner).callbackUrl();
    if (d.callback() == CallbackMode.NONE || url == null || url.isBlank()) {
      return;
    }
    int copies = d.callback() == CallbackMode.DUPLICATE ? 2 : 1;
    long delay = d.callback() == CallbackMode.LATE ? d.callbackDelayMs() : 0;
    boolean stale = d.callback() == CallbackMode.OUT_OF_ORDER && staleBody != null;
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
    MockParties.require(partner);
    String body =
        switch (partner) {
          case BillAggregatorService.PARTNER ->
              payments.find(reference).map(r -> json.writeValueAsString(billMapper.toResponse(r))).orElse(null);
          case BankC2wService.PARTNER ->
              debits.find(reference).map(r -> json.writeValueAsString(bankMapper.toResponse(r))).orElse(null);
          default -> partner.startsWith(BankProfile.PARTY_PREFIX)
              ? bankTransfers.findByParty(partner, reference).map(t -> json.writeValueAsString(t.body())).orElse(null)
              : ledger.find(reference).map(r -> json.writeValueAsString(mapper.toResponse(r))).orElse(null);
        };
    if (body == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "No such reference " + reference)
          .withStatus(HttpStatus.NOT_FOUND);
    }
    String url =
        urlOverride != null && !urlOverride.isBlank()
            ? urlOverride
            : control.settings(partner).callbackUrl();
    if (url == null || url.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "No callbackUrl in the request or the party settings");
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
