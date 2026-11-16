package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.BalanceRequest;
import mfs.mw.app.gateway.dto.BalanceView;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TxnRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Balance check of the token's own wallet (ADR D26, {@code POST /app/v1/balance}): any app role.
 * Authorised like a submit (mw-auth's assertion for a BALANCE of amount 0, payer = payee = the
 * wallet; its refusal passes through and nothing is published), published on {@code
 * mw.txn.request.v1} and answered from the COMPLETED status's {@code balance} within the reply
 * budget. Nothing is stored (no {@code app_transaction} row) and the balance is never logged; with
 * nothing to finish later there is no 202: no answer in time is 504 MW-TMO-001 (retryable, with a
 * new key).
 */
@Service
public class BalanceService {

  private static final Logger log = LoggerFactory.getLogger(BalanceService.class);

  private final TxnReplyExchange exchange;
  private final RequestMapper mapper;
  private final Clock clock;
  private final AuthClient auth;

  public BalanceService(
      EventPublisher publisher,
      ReplyWaiters waiters,
      RequestMapper mapper,
      AppGatewayProperties props,
      Clock clock,
      AuthClient auth) {
    this.exchange = new TxnReplyExchange(publisher, waiters, props);
    this.mapper = mapper;
    this.clock = clock;
    this.auth = auth;
  }

  public BalanceView check(AppUser user, String idempotencyKey, BalanceRequest body) {
    Instant now = clock.instant();
    String txnId = UuidV7.next(clock).toString();
    TxnRequest unauthorised =
        mapper.toBalanceRequest(user, idempotencyKey, body.locale(), txnId, TxnReplyExchange.correlationId(), now);
    TxnRequest req = unauthorised.withAssertion(
        auth.authorise(mapper.toAuthorisation(user, user.deviceId(), unauthorised, body.encryptedPin())).assertion());

    CompletableFuture<StatusMessage> answer = exchange.publish(req);
    log.atInfo()
        .addKeyValue("event", "balance.request.published")
        .addKeyValue("txnId", txnId)
        .addKeyValue("channel", user.channel())
        .log("balance request published");

    StatusMessage status = exchange.await(answer, txnId);
    if (status == null) {
      throw noAnswer(txnId);
    }
    TxnReplyExchange.throwIfRefused(status);
    if (!"COMPLETED".equals(status.status()) || status.balance() == null) {
      // a repeated key whose check is still running, or answered without the (never stored) balance
      throw noAnswer(status.txnId());
    }
    return new BalanceView(status.txnId(), status.balance().available(), status.balance().asOf());
  }

  private static MwException noAnswer(String txnId) {
    return new MwException(ErrorCodes.TMO_NO_ANSWER, "The balance is not known in time, try again with a new key")
        .withTxnId(txnId)
        .withRetryable(true);
  }
}
