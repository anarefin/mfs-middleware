package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * {@code POST /admin/v1/notify-templates/{code}/{channel}/{locale}/change-requests} (ticket 39):
 * a new version of one notify template (spec 10.3). The body is checked against the contract
 * first ({@code filter/BodySchemaAdvice}), then by {@code service/TemplateValidator}.
 *
 * @param body logic-less {@code {{name}}} text (SMS text, or the EMAIL text part)
 * @param subject EMAIL only, required there
 * @param htmlBody EMAIL only, optional HTML part
 * @param senderId SMS sender id / short code; mw-notify's default ({@code upay}) when absent
 * @param validFrom when the version takes over; the approval time when absent or earlier
 * @param reason why (free text; never logged, scrubbed in the audit)
 */
public record TemplateChangeBody(
    @NotNull @Size(max = 4000) String body,
    @Size(max = 200) String subject,
    @Size(max = 20000) String htmlBody,
    @Size(max = 11) String senderId,
    Instant validFrom,
    @NotBlank @Size(max = 500) String reason) {}
