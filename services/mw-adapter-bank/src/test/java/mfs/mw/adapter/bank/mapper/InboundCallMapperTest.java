package mfs.mw.adapter.bank.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import mfs.mw.adapter.bank.partner.InboundCall;
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
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/partner/c2wbank/callback/debits");
    req.setQueryString("a=1&b=2");
    req.addHeader("X-Signature", "sig");
    req.addHeader("X-Timestamp", "1700000000");
    byte[] body = "{\"status\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8);

    InboundCall call = mapper.toInboundCall("c2wbank", req, body);

    assertThat(call.partnerId()).isEqualTo("c2wbank");
    assertThat(call.method()).isEqualTo("POST");
    assertThat(call.pathAndQuery()).isEqualTo("/partner/c2wbank/callback/debits?a=1&b=2");
    assertThat(call.headers()).containsEntry("X-Signature", "sig").containsEntry("X-Timestamp", "1700000000");
    assertThat(call.body()).isEqualTo(body);
  }

  @Test
  void aPathWithoutQueryHasNoQuestionMark() {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/partner/c2wbank/callback/debits");

    InboundCall call = mapper.toInboundCall("c2wbank", req, null);

    assertThat(call.pathAndQuery()).isEqualTo("/partner/c2wbank/callback/debits");
    assertThat(call.body()).isEmpty();
  }
}
