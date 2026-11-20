package mfs.mw.app.gateway.service;

import java.util.UUID;
import mfs.mw.app.gateway.client.BankAccountClient;
import mfs.mw.app.gateway.client.CoreRefusedException;
import mfs.mw.app.gateway.client.CoreWalletClient;
import mfs.mw.app.gateway.repository.AvlAttemptStore;
import mfs.mw.app.gateway.config.LinkedAccountProperties;
import mfs.mw.app.gateway.dto.BankAccountValidation;
import mfs.mw.app.gateway.dto.LinkedBankAccount;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.repository.LinkedBankAccountRepository;
import mfs.mw.app.gateway.util.AccountCipher;
import mfs.mw.app.gateway.util.MaskingPolicy;
import mfs.mw.app.gateway.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Linked bank accounts of one wallet (ticket 117b, FRS FL-08, UC-14). Linking runs the bank's
 * account validation (AVL) through mw-adapter-bank first; only a valid account is stored, its number
 * encrypted ({@link AccountCipher}, wallet id as associated data) with a keyed lookup hash and the
 * last 4 digits, the holder name as its first initial. An AVL refusal links nothing and passes the
 * adapter's code through. Every call is scoped to the wallet.
 *
 * <p>Owner binding (M9 review, FL-08): the account is linked only when the bank's holder is the
 * wallet's owner. The KYC name comes from the core (WAL-01, {@link CoreWalletClient}) and goes to the
 * AVL as {@code matchName}; mw-adapter-bank compares it with the bank's whole holder name
 * (normalised, honorifics dropped) and answers {@code nameMatch}. Anything but {@code true} (no KYC
 * name, no answer to the check, someone else) is 422 {@code MW-VAL-ACCOUNT_HOLDER_MISMATCH} and
 * nothing is linked. AVL attempts are counted per wallet in Redis ({@link AvlAttemptStore},
 * {@code avl-attempts} per {@code avl-window}, default 5 per hour, refused attempts included); past the
 * limit 429 {@code MW-SYS-RATE_LIMITED}, nothing asked. No name is ever logged.
 */
@Service
public class LinkedAccountService {

  private static final Logger log = LoggerFactory.getLogger(LinkedAccountService.class);

  private final BankAccountClient bank;
  private final CoreWalletClient core;
  private final LinkedBankAccountRepository accounts;
  private final AvlAttemptStore attempts;
  private final AccountCipher cipher;
  private final LinkedAccountProperties props;

  public LinkedAccountService(
      BankAccountClient bank, CoreWalletClient core, LinkedBankAccountRepository accounts, AvlAttemptStore attempts,
      LinkedAccountProperties props) {
    this.bank = bank;
    this.core = core;
    this.accounts = accounts;
    this.attempts = attempts;
    this.cipher = new AccountCipher(props.encryptionKey());
    this.props = props;
  }

  public LinkedBankAccount link(String walletId, String bankCode, String accountNumber) {
    if (accounts.count(walletId) >= props.maxPerWallet()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Too many linked bank accounts");
    }
    if (!attempts.tryAcquire(walletId, props.avlAttempts(), props.avlWindow())) {
      log.atWarn()
          .addKeyValue("event", "bank.account.avl_rate_limited")
          .addKeyValue("bankCode", bankCode)
          .log("too many bank account validations for one wallet");
      throw new MwException(ErrorCodes.SYS_RATE_LIMITED, "Too many bank account checks, try again later")
          .withStatus(HttpStatus.TOO_MANY_REQUESTS)
          .withRetryable(true)
          .withMessageBn("অনেকবার ব্যাংক হিসাব যাচাই করা হয়েছে, পরে আবার চেষ্টা করুন");
    }
    String kycName = kycName(walletId);
    if (kycName == null || kycName.isBlank()) {
      throw holderMismatch(bankCode);
    }
    BankAccountValidation valid = bank.validate(bankCode, accountNumber, kycName);
    if (!Boolean.TRUE.equals(valid.nameMatch())) {
      throw holderMismatch(bankCode);
    }
    String last4 = accountNumber.substring(accountNumber.length() - 4);
    String holder = MaskingPolicy.mask(SensitiveType.NAME, valid.holderName());
    try {
      LinkedBankAccount linked = accounts.insert(
          walletId, bankCode, cipher.encrypt(accountNumber, walletId), cipher.lookup(bankCode, accountNumber), last4, holder);
      log.atInfo()
          .addKeyValue("event", "bank.account.linked")
          .addKeyValue("bankCode", bankCode)
          .log("bank account linked");
      return linked;
    } catch (DuplicateKeyException e) {
      throw new MwException(ErrorCodes.IDEM_CONFLICT, "Already linked");
    }
  }

  private String kycName(String walletId) {
    try {
      return core.account(walletId).holderName();
    } catch (CoreRefusedException e) {
      if (e.status() == 404) {
        return null;
      }
      throw coreUnavailable(e);
    } catch (RuntimeException e) {
      throw coreUnavailable(e);
    }
  }

  private static MwException coreUnavailable(RuntimeException e) {
    return new MwException(ErrorCodes.SYS_CORE_UNAVAILABLE, "Service temporarily unavailable, try again", e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }

  private static MwException holderMismatch(String bankCode) {
    log.atInfo()
        .addKeyValue("event", "bank.account.holder_mismatch")
        .addKeyValue("bankCode", bankCode)
        .log("bank account holder is not the wallet owner; not linked");
    return new MwException(ErrorCodes.VAL_ACCOUNT_HOLDER_MISMATCH,
            "The bank account holder's name does not match your wallet's name")
        .withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .withMessageBn("ব্যাংক হিসাবধারীর নাম আপনার ওয়ালেটের নামের সাথে মেলে না");
  }

  public LinkedBankAccount.Page list(String walletId) {
    return new LinkedBankAccount.Page(accounts.list(walletId));
  }

  /**
   * The account behind {@code id} of this wallet, decrypted for a payment (ticket 117c: W2B and
   * ADD_MONEY_BANK name a linked account, never a number); empty when it is not this wallet's. The
   * number leaves only towards the orchestrator; it is never logged ({@link Resolved#toString}).
   */
  public java.util.Optional<Resolved> resolve(String walletId, String id) {
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException e) {
      return java.util.Optional.empty();
    }
    return accounts.find(walletId, uuid)
        .map(s -> new Resolved(id, s.bankCode(), cipher.decrypt(s.ciphertext(), walletId), s.last4()));
  }

  /** A linked account ready to pay: its bank's profile is {@code bank-<bankCode>}. */
  public record Resolved(String id, String bankCode, String accountNumber, String last4) {

    public String partnerId() {
      return "bank-" + bankCode;
    }

    @Override
    public String toString() {
      return "Resolved[id=" + id + ", bankCode=" + bankCode + ", account=****" + last4 + "]";
    }
  }

  public void unlink(String walletId, String id) {
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Not an id");
    }
    if (!accounts.delete(walletId, uuid)) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
    }
  }
}
