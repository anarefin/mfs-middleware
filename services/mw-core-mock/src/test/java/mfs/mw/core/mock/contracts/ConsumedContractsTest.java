package mfs.mw.core.mock.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Every {@code consumes} entry of {@code contracts.yml} (ticket 53). Since ticket 95b (ADR D31) the core
 * consumes no Kafka command: the orchestrator, mw-auth and mw-admin-api call {@code /core/v1}
 * ({@code controller/CoreWalletRestIT} checks those bodies against {@code core-wallet.v1}); the one API
 * it consumes is mw-auth's assertion key set. No Spring context, no Docker.
 */
class ConsumedContractsTest {

  @Test
  void consumesNoEventSinceTheCoreCommandsMovedToRest() {
    assertThat(ContractManifest.consumedEvents()).isEmpty();
  }

  @Test
  void consumesMwAuthsAssertionKeys() {
    assertThat(ContractManifest.consumedApis()).containsExactly("api/mw-auth/auth.v1.openapi.yaml");
  }
}
