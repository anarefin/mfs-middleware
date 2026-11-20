package mfs.mw.simulators.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.simulators.dto.BankAccountSeed;
import mfs.mw.simulators.dto.BankProfile;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.springframework.stereotype.Service;

/**
 * The simulated bank gateway's state per bank (ticket 117d, FRS MCK-02, MCK-08): accounts (seeded from
 * {@code seed/v1/bank-accounts.csv}, others opened on first use), transfers and reversals. The party
 * reset ({@code bank-<code>}) puts that bank back to its seed. In memory.
 */
@Service
public class BankGatewayLedger implements PartyState {

  /** The balance of an account opened on first use. */
  public static final BigDecimal OPENING_BALANCE = new BigDecimal("1000000.00");

  static final String NOT_FOUND_ENDING = "00";
  static final String DORMANT_ENDING = "98";

  /** One account; balance changes are synchronised on it. */
  public static final class Account {
    private final String holderName;
    private final String status;
    private BigDecimal balance;

    Account(String holderName, String status, BigDecimal balance) {
      this.holderName = holderName;
      this.status = status;
      this.balance = balance;
    }

    public String holderName() {
      return holderName;
    }

    public String status() {
      return status;
    }

    public synchronized BigDecimal balance() {
      return balance;
    }

    /** Debits {@code amount} unless the balance is short; false when short. */
    public synchronized boolean debit(BigDecimal amount) {
      if (balance.compareTo(amount) < 0) {
        return false;
      }
      balance = balance.subtract(amount);
      return true;
    }

    public synchronized void credit(BigDecimal amount) {
      balance = balance.add(amount);
    }
  }

  private final List<BankAccountSeed> seeds = SeedFiles.bankAccounts(SeedFiles.VERSION);
  private final Map<String, Account> accounts = new ConcurrentHashMap<>();
  private final Map<String, BankTransfer> transfers = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> reversals = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  private static String key(String bank, String id) {
    return bank + "|" + id;
  }

  /**
   * The account: the seeded one, else opened on first use (ACTIVE, "Test Customer"; ending {@value
   * #DORMANT_ENDING} DORMANT); empty for a number ending {@value #NOT_FOUND_ENDING} (no such account).
   */
  public Optional<Account> account(String bank, String number) {
    Optional<BankAccountSeed> seed = seeds.stream()
        .filter(s -> s.accountNumber().equals(number) && (s.bank().equals(bank) || "*".equals(s.bank())))
        .reduce((wildcard, own) -> own.bank().equals(bank) ? own : wildcard);
    if (seed.isEmpty() && number.endsWith(NOT_FOUND_ENDING)) {
      return Optional.empty();
    }
    return Optional.of(accounts.computeIfAbsent(key(bank, number), k -> seed
        .map(s -> new Account(s.holderName(), s.status(), s.balance()))
        .orElseGet(() -> new Account("Test Customer", number.endsWith(DORMANT_ENDING) ? "DORMANT" : "ACTIVE",
            OPENING_BALANCE))));
  }

  /** Stores the transfer unless one exists for its id; returns the stored one (first writer wins). */
  public BankTransfer putIfAbsent(BankTransfer t) {
    BankTransfer prior = transfers.putIfAbsent(key(t.bank(), t.transactionId()), t);
    return prior != null ? prior : t;
  }

  /** A transfer and whether this call applied it (false: an earlier one did). */
  public record Applied(BankTransfer transfer, boolean fresh) {}

  /**
   * Applies a transfer once per (bank, transactionId), atomically (M9 review): {@code apply} (which
   * moves the balance) runs at most once even for concurrent resends; the others get its record.
   */
  public Applied applyOnce(String bank, String transactionId, java.util.function.Supplier<BankTransfer> apply) {
    boolean[] fresh = {false};
    BankTransfer t = transfers.computeIfAbsent(key(bank, transactionId), k -> {
      fresh[0] = true;
      return apply.get();
    });
    return new Applied(t, fresh[0]);
  }

  public Optional<BankTransfer> find(String bank, String transactionId) {
    return Optional.ofNullable(transfers.get(key(bank, transactionId)));
  }

  public Optional<BankTransfer> findByParty(String party, String transactionId) {
    return party.startsWith(BankProfile.PARTY_PREFIX)
        ? find(party.substring(BankProfile.PARTY_PREFIX.length()), transactionId)
        : Optional.empty();
  }

  /** The first reversal of a transfer, or the one {@code create} makes now (once per transfer). */
  public Map<String, Object> reversal(String bank, String transactionId,
      java.util.function.Supplier<Map<String, Object>> create) {
    return reversals.computeIfAbsent(key(bank, transactionId), k -> new LinkedHashMap<>(create.get()));
  }

  public String nextRef(String bank) {
    return bank.toUpperCase(Locale.ROOT) + "-" + refs.incrementAndGet();
  }

  @Override
  public void reset(String party) {
    if (!party.startsWith(BankProfile.PARTY_PREFIX)) {
      return;
    }
    String prefix = party.substring(BankProfile.PARTY_PREFIX.length()) + "|";
    accounts.keySet().removeIf(k -> k.startsWith(prefix));
    transfers.keySet().removeIf(k -> k.startsWith(prefix));
    reversals.keySet().removeIf(k -> k.startsWith(prefix));
  }
}
