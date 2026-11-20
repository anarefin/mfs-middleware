package mfs.mw.data.export.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import mfs.mw.data.export.config.PiiPolicyProperties;
import mfs.mw.data.export.exception.UntaggedFieldException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Deny-by-default PII tags (ticket 128). Expected masks are the spec's literals. */
class PiiPolicyTest {

  static final String MSISDN = "01712345678";
  static final String MASKED = "0171****678";
  static final String ACCOUNT = "001122334455";
  static final String TOKEN = "ACCOUNT_TESTTOKEN";

  FieldTokeniser tokens = (domain, value) -> {
    assertThat(domain).isEqualTo("ACCOUNT");
    assertThat(value).isEqualTo(ACCOUNT);
    return TOKEN;
  };

  PiiPolicy policy() {
    return new PiiPolicy(new PiiPolicyProperties(Map.of("payment", Map.of(
        "txnId", PiiAction.CLEAR,
        "msisdn", PiiAction.MASK,
        "account", PiiAction.TOKENISE))), tokens);
  }

  @Test
  void clearCopiesMaskUsesTheSpecLiteralAndTokeniseReplacesTheValue() {
    Map<String, Object> out = policy().apply("payment", Map.of(
        "txnId", "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b",
        "msisdn", MSISDN,
        "account", ACCOUNT));

    assertThat(out.get("txnId")).isEqualTo("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b");
    assertThat(out.get("msisdn")).isEqualTo(MASKED);
    assertThat(out.get("account")).isEqualTo(TOKEN);
    assertThat(out.values()).doesNotContain(MSISDN, ACCOUNT);
  }

  @Test
  void anUntaggedFieldIsRefused() {
    assertThatThrownBy(() -> policy().apply("payment", Map.of("nid", "1990123456789")))
        .isInstanceOf(UntaggedFieldException.class)
        .hasMessageContaining("nid")
        .hasMessageContaining("payment");
  }

  @Test
  void theWarehouseScopesTagEveryPublishedField() throws IOException {
    StandardEnvironment env = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"))
        .forEach(env.getPropertySources()::addLast);
    PiiPolicyProperties props = new Binder(ConfigurationPropertySources.from(env.getPropertySources()))
        .bind("mw.export.pii", PiiPolicyProperties.class)
        .get();

    assertThat(props.scopes().get("dw.txn.created.v1").get("payerToken")).isEqualTo(PiiAction.TOKENISE);
    assertThat(props.scopes().get("dw.txn.state-changed.v1").get("payeeToken")).isEqualTo(PiiAction.TOKENISE);
    assertThat(props.scopes().get("file.wallet_snapshot").get("wallet_token")).isEqualTo(PiiAction.TOKENISE);
    assertThat(props.scopes().get("dw.audit.operator-action.v1").get("actorId")).isEqualTo(PiiAction.CLEAR);
  }
}
