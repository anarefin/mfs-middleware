package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.client.FakeCryptoProvider;
import mfs.mw.crypto.client.KeyAlgorithm;
import mfs.mw.crypto.client.PayShieldProvider;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.config.PayShieldProperties;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.mapper.KeyMapper;
import mfs.mw.crypto.repository.KeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Keys of the payShield purposes (ticket 64): generated in the provider that has the purpose's
 * capability (the default provider has none), with the LMK blob it returns as the version's HSM
 * reference; and the dev bootstrap adds the payShield purposes only while the provider is on.
 */
class PayShieldKeyServiceTest {

  static final String BLOB = "U" + "A".repeat(32);

  KeyRepository repo = mock(KeyRepository.class);
  PayShieldProvider payshield = mock(PayShieldProvider.class);
  KeyService service;
  CryptoProperties props =
      new CryptoProperties("pkcs11", true, List.of("JWT_SIGN"), Duration.ofSeconds(30), Duration.ofSeconds(10),
          new CryptoProperties.Pkcs11("/x", 0, "", 1, Duration.ofSeconds(1), Duration.ofSeconds(1)));

  @BeforeEach
  void setUp() {
    when(payshield.name()).thenReturn("payshield");
    when(payshield.capabilities()).thenReturn(EnumSet.of(Capability.PIN_BLOCK, Capability.PVV));
    when(payshield.createKey(anyString(), any(), anyString())).thenReturn(BLOB);
    when(repo.findVersions(anyString())).thenReturn(List.of());
    service = new KeyService(repo, List.of(new FakeCryptoProvider(), payshield), props, new KeyMapper(),
        Clock.systemUTC(), new SimpleMeterRegistry());
  }

  @Test
  void aPayShieldPurposeIsGeneratedThereAndStoresTheBlob() {
    var view = service.generate("ZPK", null);
    assertThat(view.provider()).isEqualTo("payshield");
    verify(payshield).createKey("zpk.v1", KeyAlgorithm.TDES_2KEY, "ZPK");
    ArgumentCaptor<CryptoKey> row = ArgumentCaptor.forClass(CryptoKey.class);
    verify(repo).insert(row.capture());
    assertThat(row.getValue().provider()).isEqualTo("payshield");
    verify(repo).updateLabel("zpk", 1, BLOB);
  }

  @Test
  void aPkcs11PurposeKeepsItsLabel() {
    service.generate("JWT_SIGN", null);
    verify(repo).insert(any());
    verify(repo, org.mockito.Mockito.never()).updateLabel(anyString(), org.mockito.ArgumentMatchers.anyInt(),
        anyString());
  }

  @Test
  void bootstrapAddsThePayShieldPurposesOnlyWhenTheProviderIsOn() {
    when(repo.purposes()).thenReturn(new ArrayList<>(List.of("JWT_SIGN")));
    var on = new PayShieldProperties(true, null, 0, 0, 0, null, null, null, List.of("PVK", "TPK"));
    assertThat(new KeyBootstrapService(service, props, on).ensureKeys()).containsExactly("PVK", "TPK");
    var off = new PayShieldProperties(false, null, 0, 0, 0, null, null, null, List.of("PVK", "TPK"));
    assertThat(new KeyBootstrapService(service, props, off).ensureKeys()).isEmpty();
  }
}
