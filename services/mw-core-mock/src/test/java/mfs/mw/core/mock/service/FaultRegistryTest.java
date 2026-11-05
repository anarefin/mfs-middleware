package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.core.mock.dto.Fault;
import mfs.mw.core.mock.dto.Fault.FaultMode;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.exception.MwException;
import org.junit.jupiter.api.Test;

class FaultRegistryTest {

  private final FaultRegistry registry = new FaultRegistry();

  @Test
  void matchesByCommandTypeAndTxnIdWhereGiven() {
    registry.add(new FaultRequest(FaultMode.DROP_REPLY, "PostTransfer", null, null, null, null));
    registry.add(new FaultRequest(FaultMode.DROP_REPLY, null, "txn-1", null, null, null));

    assertThat(registry.consume("PostTransfer", "txn-9")).hasSize(1);
    assertThat(registry.consume("HoldFunds", "txn-1")).hasSize(1);
    assertThat(registry.consume("PostTransfer", "txn-1")).hasSize(2);
    assertThat(registry.consume("HoldFunds", "txn-9")).isEmpty();
  }

  @Test
  void aTimesLimitCountsDownAndThenRemovesTheFault() {
    Fault f = registry.add(new FaultRequest(FaultMode.REJECT, null, null, null, null, 2));
    assertThat(f.reasonCode()).isEqualTo("INJECTED_REJECT");

    registry.consume("PostTransfer", "t");
    assertThat(registry.list()).singleElement().extracting(Fault::remaining).isEqualTo(1);
    registry.consume("PostTransfer", "t");

    assertThat(registry.list()).isEmpty();
  }

  @Test
  void keepsOnlyTheParametersOfItsModeAndNormalisesBlankScopes() {
    Fault delay = registry.add(new FaultRequest(FaultMode.DELAY, " ", "", 500L, "ignored", null));
    assertThat(delay.delayMs()).isEqualTo(500);
    assertThat(delay.reasonCode()).isNull();
    assertThat(delay.commandType()).isNull();
    assertThat(delay.txnId()).isNull();
  }

  @Test
  void refusesADelayWithoutDurationAndAnUnknownCommandType() {
    assertThatThrownBy(() -> registry.add(new FaultRequest(FaultMode.DELAY, null, null, null, null, null)))
        .isInstanceOf(MwException.class);
    assertThatThrownBy(() -> registry.add(new FaultRequest(FaultMode.DROP_REPLY, "Nope", null, null, null, null)))
        .isInstanceOf(MwException.class);
  }

  @Test
  void removeAndResetClearFaults() {
    Fault one = registry.add(new FaultRequest(FaultMode.DROP_REPLY, null, null, null, null, null));
    registry.add(new FaultRequest(FaultMode.DROP_REPLY, null, null, null, null, null));

    assertThat(registry.remove(one.id())).isTrue();
    assertThat(registry.remove(one.id())).isFalse();
    registry.reset();
    assertThat(registry.list()).isEmpty();
  }
}
