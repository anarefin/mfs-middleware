package mfs.mw.simulators.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.simulators.dto.AggregatorProfile;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.springframework.stereotype.Component;

/**
 * The stateful bills and payments of the bill aggregator mocks (ticket 114, FRS MCK-08): seeded per
 * profile from {@code seed/v1/bills/<profile>.csv}. A bill is UNPAID, PAID (by one payment) or REVERSED
 * (the payment undone, payable again); the state survives scenario changes and goes back to the seed only
 * through the party reset ({@code DELETE /mock-control/<profile>/journal}, {@code POST /mock-control/reset}).
 * Payment references keep counting across resets, so no reference is handed out twice.
 */
@Component
public final class AggregatorBillLedger implements PartyState {

  /** One bill; {@code status} UNPAID, PAID or REVERSED. */
  public static final class Bill {
    private final String billerCode;
    private final String billNumber;
    private final BigDecimal amount;
    private final String dueDate;
    private final String billPeriod;
    private volatile String status;

    Bill(String billerCode, String billNumber, BigDecimal amount, String dueDate, String billPeriod, String status) {
      this.billerCode = billerCode;
      this.billNumber = billNumber;
      this.amount = amount;
      this.dueDate = dueDate;
      this.billPeriod = billPeriod;
      this.status = status;
    }

    public String billerCode() {
      return billerCode;
    }

    public String billNumber() {
      return billNumber;
    }

    public BigDecimal amount() {
      return amount;
    }

    public String dueDate() {
      return dueDate;
    }

    public String billPeriod() {
      return billPeriod;
    }

    public String status() {
      return status;
    }

    public boolean paid() {
      return "PAID".equals(status);
    }
  }

  /**
   * One payment by our reference; {@code status} SUCCESS, FAILED or REVERSED, {@code code} the refusal's
   * semantic name (NOT_FOUND, PAID, ...) when FAILED; {@code reversal} the first reverse's reference.
   */
  public record Payment(
      String reference, String billerCode, String billNumber, String amount, String status, String code,
      String paymentRef, String reversalRef, boolean pending) {

    Payment reversed(String reversalRef) {
      return new Payment(reference, billerCode, billNumber, amount, "REVERSED", code, paymentRef, reversalRef, false);
    }

    public boolean succeeded() {
      return "SUCCESS".equals(status);
    }
  }

  private final Map<String, Map<String, Bill>> bills = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Payment>> payments = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  public AggregatorBillLedger() {
    AggregatorProfile.ALL.forEach(p -> reset(p.id()));
  }

  public Optional<Bill> bill(String profile, String billerCode, String billNumber) {
    return Optional.ofNullable(bills.getOrDefault(profile, Map.of()).get(key(billerCode, billNumber)));
  }

  public boolean knowsBiller(String profile, String billerCode) {
    return bills.getOrDefault(profile, Map.of()).values().stream().anyMatch(b -> b.billerCode.equals(billerCode));
  }

  public Optional<Payment> payment(String profile, String reference) {
    return Optional.ofNullable(payments.getOrDefault(profile, Map.of()).get(reference));
  }

  /**
   * Applies a payment once per reference: the bill becomes PAID when {@code outcome} is null (nothing
   * refused it); a repeated reference returns the stored payment unchanged.
   */
  public synchronized Payment pay(String profile, String reference, String billerCode, String billNumber, String amount,
      String refusal, boolean pending) {
    Payment existing = payments.get(profile).get(reference);
    if (existing != null) {
      return existing;
    }
    String paymentRef = profile.toUpperCase(java.util.Locale.ROOT) + "-" + refs.incrementAndGet();
    Payment p;
    if (refusal == null) {
      Bill bill = bill(profile, billerCode, billNumber).orElseThrow();
      bill.status = "PAID";
      p = new Payment(reference, billerCode, billNumber, amount, "SUCCESS", null, paymentRef, null, pending);
    } else {
      p = new Payment(reference, billerCode, billNumber, amount, "FAILED", refusal, paymentRef, null, false);
    }
    payments.get(profile).put(reference, p);
    return p;
  }

  /** Undoes a successful payment once (the bill payable again); a later call returns the first reversal. */
  public synchronized Payment reverse(String profile, String reference) {
    Payment p = payments.get(profile).get(reference);
    if (p == null || !p.succeeded()) {
      return p;
    }
    bill(profile, p.billerCode(), p.billNumber()).ifPresent(b -> b.status = "REVERSED");
    Payment r = p.reversed(profile.toUpperCase(java.util.Locale.ROOT) + "-R" + refs.incrementAndGet());
    payments.get(profile).put(reference, r);
    return r;
  }

  @Override
  public void reset(String party) {
    if (AggregatorProfile.ALL.stream().noneMatch(p -> p.id().equals(party))) {
      return;
    }
    Map<String, Bill> seeded = new ConcurrentHashMap<>();
    for (String[] c : SeedFiles.bills(SeedFiles.VERSION, party)) {
      seeded.put(key(c[0], c[1]), new Bill(c[0], c[1], new BigDecimal(c[2]), c[3], c[4], c[5]));
    }
    bills.put(party, seeded);
    payments.put(party, new ConcurrentHashMap<>());
  }

  private static String key(String billerCode, String billNumber) {
    return billerCode + "|" + billNumber;
  }
}
