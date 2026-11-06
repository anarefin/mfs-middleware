package mfs.mw.seeder.client;

import java.util.List;
import java.util.Optional;
import mfs.mw.seeder.dto.ChangeRequestView;
import mfs.mw.seeder.dto.ContentVersionView;
import mfs.mw.seeder.dto.MenuProposal;
import mfs.mw.seeder.dto.PartnerChangeRequest;
import mfs.mw.seeder.dto.PartnerView;
import mfs.mw.seeder.dto.TemplateProposal;

/**
 * The mw-admin-api calls the seeder makes (implemented by {@link AdminApiClient}), each as the ops
 * user {@code asUser} (bearer token of realm ops).
 */
public interface AdminConsole {

  Optional<PartnerView> partner(String asUser, String partnerId);

  ChangeRequestView requestPartnerChange(String asUser, String partnerId, PartnerChangeRequest request);

  /** PENDING change requests on one resource ({@code resourceType} partner, ussd_menu, ...). */
  List<ChangeRequestView> pendingRequests(String asUser, String resourceType, String resourceId);

  ChangeRequestView approve(String asUser, String changeRequestId);

  /** Versions made through the admin API, newest first. */
  List<ContentVersionView> menuVersions(String asUser, String menuId);

  ChangeRequestView proposeMenu(String asUser, String menuId, MenuProposal proposal);

  List<ContentVersionView> templateVersions(String asUser, String code, String channel, String locale);

  ChangeRequestView proposeTemplate(String asUser, String code, String channel, String locale, TemplateProposal proposal);
}
