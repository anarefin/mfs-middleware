package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import mfs.mw.audit.dw.exception.MwException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The test-only chain check (ticket 58): one month through the read-only verifier, 503 without one. */
class AuditTestServiceTest {

  @SuppressWarnings("unchecked")
  private final ObjectProvider<AuditChainVerifier> provider = mock(ObjectProvider.class);

  private final AuditTestService service = new AuditTestService(provider);

  @Test
  void verifiesTheMonthWithTheReadOnlyVerifier() {
    AuditChainVerifier verifier = mock(AuditChainVerifier.class);
    when(provider.getIfAvailable()).thenReturn(verifier);
    when(verifier.verify("2026-11")).thenReturn(new ChainVerification("2026-11", true, 42, null, null));

    assertThat(service.verifyChain("2026-11")).isEqualTo(new ChainVerification("2026-11", true, 42, null, null));
  }

  @Test
  void chainVerificationSwitchedOffIsThe503Envelope() {
    when(provider.getIfAvailable()).thenReturn(null);

    assertThatThrownBy(() -> service.verifyChain("2026-11"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-002");
          assertThat(e.status().value()).isEqualTo(503);
        });
  }
}
