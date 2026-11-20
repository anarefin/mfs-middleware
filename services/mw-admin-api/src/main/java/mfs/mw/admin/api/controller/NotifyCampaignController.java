package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.NotifyCampaignBody;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.NotifyCampaignService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /admin/v1/notify-campaigns} (ticket 111, NOT-07). Approve and reject are the generic
 * change-request endpoints. The role is checked here by {@code config/SecurityConfig} and again by
 * the service.
 */
@RestController
@RequestMapping("/admin/v1/notify-campaigns")
public class NotifyCampaignController {

  private final NotifyCampaignService campaigns;
  private final OperatorResolver operators;

  public NotifyCampaignController(NotifyCampaignService campaigns, OperatorResolver operators) {
    this.campaigns = campaigns;
    this.operators = operators;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView propose(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NotifyCampaignBody body) {
    return campaigns.propose(operators.resolve(jwt), body);
  }
}
