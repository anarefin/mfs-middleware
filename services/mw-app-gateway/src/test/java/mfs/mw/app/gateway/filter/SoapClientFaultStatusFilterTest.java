package mfs.mw.app.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import mfs.mw.app.gateway.filter.SoapClientFaultStatusFilter.SoapStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class SoapClientFaultStatusFilterTest {

  @Test
  void aSoap11ClientFaultBecomesHttp400AndKeepsTheBody() throws Exception {
    MockHttpServletResponse real = new MockHttpServletResponse();
    SoapStatusResponse buffered = new SoapStatusResponse(real);
    buffered.setStatus(500);
    buffered.getOutputStream().write("<faultcode>soapenv:Client</faultcode>".getBytes(StandardCharsets.UTF_8));
    buffered.copyTo(real);
    assertThat(real.getStatus()).isEqualTo(400);
    assertThat(real.getContentAsString()).contains("soapenv:Client");
  }

  @Test
  void aServerFaultStaysHttp500() throws Exception {
    MockHttpServletResponse real = new MockHttpServletResponse();
    SoapStatusResponse buffered = new SoapStatusResponse(real);
    buffered.setStatus(500);
    buffered.getOutputStream().write("<faultcode>soapenv:Server</faultcode>".getBytes(StandardCharsets.UTF_8));
    buffered.copyTo(real);
    assertThat(real.getStatus()).isEqualTo(500);
  }

  @Test
  void aSuccessfulResponseKeepsItsStatus() throws Exception {
    MockHttpServletResponse real = new MockHttpServletResponse();
    SoapStatusResponse buffered = new SoapStatusResponse(real);
    buffered.setStatus(200);
    buffered.getWriter().write("<PreviewResponse/>");
    buffered.copyTo(real);
    assertThat(real.getStatus()).isEqualTo(200);
    assertThat(real.getContentAsString()).contains("PreviewResponse");
  }
}
