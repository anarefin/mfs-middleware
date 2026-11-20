package mfs.mw.ussd.transport;

import org.springframework.stereotype.Component;

/** Teletalk: the same session-info packing as Robi (end-of-session in bit 7). */
@Component
public final class TeletalkTlvAdapter extends MnoTlvAdapter {

  public TeletalkTlvAdapter() {
    super("teletalk", Packing.EOS_BIT7);
  }
}
