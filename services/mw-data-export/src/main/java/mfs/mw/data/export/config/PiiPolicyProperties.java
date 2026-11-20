package mfs.mw.data.export.config;

import jakarta.validation.constraints.NotNull;
import java.util.Map;
import mfs.mw.data.export.service.PiiAction;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Deny-by-default PII tags (ticket 128), under {@code mw.export.pii.scopes}. A scope is one DW
 * topic or one batch file. A field with no tag is refused.
 *
 * @param scopes scope name to field name to action
 */
@Validated
@ConfigurationProperties("mw.export.pii")
public record PiiPolicyProperties(@NotNull Map<String, Map<String, PiiAction>> scopes) {}
