package mfs.mw.adapter.billpay.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import mfs.mw.adapter.billpay.partner.InboundCall;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The HTTP request of a partner callback as the framework's {@link InboundCall}: the body and
 * the path exactly as received (that is what the partner signed), every header, the method.
 */
class InboundCallMapperTest {

  private final InboundCallMapper mapper = new InboundCallMapper();

  @Test
  void keepsThePathQueryHeadersAndBodyAsReceived() {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/partner/billagg/callback/payments");
    req.setQueryString("a=1&b=2");
    req.addHeader("X-Signature", "sig");
    req.addHeader("X-Timestamp", "1700000000");
    byte[] body = "{\"status\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8);

    InboundCall call = mapper.toInboundCall("billagg", req, body);

    assertThat(call.partnerId()).isEqualTo("billagg");
    assertThat(call.method()).isEqualTo("POST");
    assertThat(call.pathAndQuery()).isEqualTo("/partner/billagg/callback/payments?a=1&b=2");
    assertThat(call.headers()).containsEntry("X-Signature", "sig").containsEntry("X-Timestamp", "1700000000");
    assertThat(call.body()).isEqualTo(body);
  }

  @Test
  void aPathWithoutQueryHasNoQuestionMark() {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/partner/billagg/callback/payments");

    InboundCall call = mapper.toInboundCall("billagg", req, null);

    assertThat(call.pathAndQuery()).isEqualTo("/partner/billagg/callback/payments");
    assertThat(call.body()).isEmpty();
  }
}
