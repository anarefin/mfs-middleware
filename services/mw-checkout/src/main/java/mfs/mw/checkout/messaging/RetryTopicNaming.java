package mfs.mw.checkout.messaging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.kafka.config.KafkaListenerEndpoint;
import org.springframework.kafka.retrytopic.DestinationTopic;
import org.springframework.kafka.retrytopic.RetryTopicNamesProviderFactory;
import org.springframework.kafka.support.Suffixer;

/**
 * The platform's retry and DLQ names for Spring Kafka's retry topics (spec 6.2, 19.2, ADR D27):
 * retry {@code n} (1-based) of a consumed topic is {@code <topic>.<group>.retry.<n>} and its
 * dead-letter topic {@code <topic>.<group>.dlq}, {@code <group>} being this service's consumer
 * group (its name), so services consuming one topic never share parked records. Their consumer
 * groups are {@code <group>.retry.<n>} (and {@code <group>.dlq} for the DLQ container, which
 * never starts). The topics are declared in {@code deploy/kafka/topics.txt} ({@code
 * retry=<group>}); nothing here creates them.
 *
 * <p>Spring Kafka names a retry topic with the configured suffix and a 0-based index ({@code
 * config/KafkaConfig} sets {@link #RETRY_SUFFIX} with index suffixing, giving {@code
 * .retry-0}); this factory turns that into the 1-based platform name.
 */
public final class RetryTopicNaming implements RetryTopicNamesProviderFactory {

  /** Retry suffix handed to Spring Kafka; Spring Kafka appends {@code -<index>}. */
  public static final String RETRY_SUFFIX = ".retry";
  /** Dead-letter suffix handed to Spring Kafka. */
  public static final String DLQ_SUFFIX = ".dlq";

  private static final Pattern INDEXED_RETRY = Pattern.compile(Pattern.quote(RETRY_SUFFIX) + "-(\\d+)");
  private static final Pattern RETRY_TOPIC = Pattern.compile(".+\\.retry\\.(\\d+)");

  private final String group;

  /** @param group this service's consumer group, the middle part of its retry and DLQ topic names */
  public RetryTopicNaming(String group) {
    this.group = group;
  }

  /** Retry topic {@code n} (1-based) of {@code group} for {@code topic}. */
  public static String retryTopic(String topic, String group, int n) {
    return topic + "." + group + RETRY_SUFFIX + "." + n;
  }

  /** Dead-letter topic of {@code group} for {@code topic}. */
  public static String dlqTopic(String topic, String group) {
    return topic + "." + group + DLQ_SUFFIX;
  }

  /** The retry number {@code n} of a {@code <topic>.<group>.retry.<n>} topic; 0 for any other. */
  public static int retryNumber(String topic) {
    Matcher m = RETRY_TOPIC.matcher(topic);
    return m.matches() ? Integer.parseInt(m.group(1)) : 0;
  }

  @Override
  public RetryTopicNamesProvider createRetryTopicNamesProvider(DestinationTopic.Properties properties) {
    String suffix = properties.suffix(); // "" (main), ".retry-<index>" or ".dlq"
    String nameSuffix; // appended to the endpoint id, group id, client id prefix and container group
    if (properties.isDltTopic()) {
      nameSuffix = DLQ_SUFFIX;
    } else if (suffix.isEmpty()) {
      nameSuffix = "";
    } else {
      Matcher m = INDEXED_RETRY.matcher(suffix);
      if (!m.matches()) {
        throw new IllegalStateException("unexpected retry topic suffix " + suffix + ": index suffixing is required");
      }
      nameSuffix = RETRY_SUFFIX + "." + (Integer.parseInt(m.group(1)) + 1);
    }
    Suffixer names = new Suffixer(nameSuffix);
    Suffixer topics = new Suffixer(nameSuffix.isEmpty() ? "" : "." + group + nameSuffix);
    return new RetryTopicNamesProvider() {
      @Override
      public String getEndpointId(KafkaListenerEndpoint endpoint) {
        return names.maybeAddTo(endpoint.getId());
      }

      @Override
      public String getGroupId(KafkaListenerEndpoint endpoint) {
        return names.maybeAddTo(endpoint.getGroupId());
      }

      @Override
      public String getClientIdPrefix(KafkaListenerEndpoint endpoint) {
        return names.maybeAddTo(endpoint.getClientIdPrefix());
      }

      @Override
      public String getGroup(KafkaListenerEndpoint endpoint) {
        return names.maybeAddTo(endpoint.getGroup());
      }

      @Override
      public String getTopicName(String topic) {
        return topics.maybeAddTo(topic);
      }
    };
  }
}
