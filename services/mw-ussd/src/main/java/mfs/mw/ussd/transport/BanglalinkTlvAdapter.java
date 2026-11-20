package mfs.mw.ussd.transport;

import org.springframework.stereotype.Component;

/** Banglalink: the same session-info packing as Grameenphone (end-of-session in bit 0). */
@Component
public final class BanglalinkTlvAdapter extends MnoTlvAdapter {

  public BanglalinkTlvAdapter() {
    super("bl", Packing.EOS_BIT0);
  }
}
