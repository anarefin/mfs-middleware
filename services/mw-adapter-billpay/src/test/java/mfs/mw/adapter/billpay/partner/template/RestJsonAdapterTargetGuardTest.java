package mfs.mw.adapter.billpay.partner.template;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.billpay.partner.CredentialRef;
import mfs.mw.adapter.billpay.partner.ExchangeRecord;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerContext;
import mfs.mw.adapter.billpay.partner.PartnerNotSentException;
import mfs.mw.adapter.billpay.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.billpay.partner.auth.ApiKeyAuthScheme;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Defence in depth behind config validation: even with an operation path that slipped past it
 * (the adapter is driven directly here, with a config that was never validated), the adapter
 * never sends a request, nor its credentials, to any scheme, host or port other than the
 * partner's validated baseUrl. Such a call is refused unsent (MISCONFIGURED).
 */
class RestJsonAdapterTargetGuardTest {

  WireMockServer partner;
  WireMockServer evil;

  @BeforeEach
  void start() {
    partner = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    evil = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    partner.start();
    evil.start();
    partner.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    evil.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
  }

  @AfterEach
  void stop() {
    partner.stop();
    evil.stop();
  }

  @ParameterizedTest
  @ValueSource(strings = {"@localhost:EVIL/x", ":EVIL/x", "@127.0.0.1:EVIL/x", ".evil.invalid/x"})
  void aPathThatWouldChangeTheTargetIsNeverSent(String template) {
    String path = template.replace("EVIL", Integer.toString(evil.port()));
    PartnerConfig config =
        new PartnerConfig(
            true, PartnerTemplates.REQUEST_REPLY, URI.create("http://localhost:" + partner.port()), 500L, 2000L,
            null, Map.of(), new PartnerConfig.Auth("api-key", "api-key", Map.of()),
            Map.of("PAY", new PartnerConfig.Operation("POST", path, List.of(422), null, "{\"outcome\": \"UNKNOWN\"}")),
            Map.of(), null, null, null);
    PartnerContext ctx =
        new PartnerContext(
            "acme", config, new ApiKeyAuthScheme("X-Api-Key", ref -> "fake-key-not-real"),
            CredentialRef.of("acme", "api-key"), HttpClient.newHttpClient(), new ExchangeRecord());
    RestJsonAdapter adapter = new RestJsonAdapter(PartnerTemplates.REQUEST_REPLY, PartnerFramework.JSON);
    PartnerCall call = new PartnerCall("acme", "PAY", "BILL_PAY", "t", "c", "R", Map.of());

    assertThatThrownBy(() -> adapter.execute(call, ctx))
        .as(path)
        .isInstanceOfSatisfying(
            PartnerNotSentException.class, e -> assertThat(e.reason()).isEqualTo(NotSentReason.MISCONFIGURED));
    evil.verify(0, anyRequestedFor(anyUrl()));
  }
}
