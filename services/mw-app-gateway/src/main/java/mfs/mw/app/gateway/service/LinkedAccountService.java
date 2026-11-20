package mfs.mw.app.gateway.service;

import java.util.UUID;
import mfs.mw.app.gateway.client.BankAccountClient;
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
 */
@Service
public class LinkedAccountService {

  private static final Logger log = LoggerFactory.getLogger(LinkedAccountService.class);

  private final BankAccountClient bank;
  private final LinkedBankAccountRepository accounts;
  private final AccountCipher cipher;
  private final int maxPerWallet;

  public LinkedAccountService(
      BankAccountClient bank, LinkedBankAccountRepository accounts, LinkedAccountProperties props) {
    this.bank = bank;
    this.accounts = accounts;
    this.cipher = new AccountCipher(props.encryptionKey());
    this.maxPerWallet = props.maxPerWallet();
  }

  public LinkedBankAccount link(String walletId, String bankCode, String accountNumber) {
    if (accounts.count(walletId) >= maxPerWallet) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Too many linked bank accounts");
    }
    BankAccountValidation valid = bank.validate(bankCode, accountNumber);
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

  public LinkedBankAccount.Page list(String walletId) {
    return new LinkedBankAccount.Page(accounts.list(walletId));
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
