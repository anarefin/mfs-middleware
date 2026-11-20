package mfs.mw.data.export.client;

import mfs.mw.data.export.exception.ErrorCodes;
import mfs.mw.data.export.exception.MwException;
import mfs.mw.data.export.service.FieldTokeniser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Present when mw-crypto is switched off, so the context still has one {@link FieldTokeniser}. */
@Component
@ConditionalOnProperty(name = "mw.export.crypto.enabled", havingValue = "false")
public class OfflineFieldTokeniser implements FieldTokeniser {

  @Override
  public String tokenise(String domain, String value) {
    throw new MwException(ErrorCodes.SYS_UPSTREAM, "mw-crypto tokeniser is off");
  }
}
