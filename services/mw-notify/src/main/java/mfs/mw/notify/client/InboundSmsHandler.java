package mfs.mw.notify.client;

/** A mobile-originated {@code deliver_sm} (not a delivery receipt). */
public interface InboundSmsHandler {

  InboundSmsHandler NONE = (source, text) -> {};

  void onMessage(String source, String text);
}
