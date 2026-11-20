package mfs.mw.core.mock.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import mfs.mw.core.mock.dto.AccountActionResult;
import mfs.mw.core.mock.dto.AccountUnlockRequest;
import mfs.mw.core.mock.dto.AccountView;
import mfs.mw.core.mock.dto.EmoneyTotalView;
import mfs.mw.core.mock.dto.FactorsRequest;
import mfs.mw.core.mock.dto.BalanceView;
import mfs.mw.core.mock.dto.ChargesRequest;
import mfs.mw.core.mock.dto.ChargesView;
import mfs.mw.core.mock.dto.HoldActionRequest;
import mfs.mw.core.mock.dto.KycLevelRequest;
import mfs.mw.core.mock.dto.KycLevelResult;
import mfs.mw.core.mock.dto.LimitsRequest;
import mfs.mw.core.mock.dto.LimitsView;
import mfs.mw.core.mock.dto.OperationResult;
import mfs.mw.core.mock.dto.ReversalRequest;
import mfs.mw.core.mock.dto.StatementView;
import mfs.mw.core.mock.dto.TransactionStatusView;
import mfs.mw.core.mock.dto.ValidationResult;
import mfs.mw.core.mock.dto.WalletCreditRequest;
import mfs.mw.core.mock.dto.WalletTransferRequest;
import mfs.mw.core.mock.security.TxnAssertionHeader;
import mfs.mw.core.mock.service.CoreWalletService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The core wallet over REST (FRS 7.2 WAL-01, WAL-03..13, ADR D31, ticket 93; contract {@code
 * api/mw-core-mock/core-wallet.v1.openapi.yaml}). Mapping and validation only: the rules are the
 * core's ({@link CoreWalletService}, the same code as the Kafka commands). Service token checked by
 * {@code config/SecurityConfig}; the assertion header's presence by {@link TxnAssertionHeader}.
 */
@RestController
@RequestMapping("/core/v1")
public class CoreWalletController {

  private final CoreWalletService wallet;

  public CoreWalletController(CoreWalletService wallet) {
    this.wallet = wallet;
  }

  @GetMapping("/accounts/{msisdn}")
  AccountView account(@PathVariable @Size(min = 1, max = 64) String msisdn) {
    return wallet.account(msisdn);
  }

  /**
   * WAL-03; a customer's own read (the orchestrator's BALANCE check, ticket 95a) carries the txnId, the
   * assertion and the idempotency key it was signed over.
   */
  @GetMapping("/balance/{walletId}")
  BalanceView balance(
      @PathVariable @Size(min = 1, max = 64) String walletId,
      @RequestParam(required = false) @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId,
      @RequestHeader(name = TxnAssertionHeader.NAME, required = false) @Size(max = 4096) String assertion,
      @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 64) String idempotencyKey,
      @AuthenticationPrincipal Jwt caller) {
    return wallet.balance(walletId, new CoreWalletService.BalanceRead(
        caller == null ? null : caller.getClaimAsString("azp"), txnId, assertion, idempotencyKey));
  }

  /** ValidateTransaction over REST (ticket 95a): the transfer's rules, nothing moved. */
  @PostMapping("/transactions/validate")
  ValidationResult validate(
      @Valid @RequestBody WalletTransferRequest req,
      @RequestHeader(name = TxnAssertionHeader.NAME, required = false) String assertion) {
    return wallet.validate(req, TxnAssertionHeader.require(req, assertion));
  }

  @PostMapping("/charges/calculate")
  ChargesView charges(@Valid @RequestBody ChargesRequest req) {
    return wallet.charges(req);
  }

  @PostMapping("/limits/check")
  LimitsView limits(@Valid @RequestBody LimitsRequest req) {
    return wallet.limits(req);
  }

  @PostMapping("/holds")
  OperationResult hold(
      @Valid @RequestBody WalletTransferRequest req,
      @RequestHeader(name = TxnAssertionHeader.NAME, required = false) String assertion) {
    return wallet.hold(req, TxnAssertionHeader.require(req, assertion));
  }

  @PostMapping("/holds/{holdId}/commit")
  OperationResult commit(
      @PathVariable @Size(min = 1, max = 64) String holdId, @Valid @RequestBody(required = false) HoldActionRequest req) {
    return wallet.commit(holdId, req == null ? HoldActionRequest.NONE : req);
  }

  @PostMapping("/holds/{holdId}/release")
  OperationResult release(
      @PathVariable @Size(min = 1, max = 64) String holdId, @Valid @RequestBody(required = false) HoldActionRequest req) {
    return wallet.release(holdId, req == null ? HoldActionRequest.NONE : req);
  }

  @PostMapping("/transfers")
  OperationResult transfer(
      @Valid @RequestBody WalletTransferRequest req,
      @RequestHeader(name = TxnAssertionHeader.NAME, required = false) String assertion) {
    return wallet.transfer(req, TxnAssertionHeader.require(req, assertion));
  }

  @PostMapping("/credits")
  OperationResult credit(@Valid @RequestBody WalletCreditRequest req) {
    return wallet.credit(req);
  }

  @PostMapping("/reversals")
  OperationResult reverse(@Valid @RequestBody ReversalRequest req) {
    return wallet.reverse(req);
  }

  /** REC-07 e-money total (ticket 127c): only mw-recon ({@code config/SecurityConfig}). */
  @GetMapping("/reports/emoney-total")
  EmoneyTotalView emoneyTotal() {
    return wallet.emoneyTotal();
  }

  /** UnlockWallet over REST (ticket 95b): only mw-admin-api ({@code config/SecurityConfig}). */
  @PostMapping("/accounts/{walletId}/unlock")
  AccountActionResult unlock(
      @PathVariable @Size(min = 1, max = 64) String walletId, @Valid @RequestBody AccountUnlockRequest req) {
    return wallet.unlock(walletId, req);
  }

  /** The KYC level after an eKYC match (ticket 124b): only mw-compliance ({@code config/SecurityConfig}). */
  @PutMapping("/accounts/{walletId}/kyc-level")
  KycLevelResult kycLevel(
      @PathVariable @Size(min = 1, max = 64) String walletId, @Valid @RequestBody KycLevelRequest req) {
    return wallet.setKycLevel(walletId, req);
  }

  /** VerifyCustomerFactors over REST (ticket 95b): only mw-auth ({@code config/SecurityConfig}). */
  @PostMapping("/customers/{walletId}/verify-factors")
  AccountActionResult verifyFactors(
      @PathVariable @Size(min = 1, max = 64) String walletId, @Valid @RequestBody FactorsRequest req) {
    return wallet.verifyFactors(walletId, req);
  }

  @GetMapping("/statements")
  StatementView statement(
      @RequestParam @Size(min = 1, max = 64) String walletId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page) {
    return wallet.statement(walletId, from, to, limit, page);
  }

  @GetMapping("/transactions/{txnId}")
  TransactionStatusView transaction(@PathVariable @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId) {
    return wallet.transaction(txnId);
  }
}
