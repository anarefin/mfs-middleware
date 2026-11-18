package mfs.mw.simulators.controller;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.simulators.service.SoapBillerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The SOAP stub biller {@code soapbill} (ticket 85): SOAP 1.1 FetchBill and PayBill on {@code POST
 * /soapbill/v1}, faults with the biller's code in the detail (bill number ending 404 / 999), a
 * fault forced for every call through {@code POST /sim/soapbill/fault}, a DOCTYPE refused.
 */
@WebMvcTest(SoapBillerController.class)
@Import(SoapBillerService.class)
class SoapBillerControllerTest {

  static final String NS = "urn:soapbill:v1";

  private final MockMvcTester mvc;

  @Autowired
  SoapBillerControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Header/><soap:Body>"
        + body + "</soap:Body></soap:Envelope>";
  }

  MvcTestResult soap(String body) {
    return mvc.post().uri("/soapbill/v1").contentType(MediaType.TEXT_XML).header("SOAPAction", "\"x\"")
        .content(envelope(body)).exchange();
  }

  @Test
  void aBillIsFetchedAndPaidOncePerReference() throws Exception {
    MvcTestResult bill = soap("<s:FetchBill xmlns:s=\"" + NS + "\"><s:billNumber>S-123</s:billNumber></s:FetchBill>");
    String pay = "<s:PayBill xmlns:s=\"" + NS + "\"><s:billNumber>S-123</s:billNumber><s:amount>223.00</s:amount>"
        + "<s:reference>BITS1</s:reference></s:PayBill>";
    MvcTestResult first = soap(pay);
    MvcTestResult again = soap(pay);

    assertThat(bill).hasStatus(200).bodyText()
        .contains("<s:FetchBillResponse", "<s:billNumber>S-123</s:billNumber>", "<s:amountDue>223.00</s:amountDue>", "<s:dueDate>");
    assertThat(first).hasStatus(200).bodyText().contains("<s:PayBillResponse", "<s:receipt>SB-");
    String receipt = first.getResponse().getContentAsString().replaceAll("(?s).*<s:receipt>([^<]*)<.*", "$1");
    assertThat(again).hasStatus(200).bodyText().contains("<s:receipt>" + receipt + "</s:receipt>");
  }

  @Test
  void aPaymentStatusIsItsReceiptOrANotFoundFault() {
    soap("<s:PayBill xmlns:s=\"" + NS + "\"><s:billNumber>S-1</s:billNumber><s:amount>1.00</s:amount>"
        + "<s:reference>BITS9</s:reference></s:PayBill>");

    assertThat(soap("<s:GetPayment xmlns:s=\"" + NS + "\"><s:reference>BITS9</s:reference></s:GetPayment>"))
        .hasStatus(200).bodyText().contains("<s:GetPaymentResponse", "<s:status>PAID</s:status>", "<s:receipt>SB-");
    assertThat(soap("<s:GetPayment xmlns:s=\"" + NS + "\"><s:reference>NOPE</s:reference></s:GetPayment>"))
        .hasStatus(500).bodyText().contains("<s:code>3404</s:code>");
  }

  @Test
  void aBillNumberEnding404Or999IsAFaultWithTheBillersCode() {
    assertThat(soap("<s:FetchBill xmlns:s=\"" + NS + "\"><s:billNumber>S-404</s:billNumber></s:FetchBill>"))
        .hasStatus(500).bodyText().contains("<faultcode>soap:Client</faultcode>", "<s:code>3001</s:code>");
    assertThat(soap("<s:PayBill xmlns:s=\"" + NS + "\"><s:billNumber>S-999</s:billNumber><s:amount>1.00</s:amount>"
        + "<s:reference>BITS2</s:reference></s:PayBill>"))
        .hasStatus(500).bodyText().contains("<s:code>3003</s:code>");
  }

  @Test
  void aForcedFaultAnswersEveryCallUntilItIsCleared() {
    assertThat(mvc.post().uri("/sim/soapbill/fault").contentType(MediaType.APPLICATION_JSON)
        .content("{\"faultCode\": \"Server\", \"code\": \"9000\", \"reason\": \"maintenance\"}").exchange()).hasStatus(200);

    assertThat(soap("<s:FetchBill xmlns:s=\"" + NS + "\"><s:billNumber>S-123</s:billNumber></s:FetchBill>"))
        .hasStatus(500).bodyText().contains("<faultcode>soap:Server</faultcode>", "maintenance", "<s:code>9000</s:code>");

    assertThat(mvc.delete().uri("/sim/soapbill/fault").exchange()).hasStatus(204);
    assertThat(soap("<s:FetchBill xmlns:s=\"" + NS + "\"><s:billNumber>S-123</s:billNumber></s:FetchBill>")).hasStatus(200);
  }

  @Test
  void anUnknownOperationOrADoctypeIsAClientFault() {
    assertThat(soap("<s:Refund xmlns:s=\"" + NS + "\"/>")).hasStatus(500).bodyText().contains("soap:Client");
    assertThat(mvc.post().uri("/soapbill/v1").contentType(MediaType.TEXT_XML)
        .content("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + envelope("<s:FetchBill xmlns:s=\"" + NS
            + "\"><s:billNumber>&e;</s:billNumber></s:FetchBill>")).exchange())
        .hasStatus(500).bodyText().contains("soap:Client").doesNotContain("root:");
  }
}
