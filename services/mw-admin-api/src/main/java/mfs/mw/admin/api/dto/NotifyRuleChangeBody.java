package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /admin/v1/notify-rules/{ruleId}/change-requests} (ticket 133c, FRS NOT-01): a new
 * version of one notification rule. Checked against the contract first ({@code
 * controller/BodySchemaAdvice}), then by {@code service/ContentChangeService}.
 *
 * @param eventType the txnType of {@code mw.txn.status.v1}, e.g. P2P
 * @param outcome SUCCESS, FAILURE or PROCESSING
 * @param customerType the recipient's wallet type, ORG, or {@code *} (any; the default)
 * @param recipient INITIATOR, DEBIT_PARTY, CREDIT_PARTY or OPS
 * @param channels SMS, EMAIL, PUSH: each one message
 * @param templateCode the template sent
 * @param enabled false stops the rule; true when absent
 * @param reason why (free text; never logged, scrubbed in the audit)
 */
public record NotifyRuleChangeBody(
    @NotNull String eventType,
    @NotNull String outcome,
    String customerType,
    @NotNull String recipient,
    @NotNull @Size(min = 1, max = 3) List<String> channels,
    @NotNull String templateCode,
    Boolean enabled,
    @NotBlank @Size(max = 500) String reason) {}
