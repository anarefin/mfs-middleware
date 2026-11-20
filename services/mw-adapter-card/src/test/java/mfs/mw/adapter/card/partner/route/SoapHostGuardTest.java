package mfs.mw.adapter.card.partner.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.card.partner.PartnerTestSeeds;
import mfs.mw.adapter.card.partner.auth.PartnerTlsContexts;
import mfs.mw.adapter.card.partner.config.PartnerConfig;
import mfs.mw.adapter.card.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.card.partner.config.PartnerProperties;
import org.junit.jupiter.api.Test;

/** The send-time host check of a SOAP call (ticket 85): the {@code cxf} endpoint of mw-soap-call only. */
class SoapHostGuardTest {

  final PartnerHostGuard guard =
      new PartnerHostGuard(new PartnerEndpointPolicy(new PartnerProperties.Security(Map.of(), List.of("mw-simulators"))), PartnerTestSeeds.noTlsContexts());
  static final Map.Entry<String, PartnerConfig> SEED = PartnerTestSeeds.devSeed();

  final PartnerRuntime partner =
      new PartnerRuntime(SEED.getKey(), 0, SEED.getValue(), "partner-x-v0", "direct:partner-x-v0",
          Map.of(), Map.of(), List.of("mw-simulators", "93.184.215.14"), Set.of());

  static final String OPTIONS = "?dataFormat=PAYLOAD&headerFilterStrategy=%23mwSoapHeaderFilter";
  static final String SOAP12 = "&bindingId=http://www.w3.org/2003/05/soap/bindings/HTTP/";

  @Test
  void theKameletsEndpointsOnAnApprovedHostPass() {
    assertThat(guard.soapProblem(partner, "cxf://http://mw-simulators:8080/soapbill" + OPTIONS, null)).isNull();
    assertThat(guard.soapProblem(partner, "cxf://https://93.184.215.14/ws" + OPTIONS + SOAP12, null)).isNull();
    assertThat(guard.soapProblem(partner, "cxf://https://93.184.215.14/ws?bindingId=http%3A%2F%2Fwww.w3.org%2F2003%2F05%2Fsoap%2Fbindings%2FHTTP%2F"
        + "&dataFormat=PAYLOAD&headerFilterStrategy=%23mwSoapHeaderFilter", null)).isNull();
  }

  @Test
  void anotherHostPlainHttpAnOverrideOrACallOutsideAPartnerIsRefused() {
    assertThat(guard.soapProblem(partner, "cxf://https://evil.example/ws" + OPTIONS, null)).contains("allowedHosts");
    assertThat(guard.soapProblem(partner, "cxf://http://93.184.215.14/ws" + OPTIONS, null)).contains("plain http");
    assertThat(guard.soapProblem(partner, "cxf://http://mw-simulators:8080/soapbill" + OPTIONS, "https://evil.example"))
        .contains("CamelDestinationOverrideUrl");
    assertThat(guard.soapProblem(null, "cxf://http://mw-simulators:8080/soapbill" + OPTIONS, null)).contains("outside a partner call");
    assertThat(guard.soapProblem(partner, "cxf:bean:evil" + OPTIONS, null)).isNotNull();
  }

  @Test
  void anyEndpointOptionOutsideTheFixedSetIsRefused() {
    assertThat(guard.soapProblem(partner, "cxf://http://mw-simulators:8080/soapbill" + OPTIONS + "&wsdlURL=http://evil.example/x", null))
        .contains("option");
    assertThat(guard.soapProblem(partner, "cxf://http://mw-simulators:8080/soapbill?dataFormat=RAW&headerFilterStrategy=%23mwSoapHeaderFilter", null))
        .contains("option");
    assertThat(guard.soapProblem(partner, "cxf://http://mw-simulators:8080/soapbill?dataFormat=PAYLOAD&loggingFeatureEnabled=true", null))
        .contains("option");
  }

  /** Review of 85: mw-soap-call's mTLS, held to the rules of mw-http-call's (ticket 87). */
  @Test
  void anMtlsSoapCallMayNameOnlyAContextOfThePartnersOwnOverHttps() {
    PartnerTlsContexts tls = org.mockito.Mockito.mock(PartnerTlsContexts.class);
    org.mockito.Mockito.when(tls.owner(org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.Optional.empty());
    org.mockito.Mockito.when(tls.owner("own-client")).thenReturn(java.util.Optional.of(SEED.getKey()));
    org.mockito.Mockito.when(tls.owner("other-client")).thenReturn(java.util.Optional.of("someone-else"));
    PartnerHostGuard withTls =
        new PartnerHostGuard(new PartnerEndpointPolicy(new PartnerProperties.Security(Map.of(), List.of("mw-simulators"))), tls);

    assertThat(withTls.soapProblem(partner, "cxf://https://93.184.215.14/ws" + OPTIONS + "&sslContextParameters=%23mwTls-own-client", null))
        .isNull();
    assertThat(withTls.soapProblem(partner, "cxf://https://93.184.215.14/ws" + OPTIONS + "&sslContextParameters=%23mwTls-other-client", null))
        .contains("TLS client context");
    assertThat(withTls.soapProblem(partner, "cxf://https://93.184.215.14/ws" + OPTIONS + "&sslContextParameters=%23evil", null))
        .contains("TLS client context");
    assertThat(withTls.soapProblem(partner, "cxf://http://mw-simulators:8080/soapbill" + OPTIONS
        + "&sslContextParameters=%23mwTls-own-client", null)).contains("https");
  }
}
