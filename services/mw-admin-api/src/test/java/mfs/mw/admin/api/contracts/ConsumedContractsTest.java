package mfs.mw.admin.api.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import mfs.mw.admin.api.dto.PartnerRouteStatusMessage;
import mfs.mw.admin.api.repository.PartnerRepository;
import mfs.mw.admin.api.service.PartnerRouteStatusService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every event this module consumes ({@code consumes.events} in {@code contracts.yml}, ticket 83):
 * each valid contract sample deserialises into the module's own type as the listener reads it and
 * is accepted by the consumer's own check. No Spring context, no Docker.
 */
class ConsumedContractsTest {

  static final String ROUTE_STATUS = "events/mw-adapter-billpay/mw.partner.route.status.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  @Test
  void theManifestListsExactlyTheEventsWithAReader() {
    assertThat(ContractManifest.load().consumedEvents()).containsExactly(ROUTE_STATUS);
  }

  @Test
  void everyValidRouteStatusSampleIsReadAndApplied() throws Exception {
    PartnerRepository partners = mock(PartnerRepository.class);
    when(partners.updateRouteStatus(anyString(), anyLong(), anyString(), any(), any(), any())).thenReturn(true);
    PartnerRouteStatusService service = new PartnerRouteStatusService(partners, Clock.systemUTC());
    List<Path> samples = ContractManifest.validSamples(ROUTE_STATUS);
    assertThat(samples).isNotEmpty();
    for (Path sample : samples) {
      PartnerRouteStatusMessage m = JSON.readValue(Files.readString(sample), PartnerRouteStatusMessage.class);
      service.apply(m);
      assertThat(m.partnerId()).as("%s", sample).isNotBlank();
    }
  }
}
