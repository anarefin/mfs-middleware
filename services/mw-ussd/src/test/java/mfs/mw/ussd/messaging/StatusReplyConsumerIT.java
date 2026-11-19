package mfs.mw.ussd.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.ussd.UssdApplication;
import mfs.mw.ussd.TestInfrastructure;
import mfs.mw.ussd.TestTopics;
import mfs.mw.ussd.dto.StatusMessage;
import mfs.mw.ussd.service.ReplyWaiters;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The per-instance reply consumer of {@code mw.txn.status.v1} (spec 7.5, ADR D27) on two running
 * instances of this service: each has its own consumer group ({@code mw-ussd.reply.<id>}),
 * so each gets every status record and completes the waiters addressed to it; it starts at the
 * latest offset, so a reply sent before it started is never replayed.
 */
@SpringBootTest(
    properties = {
      "mw.ussd.instance-id=it-reply-a",
      "mw.ussd.consumers.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class StatusReplyConsumerIT {

  static final String TOPIC = "mw.txn.status.v1";
  /** One key for every record: one partition, so the records' order is the order they are read. */
  static final String KEY = "it-reply-key";
  static final String PAYER = "01711000001";

  private static KafkaProducer<String, String> producer;

  private final ReplyWaiters waitersA;
  private final Environment env;

  @Autowired
  StatusReplyConsumerIT(ReplyWaiters waitersA, Environment env) {
    this.waitersA = waitersA;
    this.env = env;
  }

  @BeforeAll
  static void topicAndProducer() {
    TestTopics.ensure(TOPIC);
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestTopics.bootstrap());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producer = new KafkaProducer<>(p);
  }

  @AfterAll
  static void close() {
    producer.close();
  }

  @Test
  void eachInstanceGetsItsRepliesAndNoneSentBeforeItStarted() throws Exception {
    TestTopics.awaitGroupStable("mw-ussd.reply.it-reply-a", Duration.ofSeconds(60));

    String earlyTxn = UUID.randomUUID().toString();
    send(earlyTxn, "it-reply-b");

    // B waits for the early reply from before its reply consumer starts, so a replay would be seen.
    AtomicReference<CompletableFuture<StatusMessage>> early = new AtomicReference<>();
    try (ConfigurableApplicationContext b = startInstance("it-reply-b", early, earlyTxn)) {
      ReplyWaiters waitersB = b.getBean(ReplyWaiters.class);
      TestTopics.awaitGroupStable("mw-ussd.reply.it-reply-b", Duration.ofSeconds(60));
      CompletableFuture<StatusMessage> earlyReply = early.get();
      assertThat(earlyReply).isNotNull();

      // B reads from where the partition ended when it joined: a probe sent now arrives.
      String probe = UUID.randomUUID().toString();
      CompletableFuture<StatusMessage> probeReply = waitersB.register(probe, "key-" + probe, PAYER);
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (!probeReply.isDone() && System.nanoTime() < deadline) {
        send(probe, "it-reply-b");
        probeReply.completeOnTimeout(null, 1, TimeUnit.SECONDS).join();
      }
      assertThat(probeReply.join()).isNotNull();
      // The early record is ahead of the probe in the same partition: not read, so not replayed.
      assertThat(earlyReply).isNotDone();

      String forA = UUID.randomUUID().toString();
      String forB = UUID.randomUUID().toString();
      CompletableFuture<StatusMessage> replyA = waitersA.register(forA, "key-" + forA, PAYER);
      CompletableFuture<StatusMessage> replyB = waitersB.register(forB, "key-" + forB, PAYER);
      send(forA, "it-reply-a");
      send(forB, "it-reply-b");

      assertThat(replyA.get(20, TimeUnit.SECONDS).txnId()).isEqualTo(forA);
      assertThat(replyB.get(20, TimeUnit.SECONDS).txnId()).isEqualTo(forB);
      assertThat(earlyReply).isNotDone();
    }
  }

  /**
   * A second instance of this service, on the same database, Kafka and Redis; before its Kafka
   * containers start (lifecycle phase 0), it registers a waiter for {@code earlyTxn} into {@code
   * early}.
   */
  ConfigurableApplicationContext startInstance(
      String instanceId, AtomicReference<CompletableFuture<StatusMessage>> early, String earlyTxn) {
    ApplicationContextInitializer<GenericApplicationContext> waitEarly =
        ctx ->
            ctx.registerBean(
                "itEarlyWaiter",
                SmartLifecycle.class,
                () ->
                    new SmartLifecycle() {
                      private volatile boolean running;

                      @Override
                      public void start() {
                        early.set(ctx.getBean(ReplyWaiters.class).register(earlyTxn, "key-" + earlyTxn, PAYER));
                        running = true;
                      }

                      @Override
                      public void stop() {
                        running = false;
                      }

                      @Override
                      public boolean isRunning() {
                        return running;
                      }

                      @Override
                      public int getPhase() {
                        return 0;
                      }
                    });
    return new SpringApplicationBuilder(UssdApplication.class)
        .initializers(waitEarly)
        .run(
            "--server.port=0",
            "--management.server.port=",
            "--spring.datasource.url=" + env.getProperty("spring.datasource.url"),
            "--spring.datasource.username=" + env.getProperty("spring.datasource.username"),
            "--spring.datasource.password=" + env.getProperty("spring.datasource.password"),
            "--spring.kafka.bootstrap-servers=" + TestInfrastructure.kafkaBootstrapServers(),
            "--spring.data.redis.host=" + env.getProperty("spring.data.redis.host"),
            "--spring.data.redis.port=" + env.getProperty("spring.data.redis.port"),
            "--mw.ussd.pin.key-id=" + env.getProperty("mw.ussd.pin.key-id"),
            "--mw.ussd.pin.public-key=" + env.getProperty("mw.ussd.pin.public-key"),
            "--mw.ussd.instance-id=" + instanceId,
            "--mw.ussd.consumers.enabled=true",
            "--mw.kafka.retry.delays=1s,1s,1s");
  }

  /** A terminal status of {@code txnId} addressed to {@code replyTo}. */
  void send(String txnId, String replyTo) throws Exception {
    String body =
        """
        {"schemaVersion":1,"txnId":"%s","idempotencyKey":"key-%s","channel":"USSD",
         "txnType":"P2P","status":"COMPLETED","initiator":{"type":"WALLET","id":"%s"}}
        """
            .formatted(txnId, txnId, PAYER);
    ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, KEY, body);
    record.headers().add(MwHeaders.REPLY_TO, replyTo.getBytes(StandardCharsets.UTF_8));
    record.headers().add(MwHeaders.TXN_ID, txnId.getBytes(StandardCharsets.UTF_8));
    producer.send(record).get(10, TimeUnit.SECONDS);
  }
}
