package mfs.mw.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.contracts.ServiceJson;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.dto.SeedPinsRequest;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.WalletRepository;
import org.junit.jupiter.api.Test;

/**
 * The dev seed PIN call is how the seeder marks a partner user. The mark is stored only for an
 * organisation wallet, including when the PIN is already set. A customer sent with the same mark
 * stays a customer.
 */
class SeedPinServiceTest {

  static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  static final EncryptedPin PIN = new EncryptedPin("pin-transport.v1", "Y2lwaGVy");

  final WalletRepository wallets = mock(WalletRepository.class);
  final CredentialRepository credentials = mock(CredentialRepository.class);
  final PinService pins = mock(PinService.class);
  final SeedPinService seeds =
      new SeedPinService(wallets, credentials, pins, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void anOrganisationWalletMarkedPartnerKeepsTheMarkWhenThePinAlreadyExists() {
    when(wallets.find("01412000001")).thenReturn(Optional.of(org("01412000001")));
    when(credentials.find("01412000001")).thenReturn(Optional.of(new PinHmac("h", "pin-hmac.v1")));

    assertThat(seeds.seed(request("01412000001", "PARTNER")).results().getFirst().outcome()).isEqualTo("UNCHANGED");

    verify(wallets).markPartnerApp("01412000001");
    verify(pins, never()).derive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void anUnmarkedOrganisationWalletIsNotMarked() {
    when(wallets.find("01312000001")).thenReturn(Optional.of(org("01312000001")));
    when(credentials.find("01312000001")).thenReturn(Optional.of(new PinHmac("h", "pin-hmac.v1")));

    seeds.seed(request("01312000001", null));

    verify(wallets, never()).markPartnerApp(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void theSeedPinBodyCarriesThePartnerMark() throws Exception {
    SeedPinsRequest body = ServiceJson.MAPPER.readValue(
        "{\"pins\":[{\"walletId\":\"01412000001\",\"encryptedPin\":{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\"Y2lwaGVy\"},\"appRole\":\"PARTNER\"}]}",
        SeedPinsRequest.class);
    assertThat(body.pins().getFirst().appRole()).isEqualTo("PARTNER");
    SeedPinsRequest plain = ServiceJson.MAPPER.readValue(
        "{\"pins\":[{\"walletId\":\"01712000001\",\"encryptedPin\":{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\"Y2lwaGVy\"}}]}",
        SeedPinsRequest.class);
    assertThat(plain.pins().getFirst().appRole()).isNull();
  }

  @Test
  void aPartnerMarkOnACustomerWalletIsIgnored() {
    when(wallets.find("01712000001")).thenReturn(Optional.of(
        new AuthWallet("01712000001", "t", "k", "{}", "CUSTOMER", "ACTIVE", true, null, 1)));
    when(credentials.find("01712000001")).thenReturn(Optional.of(new PinHmac("h", "pin-hmac.v1")));

    seeds.seed(request("01712000001", "PARTNER"));

    verify(wallets, never()).markPartnerApp(org.mockito.ArgumentMatchers.any());
  }

  private static AuthWallet org(String id) {
    return new AuthWallet(id, "t", "k", "{}", "ORG", "ACTIVE", true, null, 1);
  }

  private static SeedPinsRequest request(String walletId, String appRole) {
    return new SeedPinsRequest(List.of(new SeedPinsRequest.Pin(walletId, PIN, appRole)));
  }
}
