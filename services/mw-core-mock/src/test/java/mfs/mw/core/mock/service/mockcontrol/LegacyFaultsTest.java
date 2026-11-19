package mfs.mw.core.mock.service.mockcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.Map;
import java.util.SplittableRandom;
import mfs.mw.core.mock.config.MockControlProperties;
import mfs.mw.core.mock.dto.Fault;
import mfs.mw.core.mock.dto.Fault.FaultMode;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.dto.MockMode;
import mfs.mw.core.mock.dto.ReplyMode;
import mfs.mw.core.mock.exception.MwException;
import org.junit.jupiter.api.Test;

/** The old /core-mock/v1/faults API as scenarios of the core party (ticket 103): the old behaviour, decided by the engine. */
class LegacyFaultsTest {

  private final PartySwitch switches = new PartySwitch();
  private final ScenarioEngine engine = new ScenarioEngine(switches,
      new Journal(new MockControlProperties(null, null)), new MockControlProperties(null, null), new SplittableRandom(1),
      Clock.systemUTC());
  private final LegacyFaults faults = new LegacyFaults(engine);

  private Decision decide(String operation, String txnId) {
    return engine.decide(operation, Map.of("txnId", txnId));
  }

  @Test
  void matchesByCommandTypeAndTxnIdWhereGiven() {
    faults.add(new FaultRequest(FaultMode.DROP_REPLY, "PostTransfer", null, null, null, null));
    faults.add(new FaultRequest(FaultMode.REJECT, null, "txn-1", null, null, null));

    assertThat(decide("transfer", "txn-9").reply()).isEqualTo(ReplyMode.NONE);
    assertThat(decide("transfer", "txn-9").refuses()).isFalse();
    assertThat(decide("hold", "txn-1").refuses()).isTrue();
    Decision both = decide("transfer", "txn-1");
    assertThat(both.refuses()).isTrue();
    assertThat(both.replyLost()).as("a lost refusal: outcome and reply are separate dimensions").isTrue();
    assertThat(decide("hold", "txn-9")).extracting(Decision::mode, Decision::reply).containsExactly(MockMode.SUCCESS, ReplyMode.ONCE);
  }

  @Test
  void aTimesLimitCountsDownAndThenRemovesTheFault() {
    Fault f = faults.add(new FaultRequest(FaultMode.REJECT, null, null, null, null, 2));
    assertThat(f.reasonCode()).isEqualTo("INJECTED_REJECT");

    assertThat(decide("transfer", "t").reason()).isEqualTo("INJECTED_REJECT");
    assertThat(faults.list()).singleElement().extracting(Fault::remaining).isEqualTo(1);
    decide("transfer", "t");

    assertThat(faults.list()).isEmpty();
    assertThat(decide("transfer", "t").refuses()).isFalse();
  }

  @Test
  void delayAndDuplicateShapeTheReply() {
    faults.add(new FaultRequest(FaultMode.DELAY, " ", "", 500L, "ignored", null));
    faults.add(new FaultRequest(FaultMode.DUPLICATE_REPLY, "BalanceInquiry", null, null, null, null));

    Fault delay = faults.list().get(0);
    assertThat(delay.delayMs()).isEqualTo(500);
    assertThat(delay.reasonCode()).isNull();
    assertThat(delay.commandType()).isNull();
    assertThat(decide("balance", "t")).extracting(Decision::replyAfterMs, Decision::reply).containsExactly(500L, ReplyMode.DUPLICATE);
    assertThat(decide("transfer", "t").reply()).isEqualTo(ReplyMode.ONCE);
  }

  @Test
  void refusesADelayWithoutDurationAndAnUnknownCommandType() {
    assertThatThrownBy(() -> faults.add(new FaultRequest(FaultMode.DELAY, null, null, null, null, null)))
        .isInstanceOf(MwException.class);
    assertThatThrownBy(() -> faults.add(new FaultRequest(FaultMode.DROP_REPLY, "Nope", null, null, null, null)))
        .isInstanceOf(MwException.class);
  }

  @Test
  void removeAndResetClearOnlyTheFaultsScenarios() {
    Fault one = faults.add(new FaultRequest(FaultMode.DROP_REPLY, null, null, null, null, null));
    faults.add(new FaultRequest(FaultMode.DROP_REPLY, null, null, null, null, null));
    engine.add(MockControl.CORE, mfs.mw.core.mock.dto.MockScenario.of("hold", mfs.mw.core.mock.dto.MockBehaviour.TIMEOUT));

    assertThat(faults.remove(one.id())).isTrue();
    assertThat(faults.remove(one.id())).isFalse();
    faults.reset();
    assertThat(faults.list()).isEmpty();
    assertThat(engine.list(MockControl.CORE)).as("a scenario set through /mock-control stays").hasSize(1);
  }
}
