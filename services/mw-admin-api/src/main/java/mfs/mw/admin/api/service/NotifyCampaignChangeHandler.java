package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.Set;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import org.springframework.stereotype.Component;

/** Promotional campaigns ({@code notify-campaign}, ticket 111). A checker is {@code ops-checker}. */
@Component
public class NotifyCampaignChangeHandler implements ResourceChangeHandler {

  private final NotifyCampaignService campaigns;

  public NotifyCampaignChangeHandler(NotifyCampaignService campaigns) {
    this.campaigns = campaigns;
  }

  @Override
  public String resourceType() {
    return NotifyCampaignService.RESOURCE;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.CONTENT_CHECKER;
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    return new Approval(cr.payload(), NotifyCampaignService.TOPIC,
        () -> campaigns.publish(cr, checker, now, correlationId));
  }
}
