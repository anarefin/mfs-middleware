package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Optional;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.WalletBalance;
import mfs.mw.core.mock.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Test-only seeding (ticket 57): the seeded wallet id is logged masked by the service itself (18.3). */
class WalletSeedServiceTest {

  private static final String WALLET = "01712000001";

  private final WalletRepository wallets = mock(WalletRepository.class);
  private final WalletBalances balances = mock(WalletBalances.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final CoreMockProperties props = mock(CoreMockProperties.class);
  private final WalletSeedService service = new WalletSeedService(wallets, balances, encoder, props);

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
    when(props.seed()).thenReturn(new CoreMockProperties.Seed(false, "fake-pin", 0, 0, 0, 0));
    when(encoder.encode(anyString())).thenReturn("hash");
    when(wallets.resolveId(WALLET)).thenReturn(Optional.empty());
    when(wallets.insertIfAbsent(WALLET, "CUSTOMER", 1000, "hash")).thenReturn(true);
    when(balances.find(WALLET))
        .thenReturn(Optional.of(new WalletBalance(WALLET, "CUSTOMER", "ACTIVE", 1000, 0, 1000)));

    assertThat(service.seed(WALLET, "CUSTOMER", 1000).created()).isTrue();

    assertThat(logs.list).hasSize(1);
    String line = logs.list.get(0).getFormattedMessage();
    assertThat(line).contains("0171****001").doesNotContain(WALLET);
  }
}
