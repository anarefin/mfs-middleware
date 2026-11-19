package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Optional;
import mfs.mw.core.mock.dto.WalletBalance;
import mfs.mw.core.mock.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Test-only seeding (ticket 57): the seeded wallet id is logged masked by the service itself (18.3). */
class WalletSeedServiceTest {

  private static final String WALLET = "01712000001";

  private final WalletRepository wallets = mock(WalletRepository.class);
  private final WalletBalances balances = mock(WalletBalances.class);
  private final WalletSeedService service = new WalletSeedService(wallets, balances, mock(WalletStateEvents.class), mock(mfs.mw.core.mock.repository.LedgerRepository.class), java.time.Clock.systemUTC());

  private final Logger logger = (Logger) LoggerFactory.getLogger(WalletSeedService.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void capture() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void release() {
    logger.detachAppender(logs);
  }

  @Test
  void aNewWalletIsLoggedWithItsIdMasked() {
    when(wallets.resolveId(WALLET)).thenReturn(Optional.empty());
    when(wallets.insertIfAbsent(WALLET, "CUSTOMER", "FULL", 1000, "Rahim Uddin")).thenReturn(true);
    when(balances.find(WALLET))
        .thenReturn(Optional.of(new WalletBalance(WALLET, "CUSTOMER", "ACTIVE", 1000, 0, 1000, "Rahim Uddin")));

    assertThat(service.seed(WALLET, "CUSTOMER", 1000, "Rahim Uddin", null).created()).isTrue();

    assertThat(logs.list).hasSize(1);
    String line = logs.list.get(0).getFormattedMessage();
    assertThat(line).contains("0171****001").doesNotContain(WALLET).doesNotContain("Rahim");
  }

  @Test
  void anExistingWalletOnlyGetsAMissingHolderName() {
    when(wallets.resolveId(WALLET)).thenReturn(Optional.of(WALLET));
    when(balances.find(WALLET))
        .thenReturn(Optional.of(new WalletBalance(WALLET, "CUSTOMER", "ACTIVE", 5, 0, 5, "Rahim Uddin")));

    assertThat(service.seed(WALLET, "CUSTOMER", 1000, "Rahim Uddin", null).created()).isFalse();

    org.mockito.Mockito.verify(wallets).fillHolderName(WALLET, "Rahim Uddin");
    org.mockito.Mockito.verify(wallets, org.mockito.Mockito.never())
        .insertIfAbsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
  }
}
