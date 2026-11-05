package mfs.mw.core.mock.service;

/** Why the core refuses a request; the code travels as {@code reasonCode} (mapped by the orchestrator). */
enum Reason {
  WALLET_NOT_FOUND("Debit wallet not found"),
  CREDIT_WALLET_NOT_FOUND("Credit wallet not found"),
  ACCOUNT_BLOCKED("Debit wallet is not active"),
  ACCOUNT_LOCKED("Debit wallet is locked after too many wrong PINs"),
  INVALID_COUNTERPARTY("Wallet types do not fit the transaction type"),
  CREDIT_ACCOUNT_BLOCKED("Credit wallet is not active"),
  SAME_WALLET("Debit and credit wallet are the same"),
  INVALID_PIN("Wrong PIN"),
  INVALID_PIN_BLOCK("PIN block cannot be decrypted"),
  INVALID_AMOUNT("Amount must be positive BDT"),
  LIMIT_EXCEEDED_PER_TXN("Per-transaction limit exceeded"),
  LIMIT_EXCEEDED_DAILY("Daily limit exceeded"),
  INSUFFICIENT_BALANCE("Balance does not cover amount and fee"),
  HOLD_NOT_FOUND("Hold not found"),
  HOLD_RELEASED("Hold was already released"),
  HOLD_ALREADY_COMMITTED("Hold was already committed"),
  ORIGINAL_NOT_FOUND("No posted transaction with that id"),
  ORIGINAL_NOT_REVERSIBLE("A reversal cannot be reversed"),
  ALREADY_REVERSED("Transaction was already reversed"),
  AMOUNT_MISMATCH("Amount differs from the original transfer");

  final String message;

  Reason(String message) {
    this.message = message;
  }
}
