package mfs.mw.core.mock.service;

/** Why the core refuses a request; the code travels as {@code reasonCode} (mapped by the orchestrator). */
enum Reason {
  WALLET_NOT_FOUND("Debit wallet not found"),
  CREDIT_WALLET_NOT_FOUND("Credit wallet not found"),
  ACCOUNT_BLOCKED("Debit wallet is not active"),
  ACCOUNT_LOCKED("Debit wallet is locked by the core"),
  INVALID_COUNTERPARTY("Wallet types do not fit the transaction type"),
  CREDIT_ACCOUNT_BLOCKED("Credit wallet is not active"),
  SAME_WALLET("Debit and credit wallet are the same"),
  INVALID_AMOUNT("Amount must be positive BDT"),
  LIMIT_EXCEEDED_PER_TXN("Per-transaction limit exceeded"),
  LIMIT_EXCEEDED_DAILY("Daily limit exceeded"),
  // the limit sheet (ticket 94, FRS 7.1)
  LIMIT_BELOW_MIN_PER_TXN("Amount is below the per-transaction minimum"),
  LIMIT_EXCEEDED_DAILY_COUNT("Daily transaction count exceeded"),
  LIMIT_EXCEEDED_MONTHLY("Monthly limit exceeded"),
  LIMIT_EXCEEDED_MONTHLY_COUNT("Monthly transaction count exceeded"),
  // account statuses (ticket 94, FRS 7.1)
  ACCOUNT_DORMANT("Debit wallet is dormant"),
  ACCOUNT_CLOSED("Debit wallet is closed"),
  INSUFFICIENT_BALANCE("Balance does not cover amount and fee"),
  // a hold or credit naming a settlement wallet the core does not have (ticket 94)
  SETTLEMENT_WALLET_NOT_FOUND("Settlement wallet not found"),
  HOLD_NOT_FOUND("Hold not found"),
  HOLD_RELEASED("Hold was already released"),
  // the hold ran out before it was committed and was released by the core (ticket 94, FRS 7.3)
  HOLD_EXPIRED("Hold expired and was released"),
  HOLD_ALREADY_COMMITTED("Hold was already committed"),
  ORIGINAL_NOT_FOUND("No posted transaction with that id"),
  ORIGINAL_NOT_REVERSIBLE("A reversal cannot be reversed"),
  ALREADY_REVERSED("Transaction was already reversed"),
  AMOUNT_MISMATCH("Amount differs from the original transfer"),
  WALLET_NOT_LOCKED("Wallet is not locked"),
  // VerifyCustomerFactors (ticket 70)
  FACTORS_MISMATCH("KYC factors do not match"),
  WALLET_NOT_ACTIVE("Wallet is not active"),
  /**
   * mw-auth's transaction assertion was refused (ticket 71: signature, audience, expiry, hash,
   * wallet or a jti used by another transaction); travels as mw-auth's code MW-AUTH-046.
   */
  ASSERTION_INVALID("MW-AUTH-046", "Transaction authorisation invalid or already used");

  final String message;
  private final String code;

  Reason(String message) {
    this(null, message);
  }

  Reason(String code, String message) {
    this.code = code;
    this.message = message;
  }

  /** The {@code reasonCode} of the failure event: the name, except the assertion's MW-AUTH-046. */
  String code() {
    return code != null ? code : name();
  }
}
