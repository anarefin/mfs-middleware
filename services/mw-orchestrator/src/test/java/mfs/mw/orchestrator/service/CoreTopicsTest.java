package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The core's event topics (spec 6.3, ADR D31); its calls are REST since ticket 95a. */
class CoreTopicsTest {

  @Test
  void eventsAreConsumedFromBothEventTopics() {
    assertThat(CoreTopics.WALLET_EVENTS).isEqualTo("core.wallet.event.v1");
    assertThat(CoreTopics.PAYMENT_EVENTS).isEqualTo("core.payment.event.v1");
  }
}
