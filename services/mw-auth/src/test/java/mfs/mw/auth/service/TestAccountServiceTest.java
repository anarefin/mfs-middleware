package mfs.mw.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import mfs.mw.auth.config.DeviceBindingProperties;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.dto.TestAccount;
import mfs.mw.auth.entity.AuthDevice;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.entity.DeviceOtp;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.ChannelOtpRepository;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.DeviceOtpRepository;
import mfs.mw.auth.repository.DeviceRepository;
import mfs.mw.auth.repository.DeviceSessionStore;
import mfs.mw.auth.repository.RefreshTokenRepository;
import mfs.mw.auth.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** The dev-only account test API's logic (repositories, mw-crypto and Redis are mocks). */
class TestAccountServiceTest {

  static final String WALLET = "01711000001";
  static final Instant NOW = Instant.parse("2026-11-15T06:00:00Z");

  final WalletRepository wallets = mock(WalletRepository.class);
  final CredentialRepository credentials = mock(CredentialRepository.class);
  final PinVerifier verifier = mock(PinVerifier.class);
  final PinService pins = mock(PinService.class);
  final DeviceRepository devices = mock(DeviceRepository.class);
  final DeviceOtpRepository deviceOtps = mock(DeviceOtpRepository.class);
  final ChannelOtpRepository channelOtps = mock(ChannelOtpRepository.class);
  final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
  final DeviceSessionStore sessions = mock(DeviceSessionStore.class);
  final TransactionTemplate tx = mock(TransactionTemplate.class);
  final DeviceBindingProperties binding = new DeviceBindingProperties(Duration.ofMinutes(5), Duration.ofMinutes(5),
      Map.of(), new DeviceBindingProperties.Otp(Duration.ofMinutes(2), 3, 5, Duration.ofMinutes(15)), "mw.notify.request.v1");

  TestAccountService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    service = new TestAccountService(wallets, credentials, verifier, pins, devices, deviceOtps, channelOtps, refresh,
        sessions, binding, tx, Clock.fixed(NOW, ZoneOffset.UTC));
    when(wallets.find(WALLET)).thenReturn(Optional.of(
        new AuthWallet(WALLET, "tok", "tokenise-msisdn.v1", "{}", "CUSTOMER", "ACTIVE", true, null, 1)));
    doAnswer(inv -> {
      ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
      return null;
    }).when(tx).executeWithoutResult(any());
    when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    when(devices.findByWallet(WALLET)).thenReturn(List.of());
    when(credentials.lock(anyString(), anyString())).thenReturn(Optional.empty());
    when(credentials.find(WALLET)).thenReturn(Optional.empty());
    when(deviceOtps.find(WALLET)).thenReturn(Optional.empty());
  }

  @Test
  void aPinLockInForceIsShownWithItsEnd() {
    Instant until = NOW.plusSeconds(60);
    when(credentials.lock(WALLET, PinVerifier.PIN))
        .thenReturn(Optional.of(new CredentialRepository.Lock("PIN", 1, until, NOW.minusSeconds(1))));
    when(credentials.find(WALLET)).thenReturn(Optional.of(new PinHmac("h", "pin-hmac.v1")));
    when(credentials.failures(WALLET)).thenReturn(0);
    when(deviceOtps.find(WALLET)).thenReturn(Optional.of(
        new DeviceOtp(WALLET, "dev-1", "hash", "en", NOW.plusSeconds(100), 3, 2, "LOGIN")));
    when(channelOtps.quotaSends(WALLET)).thenReturn(4);
    when(devices.findByWallet(WALLET)).thenReturn(List.of(
        new AuthDevice("dev-2", WALLET, "pk", NOW, null, 2), new AuthDevice("dev-1", WALLET, "pk", NOW.minusSeconds(9), NOW, 1)));

    TestAccount a = service.view(WALLET);

    assertThat(a.pinSet()).isTrue();
    assertThat(a.locked()).isTrue();
    assertThat(a.lockedUntil()).isEqualTo(until);
    assertThat(a.pinLock()).isEqualTo(new TestAccount.PinLock(1, until, true));
    assertThat(a.adminLocked()).isFalse();
    assertThat(a.deviceOtp()).isEqualTo(new TestAccount.DeviceOtp("LOGIN", 2, 3, NOW.plusSeconds(100), false));
    assertThat(a.checkoutOtpSends()).isEqualTo(4);
    assertThat(a.devices()).extracting(TestAccount.Device::status).containsExactly("BOUND", "UNBOUND");
  }

  @Test
  void anEndedPinLockIsHistoryNotALock() {
    when(credentials.lock(WALLET, PinVerifier.PIN))
        .thenReturn(Optional.of(new CredentialRepository.Lock("PIN", 2, NOW.minusSeconds(1), NOW.minusSeconds(99))));
    when(credentials.failures(WALLET)).thenReturn(2);

    TestAccount a = service.view(WALLET);

    assertThat(a.locked()).isFalse();
    assertThat(a.lockedUntil()).isNull();
    assertThat(a.pinLock().active()).isFalse();
    assertThat(a.failedPinAttempts()).isEqualTo(2);
  }

  @Test
  void anAdminLockLocksWithoutAnEnd() {
    when(credentials.lock(WALLET, PinVerifier.ADMIN))
        .thenReturn(Optional.of(new CredentialRepository.Lock("ADMIN", 1, null, NOW)));

    TestAccount a = service.view(WALLET);

    assertThat(a.locked()).isTrue();
    assertThat(a.adminLocked()).isTrue();
    assertThat(a.lockedUntil()).isNull();
  }

  @Test
  void anUnknownWalletIsNotFound() {
    when(wallets.find("01700000000")).thenReturn(Optional.empty());
    for (Runnable call : List.<Runnable>of(() -> service.view("01700000000"), () -> service.unlock("01700000000"),
        () -> service.unbind("01700000000"), () -> service.setPin("01700000000", new EncryptedPin("k", "AA")))) {
      assertThatThrownBy(call::run).isInstanceOfSatisfying(MwException.class, e -> {
        assertThat(e.code()).isEqualTo(ErrorCodes.VAL_NOT_FOUND);
        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
      });
    }
    verify(credentials, never()).removeLock(anyString(), anyString());
    verify(devices, never()).unbindAll(anyString(), any());
    verify(pins, never()).derive(anyString(), any(), any());
  }

  @Test
  void unlockClearsEveryLockAndCounter() {
    service.unlock(WALLET);

    verify(credentials).removeLock(WALLET, PinVerifier.ADMIN);
    verify(verifier).clearPinLock(WALLET);
    verify(deviceOtps).delete(WALLET);
    verify(channelOtps).clearQuota(WALLET);
  }

  @Test
  void unbindRevokesTheTokensAndThenClosesTheSessions() {
    when(devices.unbindAll(WALLET, NOW)).thenReturn(List.of("dev-1"));

    service.unbind(WALLET);

    var order = inOrder(devices, refresh, sessions);
    order.verify(devices).unbindAll(WALLET, NOW);
    order.verify(refresh).revokeWallet(WALLET);
    order.verify(sessions).close(WALLET, "dev-1");
  }

  @Test
  void setPinReplacesTheCurrentPinWithTheDerivedHmac() {
    EncryptedPin pin = new EncryptedPin("pin-transport.v1", "AAAA");
    PinHmac hmac = new PinHmac("new", "pin-hmac.v1");
    when(pins.derive(eq(WALLET), eq(pin), isNull())).thenReturn(hmac);

    service.setPin(WALLET, pin);

    verify(credentials).replace(WALLET, hmac, List.of(), NOW);
  }

  @Test
  void aWeakPinIsRefusedAndNothingStored() {
    EncryptedPin pin = new EncryptedPin("pin-transport.v1", "AAAA");
    when(pins.derive(eq(WALLET), eq(pin), isNull())).thenThrow(PinService.weak("REPEATED_DIGIT"));

    assertThatThrownBy(() -> service.setPin(WALLET, pin)).isInstanceOfSatisfying(MwException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCodes.AUTH_WEAK_PIN));
    verify(credentials, never()).replace(anyString(), any(), any(), any());
  }
}
