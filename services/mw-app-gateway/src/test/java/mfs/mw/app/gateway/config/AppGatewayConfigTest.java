package mfs.mw.app.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.contracts.ContractSchemas;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * The shipped {@code application.yml} against the app API contract: every {@code txnType} the
 * OpenAPI enum accepts is supported and has a role entry, so a type added to the API without
 * deciding who may initiate it fails here, not in production (where it would be refused for
 * every role, deny by default).
 */
class AppGatewayConfigTest {

  static AppGatewayProperties shipped() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    Binder binder =
        new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources));
    return binder.bind("mw.app-gateway", Bindable.of(AppGatewayProperties.class)).get();
  }

  @SuppressWarnings("unchecked")
  static List<String> openApiTxnTypes() throws IOException {
    Path file = ContractSchemas.contractsDir().resolve("api/mw-app-gateway/app.v1.openapi.yaml");
    try (InputStream in = Files.newInputStream(file)) {
      Map<String, Object> doc = new Yaml().load(in);
      Map<String, Object> schemas =
          (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
      Map<String, Object> request = (Map<String, Object>) schemas.get("TransactionRequest");
      Map<String, Object> txnType =
          (Map<String, Object>) ((Map<String, Object>) request.get("properties")).get("txnType");
      return (List<String>) txnType.get("enum");
    }
  }

  @Test
  void everyTxnTypeOfTheApiIsSupportedAndHasARoleEntry() throws IOException {
    AppGatewayProperties props = shipped();
    List<String> api = openApiTxnTypes();

    assertThat(api).isNotEmpty();
    assertThat(props.supportedTxnTypes()).containsExactlyInAnyOrderElementsOf(api);
    assertThat(props.txnTypeRoles().keySet()).containsAll(api);
    props.txnTypeRoles().forEach((type, roles) -> assertThat(roles).as(type).isNotEmpty());
  }

  @Test
  void theShippedRolesAreTheAgreedOnes() throws IOException {
    AppGatewayProperties props = shipped();

    assertThat(props.txnTypeRoles())
        .containsEntry("P2P", List.of("CUSTOMER"))
        .containsEntry("CASH_IN", List.of("AGENT"))
        .containsEntry("CASH_OUT", List.of("CUSTOMER"))
        .containsEntry("TOPUP", List.of("CUSTOMER"));
  }

  @Test
  void aTypeWithoutAnEntryIsDeniedForEveryRole() throws IOException {
    AppGatewayProperties props = shipped();

    assertThat(props.mayInitiate("CUSTOMER", "BILL_PAY")).isFalse();
    assertThat(props.mayInitiate("AGENT", "BILL_PAY")).isFalse();
    assertThat(props.mayInitiate("AGENT", "CASH_IN")).isTrue();
    assertThat(props.mayInitiate("CUSTOMER", "CASH_IN")).isFalse();
  }
}
