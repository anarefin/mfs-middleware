package mfs.mw.seeder.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /admin/v1/ussd-menus/{menuId}/change-requests} (admin.v1): the tree ({@code
 * start}, {@code messages}, {@code nodes}), one activation per MNO (go-live = approval time), why.
 */
public record MenuProposal(JsonNode menu, List<Activation> activations, String reason) {

  public record Activation(String mno) {}
}
