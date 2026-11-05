package mfs.mw.notify.client;

import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.SmsPart;
import mfs.mw.notify.dto.SubmitResult;
import mfs.mw.notify.exception.SmsSendException;

/** Sends one SMS part to the SMSC on a bind of the priority's pool (spec 10.1, 10.2). */
public interface SmsSender {

  SubmitResult submit(Priority priority, SmsPart part) throws SmsSendException;

  /** Keeps the connections up between sends (so DLRs can arrive); called every dispatch cycle. */
  default void maintain() {
    // nothing to keep up by default
  }
}
