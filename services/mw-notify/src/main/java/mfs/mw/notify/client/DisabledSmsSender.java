package mfs.mw.notify.client;

import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.SmsPart;
import mfs.mw.notify.dto.SubmitResult;
import mfs.mw.notify.exception.SmsSendException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** With {@code mw.notify.smpp.enabled=false} there is no SMSC: every send is a transient error. */
@Component
@ConditionalOnProperty(name = "mw.notify.smpp.enabled", havingValue = "false")
public class DisabledSmsSender implements SmsSender {

  @Override
  public SubmitResult submit(Priority priority, SmsPart part) throws SmsSendException {
    throw new SmsSendException(true, -1, null, "SMPP client disabled");
  }
}
