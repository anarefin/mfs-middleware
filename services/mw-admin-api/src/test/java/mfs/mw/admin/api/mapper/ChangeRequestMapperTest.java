package mfs.mw.admin.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.entity.ChangeRequest;
import org.junit.jupiter.api.Test;

/**
 * The admin view of a change request never shows a customer's number in the operators' free text
 * (security review of ticket 37): reasons and any free text in payload / diff are scrubbed; there is
 * no reveal path for change requests.
 */
class ChangeRequestMapperTest {

  private static final Instant AT = Instant.parse("2026-11-05T10:00:00Z");

  private static ChangeRequest request(Map<String, Object> payload, Map<String, Object> diff) {
    return new ChangeRequest(
        UUID.fromString("7d4b9c8e-1f2a-4b3c-9d8e-0a1b2c3d4e5f"), ChangeRequest.TRANSACTION,
        "3f1e2d4c-5b6a-4789-8abc-def012345678", "FORCE_COMMIT", payload, diff, "ops-maker01", "sub-m",
        ChangeRequest.Status.REJECTED, "ops-checker01", "sub-c",
        "customer 01712345789 called, NID 1234567890123", "checked with 01812345678 first",
        AT, AT.plusSeconds(60), AT.plusSeconds(3600));
  }

  @Test
  void theViewScrubsTheMakersAndCheckersReasons() {
    ChangeRequestView v = ChangeRequestMapper.view(request(Map.of(), Map.of()));

    assertThat(v.reason()).isEqualTo("customer 0171****789 called, NID ****123");
    assertThat(v.decisionReason()).isEqualTo("checked with 0181****678 first");
  }

  @Test
  void theViewScrubsFreeTextInPayloadAndDiffAtAnyDepthAndKeepsTheRest() {
    Map<String, Object> payload = Map.of(
        "note", "refund to 01712345789",
        "nested", Map.of("items", List.of("acct 1234567890123456", 42)),
        "amount", Map.of("value", 5000, "currency", "BDT"));
    Map<String, Object> diff = Map.of(
        "before", Map.of("status", "MANUAL_REVIEW", "comment", "payer 01912345678"),
        "after", Map.of("status", "COMMITTING"));

    ChangeRequestView v = ChangeRequestMapper.view(request(payload, diff));

    assertThat(v.payload().get("note")).isEqualTo("refund to 0171****789");
    assertThat(v.payload().get("nested")).isEqualTo(Map.of("items", List.of("acct ****3456", 42)));
    assertThat(v.payload().get("amount")).isEqualTo(Map.of("value", 5000, "currency", "BDT"));
    assertThat(v.diff().get("before")).isEqualTo(Map.of("status", "MANUAL_REVIEW", "comment", "payer 0191****678"));
    assertThat(v.diff().get("after")).isEqualTo(Map.of("status", "COMMITTING"));
    assertThat(v.resourceId()).isEqualTo("3f1e2d4c-5b6a-4789-8abc-def012345678");
  }
}
