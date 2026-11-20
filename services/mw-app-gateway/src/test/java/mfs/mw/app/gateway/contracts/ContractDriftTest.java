package mfs.mw.app.gateway.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import mfs.mw.app.gateway.controller.AppSoap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The code still matches the contracts this module owns (ticket 101, PLT-050, PLT-052): the web
 * slice with every controller builds the springdoc document ({@code /v3/api-docs}, OpenAPI 3.1,
 * profile {@code test}), which {@link ContractDrift} compares with the {@code produces.apis} files of
 * {@code contracts.yml} under {@code mwContractsDir}. An endpoint in code but not in a contract (or
 * the reverse), a required parameter or a response code on one side only fails. Conditional
 * controllers (test-only APIs) are switched on in {@code properties} so the whole surface is compared.
 */
@WebMvcTest(properties = {"spring.test.mockmvc.print=none"})
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(MockedWebSlice.class)
class ContractDriftTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private final MockMvcTester mvc;

  @Autowired
  ContractDriftTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  JsonNode generated() {
    MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();
    assertThat(result).hasStatusOk();
    return JSON.readTree(result.getResponse().getContentAsByteArray());
  }

  @Test
  void theGeneratedDocumentIsOpenApi31() {
    assertThat(generated().path("openapi").asString()).startsWith("3.1");
  }

  @Test
  void theCodeMatchesTheOwnedContracts() {
    List<JsonNode> contracts = ContractDrift.ownedApis().stream().map(ContractDrift::contract).toList();
    assertThat(ContractDrift.differences(generated(), contracts)).isEmpty();
  }

  @Test
  void swaggerUiIsServedInTheTestProfile() {
    assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    // relative, so the UI also works behind the dev-only APISIX prefix /docs/<service>/
    assertThat(mvc.get().uri("/swagger-ui/swagger-initializer.js"))
        .hasStatusOk()
        .bodyText()
        .contains("\"configUrl\" : \"../v3/api-docs/swagger-config\"");
  }

  /** Ticket 108, PLT-050: the generated WSDL's operations are the request/response pairs of the XSD. */
  @Test
  void theWsdlOperationsMatchTheXsd() throws Exception {
    Path contract = Path.of(System.getProperty("mwContractsDir"), "api/mw-app-gateway/app-soap.v1.xsd");
    byte[] xsd = Files.readAllBytes(contract);
    assertThat(new ClassPathResource("xsd/app-soap.v1.xsd").getContentAsByteArray())
        .as("the jar copy of the SOAP schema")
        .isEqualTo(xsd);
    SimpleXsdSchema schema = new SimpleXsdSchema(new FileSystemResource(contract));
    schema.afterPropertiesSet();
    DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
    wsdl.setPortTypeName("AppPort");
    wsdl.setLocationUri("/app/ws/v1");
    wsdl.setTargetNamespace(AppSoap.NAMESPACE);
    wsdl.setSchema(schema);
    wsdl.setRequestSuffix("Request");
    wsdl.setResponseSuffix("Response");
    wsdl.afterPropertiesSet();
    StringWriter out = new StringWriter();
    TransformerFactory.newInstance().newTransformer().transform(wsdl.getSource(), new StreamResult(out));
    String doc = out.toString();
    assertThat(doc).contains("location=\"/app/ws/v1\"");
    for (String operation : List.of("Preview", "Confirm", "GetBalance", "GetTransactionStatus")) {
      assertThat(doc).contains("name=\"" + operation + "\"");
      assertThat(doc).contains(operation + "Request");
      assertThat(doc).contains(operation + "Response");
    }
  }
}
