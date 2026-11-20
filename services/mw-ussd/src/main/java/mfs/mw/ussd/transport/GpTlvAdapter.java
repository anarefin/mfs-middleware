package mfs.mw.ussd.transport;

import org.springframework.stereotype.Component;

/** Grameenphone: SMPP session-info with the end-of-session flag in bit 0. */
@Component
public final class GpTlvAdapter extends MnoTlvAdapter {

  public GpTlvAdapter() {
    super("gp", Packing.EOS_BIT0);
  }
}
