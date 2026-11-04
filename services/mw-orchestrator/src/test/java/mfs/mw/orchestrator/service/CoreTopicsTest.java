package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Core command topics (spec 6.3, M2 gate decision, ticket 50). */
class CoreTopicsTest {

  @ParameterizedTest
  @ValueSource(strings = {"HoldFunds", "CommitHold", "ReleaseHold"})
  void holdCommitAndReleaseGoOnThePaymentTopic(String commandType) {
    assertThat(CoreTopics.commandTopic(commandType)).isEqualTo("core.payment.command.v1");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ValidateTransaction", "PostTransfer", "CreditWallet", "ReverseTransaction",
        "QueryTransaction", "SomethingNew"
      })
  void everyOtherCommandGoesOnTheWalletTopic(String commandType) {
    assertThat(CoreTopics.commandTopic(commandType)).isEqualTo("core.wallet.command.v1");
  }

  @Test
  void resultsAreConsumedFromBothEventTopics() {
    assertThat(CoreTopics.WALLET_EVENTS).isEqualTo("core.wallet.event.v1");
    assertThat(CoreTopics.PAYMENT_EVENTS).isEqualTo("core.payment.event.v1");
  }
}
