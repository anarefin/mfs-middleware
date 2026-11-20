package mfs.mw.notify.messaging;

import mfs.mw.notify.client.InboundSmsHandler;
import mfs.mw.notify.service.OptOutService;
import org.springframework.stereotype.Component;

/** Hands a mobile-originated SMS to {@link OptOutService} (the STOP keyword, ticket 111). */
@Component
public class InboundOptOut implements InboundSmsHandler {

  private final OptOutService optOuts;

  public InboundOptOut(OptOutService optOuts) {
    this.optOuts = optOuts;
  }

  @Override
  public void onMessage(String source, String text) {
    optOuts.onInboundSms(source, text);
  }
}
