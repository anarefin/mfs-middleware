package mfs.mw.service.template.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import mfs.mw.service.template.PostgresOnlyInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Kafka down: a publish fails within the configured bounds instead of blocking for the client
 * defaults. The observed {@code KafkaTemplate} asks the admin client for the cluster id on its
 * first send (60 s by default), then the producer waits for metadata ({@code max.block.ms}, 60 s
 * by default); {@code application.yml} bounds both.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.kafka.bootstrap-servers=127.0.0.1:1")
@Import(PostgresOnlyInfrastructure.class)
@DirtiesContext
class KafkaUnavailableIT {

  private final EventPublisher publisher;

  @Autowired
  KafkaUnavailableIT(EventPublisher publisher) {
    this.publisher = publisher;
  }

  @Test
  void publishFailsFastWhenKafkaIsDown() {
    long start = System.nanoTime();

    assertThatThrownBy(
            () ->
                publisher.publish(
                    "it.kafka.down.v1",
                    "TXN-DOWN",
                    new EventMetadata("corr-down", "TXN-DOWN", "idem-down", 1),
                    Map.of()))
        .isInstanceOf(RuntimeException.class);

    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(20_000);
  }
}
