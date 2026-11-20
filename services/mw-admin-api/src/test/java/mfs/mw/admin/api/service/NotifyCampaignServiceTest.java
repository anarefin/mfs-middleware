package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** An approved campaign is a FEATURE_FLAG on mw.config.changed.v1, without the maker's reason. */
class NotifyCampaignServiceTest {

  static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

  @Test
  void approvalPublishesTheAudienceAsAFeatureFlag() {
    OutboxWriter outbox = mock(OutboxWriter.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ChangeRequestService> unused = mock(ObjectProvider.class);
    NotifyCampaignService service = new NotifyCampaignService(unused, outbox);
    UUID id = UUID.fromString("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b");
    Map<String, Object> payload = Map.of(
        "templateCode", "CAMPAIGN_PROMO",
        "locale", "bn",
        "scheduleAt", "2026-11-20T10:00:00Z",
        "throttlePerSecond", 4,
        "msisdns", List.of("01711000990"),
        "text", "a short offer");
    ChangeRequest cr = new ChangeRequest(id, NotifyCampaignService.RESOURCE, id.toString(), "CREATE", payload,
        Map.of(), "maker", "maker-sub", ChangeRequest.Status.PENDING, null, null, "the reason stays here", null,
        NOW, null, NOW);
    Operator checker = new Operator("checker-sub", "checker", java.util.Set.of());

    service.publish(cr, checker, NOW, "corr-111");

    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(outbox).append(
        org.mockito.ArgumentMatchers.eq(NotifyCampaignService.TOPIC),
        org.mockito.ArgumentMatchers.eq(NotifyCampaignService.KEY_PREFIX + id),
        org.mockito.ArgumentMatchers.any(EventMetadata.class),
        event.capture());
    ConfigChangedEvent published = (ConfigChangedEvent) event.getValue();
    assertThat(published.configType()).isEqualTo(ConfigChangedEvent.ConfigType.FEATURE_FLAG);
    assertThat(published.value()).containsEntry("msisdns", List.of("01711000990"));
    assertThat(published.value()).doesNotContainKey("reason");
    assertThat(published.approvedBy()).isEqualTo("checker");
  }

  @Test
  void aNumberWithACountryPrefixIsCanonical() {
    assertThat(NotifyCampaignService.canonical("+8801711000990")).isEqualTo("01711000990");
    assertThat(NotifyCampaignService.canonical("not-a-number")).isNull();
  }
}
