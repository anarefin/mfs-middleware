package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import mfs.mw.orchestrator.mapper.PartnerReplyMapper;
import mfs.mw.orchestrator.repository.PartnerCallRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.SagaMachine;
import mfs.mw.orchestrator.service.saga.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The hosted page URL check (M9 review) and the card self-transitions (ticket 118b). */
class AddMoneyCardServiceTest {

  private final AddMoneyCardService service = new AddMoneyCardService(
      mock(SagaEngine.class), mock(TransactionRepository.class), mock(PartnerCallRepository.class),
      mock(PartnerReplyMapper.class), Duration.ofMinutes(15), " localhost , pay.example.test ");

  @Test
  void httpsOnAnAllowedHostIsRelayed() {
    assertThat(service.allowedHostedPage("https://localhost/cybersource/hp/sess-1")).isTrue();
    assertThat(service.allowedHostedPage("https://PAY.example.test:8443/hp/sess-1")).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "javascript:alert(1)", "http://localhost/cybersource/hp/sess-1", "https://evil.example/hp/sess-1",
      "https://user@localhost/hp", "https://localhost.evil.example/hp", "not a url", ""})
  void anythingElseIsRefused(String url) {
    assertThat(service.allowedHostedPage(url)).isFalse();
  }

  @Test
  void theCardEventsKeepTheTransactionPartnerPending() {
    assertThat(SagaMachine.apply(Status.PARTNER_PENDING, new SagaEvent.CardSessionCreated()).to())
        .isEqualTo(Status.PARTNER_PENDING);
    assertThat(SagaMachine.apply(Status.PARTNER_PENDING, new SagaEvent.CardAuthenticated()).to())
        .isEqualTo(Status.PARTNER_PENDING);
  }
}
