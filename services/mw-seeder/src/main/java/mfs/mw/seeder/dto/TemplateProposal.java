package mfs.mw.seeder.dto;

/**
 * Body of {@code POST /admin/v1/notify-templates/{code}/{channel}/{locale}/change-requests}
 * (admin.v1). Null fields are left out of the JSON (SMS has no subject).
 */
public record TemplateProposal(String body, String subject, String senderId, String reason) {}
