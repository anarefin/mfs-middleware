package mfs.mw.notify.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import mfs.mw.notify.dto.OptOutEvent;
import mfs.mw.notify.repository.OptOutRepository;
import mfs.mw.notify.util.MaskingPolicy;
import mfs.mw.notify.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Records a promotional opt-out from the app or from an SMS {@code STOP} (ticket 111, NOT-09). */
@Service
public class OptOutService {

  private static final Logger log = LoggerFactory.getLogger(OptOutService.class);

  private final OptOutRepository optOuts;
  private final Clock clock;

  public OptOutService(OptOutRepository optOuts, Clock clock) {
    this.optOuts = optOuts;
    this.clock = clock;
  }

  /** The app event. A wallet id that is not an MSISDN is ignored. Channel is SMS or APP. */
  public void onOptOut(OptOutEvent event) {
    String channel = "SMS".equals(event.channel()) ? "SMS" : "APP";
    record(event.walletId(), channel, event.optedOutAt() == null ? clock.instant() : event.optedOutAt());
  }

  /** A mobile-originated SMS. Only the keyword STOP is an opt-out. */
  public void onInboundSms(String source, String text) {
    Optional<String> msisdn = InboundSms.stop(source, text);
    msisdn.ifPresent(m -> record(m, "SMS", clock.instant()));
  }

  /** Whether a PROMO to this MSISDN must be suppressed. OTP and TXN are never blocked. */
  public boolean blocksPromo(mfs.mw.notify.dto.Priority priority, String msisdn) {
    return PromoOptOut.blocks(priority, msisdn != null && optOuts.optedOut(msisdn));
  }

  private void record(String msisdn, String channel, Instant since) {
    optOuts.record(msisdn, channel, since);
    log.atInfo()
        .addKeyValue("event", "notify.optout.recorded")
        .addKeyValue("channel", channel)
        .addKeyValue("recipient", MaskingPolicy.mask(SensitiveType.MSISDN, msisdn))
        .log("promotional opt-out recorded");
  }
}
