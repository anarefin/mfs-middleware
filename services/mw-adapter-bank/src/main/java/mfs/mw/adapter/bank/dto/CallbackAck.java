package mfs.mw.adapter.bank.dto;

/**
 * Answer to a partner callback, sent only once the event is on Kafka (spec 11.2): {@code
 * ACCEPTED}, with {@code duplicate} true when the same callback was taken before.
 */
public record CallbackAck(String status, String inboundId, boolean duplicate) {

  public static CallbackAck accepted(String inboundId, boolean duplicate) {
    return new CallbackAck("ACCEPTED", inboundId, duplicate);
  }
}
