package mfs.mw.ussd.transport;

import org.springframework.stereotype.Component;

/** Robi: session-info with the end-of-session flag in bit 7 and the sequence in bits 6..0. */
@Component
public final class RobiTlvAdapter extends MnoTlvAdapter {

  public RobiTlvAdapter() {
    super("robi", Packing.EOS_BIT7);
  }
}
