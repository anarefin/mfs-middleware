package mfs.mw.orchestrator.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.service.saga.InquirySchedule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Status inquiry of UNKNOWN outcomes under {@code mw.orchestrator.inquiry} (FRS PLT-023/024, spec
 * 7.4, ticket 96a).
 *
 * @param schedule the wait before the first, second, ... inquiry (30 s, 2 min, 10 min, 1 h); after
 *     the last one MANUAL_REVIEW
 * @param answerWait how long the answer to the last inquiry is awaited before MANUAL_REVIEW (30 s)
 * @param notFoundGrace how long after the original call could last have arrived a "not found"
 *     answer is believed: partner = PARTNER_PENDING entry + {@code partner-timeout} + grace, core =
 *     POSTING entry + {@code command-ttl} + grace (the adapter and the core drop expired requests)
 * @param partners per-partner overrides by {@code partnerId}: only their own list of waits
 */
@Validated
@ConfigurationProperties("mw.orchestrator.inquiry")
public record InquiryProperties(
    @NotEmpty @DefaultValue({"30s", "2m", "10m", "1h"}) List<Duration> schedule,
    @NotNull @DefaultValue("30s") Duration answerWait,
    @NotNull @DefaultValue("30s") Duration notFoundGrace,
    Map<String, PartnerOverride> partners) {

  /** Per-partner schedule (spec 11 "TSQ schedule"): the partner's own list of waits. */
  public record PartnerOverride(List<Duration> schedule) {}

  public InquiryProperties {
    partners = partners == null ? Map.of() : Map.copyOf(partners);
  }

  /** The schedule for {@code partnerId} (null for transactions without a partner: the default). */
  public InquirySchedule scheduleFor(String partnerId) {
    InquirySchedule base = new InquirySchedule(schedule, answerWait);
    PartnerOverride o = partnerId == null ? null : partners.get(partnerId);
    return o == null || o.schedule() == null || o.schedule().isEmpty() ? base : base.withDelays(o.schedule());
  }
}
