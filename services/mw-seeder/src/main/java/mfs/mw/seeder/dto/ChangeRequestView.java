package mfs.mw.seeder.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** A maker-checker change request (admin.v1 {@code ChangeRequest}); the fields the seeder reads. */
public record ChangeRequestView(
    String id, String resourceType, String resourceId, String action, String status, String makerId, JsonNode payload) {

  /** admin.v1 {@code ChangeRequestPage}. */
  public record Page(List<ChangeRequestView> items) {}
}
