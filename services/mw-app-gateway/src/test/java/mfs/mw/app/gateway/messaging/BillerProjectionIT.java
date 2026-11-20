package mfs.mw.app.gateway.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.function.Predicate;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.dto.Biller;
import mfs.mw.app.gateway.dto.PartnerRef;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.service.BillerCatalog;
import mfs.mw.app.gateway.service.BillerService;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Biller projection (ticket 113b): the contract's own BILLER sample published on {@code
 * mw.config.changed.v1} reaches this gateway's catalogue within 30 s with its fields, the app list
 * shows it, and a later disabled version refuses a payment with its bn/en maintenance text. Real
 * Kafka (Testcontainers).
 */
@SpringBootTest(properties = "mw.biller-catalog.consumer.enabled=true")
@Import(TestInfrastructure.class)
@DirtiesContext
class BillerProjectionIT {

  static final Path SAMPLE =
      Path.of(System.getProperty("mwContractsDir", "../../contracts"))
          .resolve("events/mw-admin-api/samples/mw.config.changed.v1/valid-biller-upsert.json");

  private final BillerCatalog catalog;
  private final BillerService billers;

  @Autowired
  BillerProjectionIT(BillerCatalog catalog, BillerService billers) {
    this.catalog = catalog;
    this.billers = billers;
  }

  @Test
  void aPublishedBillerIsListedAndItsDisableRefusesThePayment() throws Exception {
    String sample = Files.readString(SAMPLE, StandardCharsets.UTF_8);
    publish("biller/DESCO", sample);

    Biller desco = await(b -> true);
    assertThat(desco.fields()).singleElement().satisfies(f -> {
      assertThat(f.labelBn()).isEqualTo("হিসাব নম্বর");
      assertThat(f.regex()).isEqualTo("^[0-9]{8,12}$");
    });
    assertThat(billers.billers()).anySatisfy(b -> {
      assertThat(b.code()).isEqualTo("DESCO");
      assertThat(b.partnerId()).isNull();
      assertThat(b.amountRule()).isEqualTo("FIXED");
    });
    assertThat(billers.partnerFor("DESCO")).isEqualTo(new PartnerRef("billagg", "BILL", "DESCO"));

    publish(
        "biller/DESCO",
        sample
            .replace("\"version\": 1", "\"version\": 2")
            .replace(
                "\"enabled\": true",
                "\"enabled\": false, \"maintenanceEn\": \"DESCO is under maintenance\","
                    + " \"maintenanceBn\": \"ডেসকো রক্ষণাবেক্ষণে আছে\""));
    await(b -> !b.isEnabled());
    try {
      billers.partnerFor("DESCO");
      throw new AssertionError("a disabled biller was accepted");
    } catch (MwException e) {
      assertThat(e.code()).isEqualTo("MW-SYS-BILLER_DISABLED");
      assertThat(e.messageBn()).isEqualTo("ডেসকো রক্ষণাবেক্ষণে আছে");
    }
  }

  private Biller await(Predicate<Biller> ready) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Biller b = catalog.find("DESCO").orElse(null);
      if (b != null && ready.test(b)) {
        return b;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("DESCO not applied within 30 s");
  }

  private static void publish(String key, String value) throws Exception {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      producer.send(new ProducerRecord<>("mw.config.changed.v1", key, value)).get();
    }
  }
}
