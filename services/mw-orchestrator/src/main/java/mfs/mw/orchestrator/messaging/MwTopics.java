package mfs.mw.orchestrator.messaging;

/**
 * Topic names and the retry/DLQ naming rule that the service layer needs too (root CLAUDE.md:
 * {@code service} may use {@code messaging/} constants, never {@code route/}). The routes refer to
 * these.
 */
public final class MwTopics {

  /** Transaction requests from the channels and operations (spec 6.2). */
  public static final String TXN_REQUEST = "mw.txn.request.v1";

  private MwTopics() {}

  /** Dead-letter topic of {@code group} for {@code topic}. */
  public static String dlqTopic(String topic, String group) {
    return topic + "." + group + ".dlq";
  }
}
