package mfs.mw.simulators.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import mfs.mw.simulators.config.SmscProperties;
import mfs.mw.simulators.service.SmscService;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.DataCodings;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.SMPPSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SMSC simulator's SMPP front without Spring: binds any client and answers the operations it
 * does not simulate with ESME_RINVCMDID (submit_sm and its receipts are covered by SmscSimulatorIT).
 */
class SmscServerTest {

  private SmscServer server;
  private SMPPSession session;

  @BeforeEach
  void start() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    server = new SmscServer(mock(SmscService.class), new SmscProperties(true, port, Duration.ofMillis(10)));
    server.start();
    session = new SMPPSession();
    session.connectAndBind("127.0.0.1", port, new BindParameter(BindType.BIND_TRX, "t", "fake-pw", "",
        TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null), 5000);
  }

  @AfterEach
  void stop() {
    session.unbindAndClose();
    server.stop();
  }

  @Test
  void anyBindIsAcceptedAndTheServerReportsRunning() {
    assertThat(server.isRunning()).isTrue();
    assertThat(session.getSessionState().isBound()).isTrue();
  }

  @Test
  void operationsTheSimulatorDoesNotPlayAreRefusedAsInvalidCommands() throws IOException {
    Throwable query = catchThrowable(() -> session.queryShortMessage("SMSC-1", TypeOfNumber.UNKNOWN,
        NumberingPlanIndicator.UNKNOWN, "01711000001"));
    Throwable cancel = catchThrowable(() -> session.cancelShortMessage("", "SMSC-1", TypeOfNumber.UNKNOWN,
        NumberingPlanIndicator.UNKNOWN, "01711000001", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN,
        "01711000002"));

    Throwable replace = catchThrowable(() -> session.replaceShortMessage("SMSC-1", TypeOfNumber.UNKNOWN,
        NumberingPlanIndicator.UNKNOWN, "01711000001", null, null, new RegisteredDelivery(0), (byte) 0,
        "x".getBytes(StandardCharsets.US_ASCII)));
    Throwable data = catchThrowable(() -> session.dataShortMessage("", TypeOfNumber.UNKNOWN,
        NumberingPlanIndicator.UNKNOWN, "01711000001", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN,
        "01711000002", new ESMClass(), new RegisteredDelivery(0), DataCodings.ZERO));

    for (Throwable t : new Throwable[] {query, cancel, replace, data}) {
      assertThat(t).isInstanceOf(NegativeResponseException.class);
      assertThat(((NegativeResponseException) t).getCommandStatus()).isEqualTo(SMPPConstant.STAT_ESME_RINVCMDID);
    }
  }

  @Test
  void stopClosesTheListener() {
    server.stop();
    assertThat(server.isRunning()).isFalse();
  }
}
