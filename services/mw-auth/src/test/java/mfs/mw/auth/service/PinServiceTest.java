package mfs.mw.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.PinProperties;
import mfs.mw.auth.dto.CryptoPinDerived;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.CredentialRepository;
import org.junit.jupiter.api.Test;

/**
 * The weak-PIN policy as mw-auth applies it (ticket 70): the rules that need the clear PIN run in
 * mw-crypto (every rule: {@code WeakPinPolicyTest} there), with the inputs passed here; the reuse of
 * the last PINs is checked here on the HMACs; the history kept is the last {@code history - 1}
 * earlier PINs plus the current one.
 */
class PinServiceTest {

  static final PinProperties PROPS = new PinProperties(3,
      new PinProperties.Lock(3, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30)),
      new PinProperties.Reset(Duration.ofMinutes(15), Duration.ofHours(72)),
      "mw.audit.event.v1");
  static final AuthWallet WALLET =
      new AuthWallet("01711000001", "tok", "tokenise-msisdn.v1", "{}", "CUSTOMER", "ACTIVE", true, 1990, 1);
  static final EncryptedPin PIN = new EncryptedPin("pin-transport.v1", "AAAA");

  final CryptoClient crypto = mock(CryptoClient.class);
  final CredentialRepository credentials = mock(CredentialRepository.class);
  final PinService pins = new PinService(crypto, credentials, PROPS);

  static PinHmac h(String v) {
    return new PinHmac(v, "pin-hmac.v1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theRuleInputsAreTheMsisdnTheBirthYearAndTheCurrentPin() {
    when(credentials.credential("01711000001"))
        .thenReturn(Optional.of(new CredentialRepository.Credential(h("current"), List.of())));
    when(crypto.derivePin(anyString(), any(), any())).thenReturn(new CryptoPinDerived(false, null, "new", "pin-hmac.v1"));

    assertThat(pins.newPin(WALLET, "01711000001", PIN)).isEqualTo(h("new"));
    org.mockito.ArgumentCaptor<Map<String, Object>> in = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(crypto).derivePin(eq("01711000001"), eq(PIN), in.capture());
    assertThat(in.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
        "msisdn", "01711000001", "birthYear", 1990, "lastHmac", "current", "lastHmacKeyId", "pin-hmac.v1"));
  }

  @Test
  void aFirstPinHasNoLastPinInput() {
    when(credentials.credential(anyString())).thenReturn(Optional.empty());
    when(crypto.derivePin(anyString(), any(), any())).thenReturn(new CryptoPinDerived(false, null, "new", "pin-hmac.v1"));
    pins.newPin(new AuthWallet("w", null, null, null, "CUSTOMER", "ACTIVE", false, null, 1), "01711000001", PIN);
    verify(crypto).derivePin("w", PIN, Map.of("msisdn", "01711000001"));
  }

  @Test
  void everyRuleMwCryptoNamesIsRefusedWithIt() {
    when(credentials.credential(anyString())).thenReturn(Optional.empty());
    for (String rule : List.of("REPEATED_DIGIT", "SEQUENCE", "MSISDN_DIGITS", "BIRTH_YEAR", "LAST_PIN")) {
      when(crypto.derivePin(anyString(), any(), any())).thenReturn(new CryptoPinDerived(true, rule, null, null));
      assertThatThrownBy(() -> pins.newPin(WALLET, "01711000001", PIN))
          .isInstanceOfSatisfying(MwException.class, e -> {
            assertThat(e.code()).isEqualTo("MW-AUTH-044");
            assertThat(e.details().getFirst().issue()).isEqualTo(rule);
          });
    }
  }

  @Test
  void anEarlierPinUnderTheSameKeyVersionIsRefusedAsLastPin() {
    when(credentials.credential(anyString()))
        .thenReturn(Optional.of(new CredentialRepository.Credential(h("c"), List.of(h("e1"), h("e2")))));
    when(crypto.derivePin(anyString(), any(), any())).thenReturn(new CryptoPinDerived(false, null, "e2", "pin-hmac.v1"));
    assertThatThrownBy(() -> pins.newPin(WALLET, "01711000001", PIN))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.details().getFirst().issue()).isEqualTo("LAST_PIN"));

    when(crypto.derivePin(anyString(), any(), any())).thenReturn(new CryptoPinDerived(false, null, "e2", "pin-hmac.v2"));
    assertThat(pins.newPin(WALLET, "01711000001", PIN)).as("another key version is another HMAC").isNotNull();
  }

  @Test
  void theHistoryKeepsTheCurrentAndTheNewestEarlierPins() {
    assertThat(pins.historyAfter(Optional.empty())).isEmpty();
    assertThat(pins.historyAfter(Optional.of(new CredentialRepository.Credential(h("c"), List.of()))))
        .containsExactly(h("c"));
    assertThat(pins.historyAfter(Optional.of(new CredentialRepository.Credential(h("c"), List.of(h("e1"), h("e2"))))))
        .containsExactly(h("c"), h("e1"));
  }
}
