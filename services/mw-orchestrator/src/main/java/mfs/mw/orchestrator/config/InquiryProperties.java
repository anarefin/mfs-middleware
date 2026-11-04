package mfs.mw.orchestrator.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.orchestrator.service.saga.InquirySchedule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Status inquiry of UNKNOWN outcomes under {@code mw.orchestrator.inquiry} (spec 7.4).
 *
 * @param schedule the wait before the first, second, ... inquiry (30 s, 1 min, 2 min, 5 min, 15 min,
 *     1 h)
 * @param repeatEvery the wait between inquiries after {@code schedule} (6 h)
 * @param cutOff how long after entering UNKNOWN inquiries stop and MANUAL_REVIEW starts (stands in
 *     for the partner's settlement cut-off)
 * @param notFoundGrace how long after the original call could last have arrived a "not found"
 *     answer is believed: partner = PARTNER_PENDING entry + {@code partner-timeout} + grace, core =
 *     POSTING entry + {@code command-ttl} + grace (the adapter and the core drop expired requests)
 * @param partners per-partner overrides by {@code partnerId}; unset parts keep the defaults
 */
@Validated
@ConfigurationProperties("mw.orchestrator.inquiry")
public record InquiryProperties(
    @NotNull @DefaultValue({"30s", "1m", "2m", "5m", "15m", "1h"}) List<Duration> schedule,
    @NotNull @DefaultValue("6h") Duration repeatEvery,
    @NotNull @DefaultValue("24h") Duration cutOff,
    @NotNull @DefaultValue("30s") Duration notFoundGrace,
    Map<String, PartnerOverride> partners) {

  /** Per-partner schedule (spec 11 "TSQ schedule"); null parts keep the default. */
  public record PartnerOverride(List<Duration> schedule, Duration repeatEvery, Duration cutOff) {}

  public InquiryProperties {
    partners = partners == null ? Map.of() : Map.copyOf(partners);
  }

  /** The schedule for {@code partnerId} (null for transactions without a partner: the default). */
  public InquirySchedule scheduleFor(String partnerId) {
    InquirySchedule base = new InquirySchedule(schedule, repeatEvery, cutOff);
    PartnerOverride o = partnerId == null ? null : partners.get(partnerId);
    if (o == null) {
      return base;
    }
    return base.overriddenBy(
        Optional.ofNullable(o.schedule()), Optional.ofNullable(o.repeatEvery()), Optional.ofNullable(o.cutOff()));
  }
}
