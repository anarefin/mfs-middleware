package mfs.mw.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.auth.dto.WalletStateChanged;
import mfs.mw.auth.exception.MwException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The core's wallet types and statuses of FRS 7.1 (ticket 94) as the projection reads them. */
class WalletEventMapperTest {

  private final WalletEventMapper mapper = new WalletEventMapper(JsonMapper.builder().build());

  private static String event(String walletType, String status) {
    return """
        {"schemaVersion":1,"eventType":"WalletStateChanged","eventId":"6b1d3c2e-4f5a-4b6c-8d7e-9f0a1b2c3d40",
         "commandId":"wallet-state:01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7aa0","txnId":"01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7aa0",
         "coreRef":"WALLET-V17","occurredAt":"2026-11-14T08:15:31Z","outcome":"SUCCESS",
         "payload":{"walletId":"01311000003","msisdn":"01311000003","walletType":"%s","status":"%s","kycLevel":"FULL",
                    "hasPin":false,"version":17,"change":"CREATED"}}
        """.formatted(walletType, status);
  }

  @Test
  void anOrganisationIsProjectedAsThisServicesOrgType() {
    WalletStateChanged e = mapper.read(event("ORGANISATION", "ACTIVE")).orElseThrow();

    assertThat(e.payload().walletType()).isEqualTo("ORG");
    assertThat(mapper.read(event("DISTRIBUTOR", "ACTIVE")).orElseThrow().payload().walletType()).isEqualTo("DISTRIBUTOR");
  }

  @Test
  void dormantAndClosedWalletsAreProjected() {
    assertThat(mapper.read(event("CUSTOMER", "DORMANT")).orElseThrow().payload().status()).isEqualTo("DORMANT");
    assertThat(mapper.read(event("CUSTOMER", "CLOSED")).orElseThrow().payload().status()).isEqualTo("CLOSED");
  }

  @Test
  void anUnknownStatusOrTypeIsMalformed() {
    assertThatThrownBy(() -> mapper.read(event("CUSTOMER", "FROZEN"))).isInstanceOf(MwException.class);
    assertThatThrownBy(() -> mapper.read(event("SYSTEM", "ACTIVE"))).isInstanceOf(MwException.class);
  }
}
