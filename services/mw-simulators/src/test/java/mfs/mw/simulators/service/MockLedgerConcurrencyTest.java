package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** M9 review Lows: concurrent resends never apply a bank transfer twice, and one bill is never paid twice at once. */
class MockLedgerConcurrencyTest {

  private static <T> List<T> race(int n, Supplier<T> call) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
      for (int i = 0; i < n; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return call.get();
        }));
      }
      start.countDown();
      List<T> out = new ArrayList<>();
      for (Future<T> f : futures) {
        out.add(f.get());
      }
      return out;
    }
  }

  @Test
  void concurrentResendsOfOneTransferApplyItOnce() throws Exception {
    BankGatewayLedger ledger = new BankGatewayLedger();
    BankGatewayLedger.Account account = ledger.account("dbbl", "1234567890").orElseThrow();
    BigDecimal before = account.balance();
    AtomicInteger applied = new AtomicInteger();

    List<BankGatewayLedger.Applied> results = race(16, () -> ledger.applyOnce("dbbl", "TXN-1", () -> {
      applied.incrementAndGet();
      account.credit(new BigDecimal("100.00"));
      return new BankTransfer("dbbl", "TXN-1", "1234567890", new BigDecimal("100.00"), BankTransfer.CREDIT,
          BankTransfer.SUCCESS, "000", "ok", "DBBL-1", null, false);
    }));

    assertThat(applied).hasValue(1);
    assertThat(results).filteredOn(BankGatewayLedger.Applied::fresh).hasSize(1);
    assertThat(account.balance()).isEqualByComparingTo(before.add(new BigDecimal("100.00")));
  }

  @Test
  void twoReferencesPayingOneBillAtOnceOnlyOneSucceeds() throws Exception {
    AggregatorBillLedger ledger = new AggregatorBillLedger();
    AtomicInteger n = new AtomicInteger();

    List<AggregatorBillLedger.Payment> results = race(8, () -> {
      String ref = "REF-" + n.incrementAndGet();
      return ledger.pay("agg3", ref, "DU", "1140000001", "1250.50",
          () -> ledger.bill("agg3", "DU", "1140000001").orElseThrow().paid() ? "PAID" : null, false);
    });

    assertThat(results).filteredOn(AggregatorBillLedger.Payment::succeeded).hasSize(1);
    assertThat(results).filteredOn(p -> !p.succeeded()).allSatisfy(p -> assertThat(p.code()).isEqualTo("PAID"));
  }
}
