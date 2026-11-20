package mfs.mw.compliance.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The drift rules of {@link ContractDrift} (ticket 101, PLT-050): what makes the generated document
 * and the owned contract files disagree, on small hand-written documents.
 */
class ContractDriftRulesTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static JsonNode doc(String paths) {
    return JSON.readTree("{\"openapi\":\"3.1.0\",\"paths\":" + paths + "}");
  }

  static final String CONTRACT =
      """
      {"/v1/things/{id}": {
         "parameters": [{"name":"id","in":"path","required":true}],
         "get": {
           "parameters": [{"$ref":"#/components/parameters/Corr"}, {"name":"Idempotency-Key","in":"header","required":true}],
           "responses": {"200":{}, "404":{}}
         }
       }}""";

  static JsonNode contract() {
    return JSON.readTree(
        "{\"openapi\":\"3.1.0\",\"paths\":" + CONTRACT
            + ",\"components\":{\"parameters\":{\"Corr\":{\"name\":\"X-Correlation-Id\",\"in\":\"header\"}}}}");
  }

  static final String MATCHING_CODE =
      """
      {"/v1/things/{thingId}": {"get": {
         "parameters": [{"name":"thingId","in":"path","required":true},
                        {"name":"idempotency-key","in":"header","required":true},
                        {"name":"X-Correlation-Id","in":"header","required":false}],
         "responses": {"200":{}}
      }}}""";

  @Test
  void codeThatMatchesTheContractHasNoDrift() {
    assertThat(ContractDrift.differences(doc(MATCHING_CODE), List.of(contract()))).isEmpty();
  }

  @Test
  void anEndpointInCodeButNotInTheContractIsDrift() {
    JsonNode code =
        doc(MATCHING_CODE.substring(0, MATCHING_CODE.length() - 1)
            + ",\"/v1/extra\":{\"post\":{\"responses\":{\"200\":{}}}}}");
    assertThat(ContractDrift.differences(code, List.of(contract())))
        .containsExactly("POST /v1/extra: in code, not in the contract");
  }

  @Test
  void anEndpointInTheContractButNotInCodeIsDrift() {
    assertThat(ContractDrift.differences(doc("{}"), List.of(contract())))
        .containsExactly("GET /v1/things/{}: in the contract, not in code");
  }

  @Test
  void aRequiredParameterOnOneSideOnlyIsDrift() {
    JsonNode code =
        doc("""
            {"/v1/things/{id}": {"get": {
               "parameters": [{"name":"id","in":"path","required":true},
                              {"name":"page","in":"query","required":true}],
               "responses": {"200":{}}}}}""");
    assertThat(ContractDrift.differences(code, List.of(contract())))
        .containsExactly(
            "GET /v1/things/{}: required parameters differ: code [query:page],"
                + " contract [header:idempotency-key]");
  }

  @Test
  void aResponseCodeTheContractDoesNotDeclareIsDrift() {
    JsonNode code = doc(MATCHING_CODE.replace("\"200\"", "\"201\""));
    assertThat(ContractDrift.differences(code, List.of(contract())))
        .containsExactly("GET /v1/things/{}: response 201 in code, contract has [200, 404]");
  }

  @Test
  void severalContractFilesFormOneSurface() {
    JsonNode other = doc("{\"/test/v1/reset\":{\"delete\":{\"responses\":{\"204\":{}}}}}");
    JsonNode code =
        doc(MATCHING_CODE.substring(0, MATCHING_CODE.length() - 1)
            + ",\"/test/v1/reset\":{\"delete\":{\"responses\":{\"204\":{}}}}}");
    assertThat(ContractDrift.differences(code, List.of(contract(), other))).isEmpty();
  }
}
