package mfs.mw.adapter.remit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.adapter.remit.client.CoreAccountClient;
import mfs.mw.adapter.remit.client.CoreUnavailableException;
import mfs.mw.adapter.remit.dto.BeneficiaryLookupResult;
import mfs.mw.adapter.remit.dto.CoreAccount;
import mfs.mw.adapter.remit.exception.MwException;
import mfs.mw.adapter.remit.partner.InboundCall;
import mfs.mw.adapter.remit.partner.InboundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Beneficiary lookup (ticket 119c, FRS UC-16): the partner's verified request, the wallet read from the
 * core, the answer carrying the masked name only, and the per-partner rate limit.
 */
class BeneficiaryLookupServiceTest {

  static final String MSISDN = "01711000001";

  private final RemitInboundService inbound = mock(RemitInboundService.class);
  private final CoreAccountClient core = mock(CoreAccountClient.class);
  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-20T10:00:00Z"));
  private final Clock clock = new Clock() {
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  };
  private BeneficiaryLookupService service;

  @BeforeEach
  void setUp() {
    service = new BeneficiaryLookupService(inbound, core, new LookupRateLimiter(clock, 3, Duration.ofMinutes(1)));
  }

  static InboundCall call(String partner) {
    return new InboundCall(partner, "POST", "/remit/v1/beneficiary-lookup", Map.of(), new byte[0]);
  }

  void asks(String msisdn) {
    org.mockito.Mockito.doReturn(new InboundResult(null, null, "PENDING", null, null, Map.of("beneficiaryMsisdn", msisdn)))
        .when(inbound).authenticate(any());
  }

  @Test
  void anActiveCustomerIsAnsweredWithTheFirstInitialAndTheLengthOfTheName() {
    asks("+8801711000001");
    when(core.account(MSISDN)).thenReturn(Optional.of(new CoreAccount(MSISDN, "CUSTOMER", "ACTIVE", "FULL", "Rahim Uddin")));

    BeneficiaryLookupResult r = service.lookup(call("ucb-imt"));

    assertThat(r.maskedName()).isEqualTo("R**********");
    assertThat(r.eligible()).isTrue();
    assertThat(r.toString()).doesNotContain("Rahim").doesNotContain("Uddin").doesNotContain("01711000001");
  }

  @Test
  void aWalletThatCannotReceiveIsAnsweredNotEligible() {
    asks(MSISDN);
    when(core.account(MSISDN)).thenReturn(Optional.of(new CoreAccount(MSISDN, "CUSTOMER", "BLOCKED", "FULL", "Ayesha")));

    BeneficiaryLookupResult r = service.lookup(call("ucb-imt"));

    assertThat(r.eligible()).isFalse();
    assertThat(r.maskedName()).isEqualTo("A*****");
  }

  @Test
  void aWalletWithoutAHolderNameIsAnsweredWithoutAName() {
    asks(MSISDN);
    when(core.account(MSISDN)).thenReturn(Optional.of(new CoreAccount(MSISDN, "CUSTOMER", "ACTIVE", "FULL", null)));

    assertThat(service.lookup(call("ucb-imt")).maskedName()).isNull();
  }

  @Test
  void anUnknownNumberOrAWalletThatIsNotACustomerIs404() {
    asks(MSISDN);
    when(core.account(MSISDN)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.lookup(call("ucb-imt")))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-VAL-003");
          assertThat(e.status().value()).isEqualTo(404);
        });

    when(core.account(MSISDN)).thenReturn(Optional.of(new CoreAccount(MSISDN, "AGENT", "ACTIVE", "FULL", "Hossain Telecom")));
    assertThatThrownBy(() -> service.lookup(call("ucb-imt")))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-003"));
  }

  @Test
  void anInvalidNumberIs400AndTheCoreIsNotAsked() {
    asks("12345");

    assertThatThrownBy(() -> service.lookup(call("ucb-imt")))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-VAL-001");
          assertThat(e.details()).extracting(d -> d.field()).containsExactly("beneficiaryMsisdn");
        });
    verify(core, never()).account(anyString());
  }

  @Test
  void anUnreachableCoreIs503Retryable() {
    asks(MSISDN);
    when(core.account(MSISDN)).thenThrow(new CoreUnavailableException("core answered 503", null));

    assertThatThrownBy(() -> service.lookup(call("ucb-imt")))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-CORE_UNAVAILABLE");
          assertThat(e.status().value()).isEqualTo(503);
          assertThat(e.retryable()).isTrue();
        });
  }

  @Test
  void aPartnerIsRateLimitedPerWindowAndOthersAreNot() {
    asks(MSISDN);
    when(core.account(MSISDN)).thenReturn(Optional.of(new CoreAccount(MSISDN, "CUSTOMER", "ACTIVE", "FULL", "Rahim")));
    for (int i = 0; i < 3; i++) {
      service.lookup(call("ucb-imt"));
    }

    assertThatThrownBy(() -> service.lookup(call("ucb-imt")))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-RATE_LIMITED");
          assertThat(e.status().value()).isEqualTo(429);
          assertThat(e.retryable()).isTrue();
        });
    assertThat(service.lookup(call("other-imt")).eligible()).isTrue();

    now.set(now.get().plus(Duration.ofMinutes(1)));
    assertThat(service.lookup(call("ucb-imt")).eligible()).isTrue();
  }

  @Test
  void anUnauthenticatedCallIsRefusedBeforeItCountsOrReachesTheCore() {
    when(inbound.authenticate(any())).thenThrow(new MwException("MW-AUTH-001", "The request signature is not valid"));
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> service.lookup(call("ucb-imt")))
          .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-001"));
    }
    verify(core, never()).account(anyString());

    // a forger burned none of the partner's quota
    asks(MSISDN);
    when(core.account(MSISDN)).thenReturn(Optional.of(new CoreAccount(MSISDN, "CUSTOMER", "ACTIVE", "FULL", "Rahim")));
    assertThat(service.lookup(call("ucb-imt")).eligible()).isTrue();
  }
}
