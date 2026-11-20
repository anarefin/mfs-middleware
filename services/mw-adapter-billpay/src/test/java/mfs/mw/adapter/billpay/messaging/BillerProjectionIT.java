package mfs.mw.adapter.billpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import mfs.mw.adapter.billpay.TestInfrastructure;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Biller projection (ticket 113): the contract's own BILLER sample published on {@code
 * mw.config.changed.v1} shows up in {@code GET /billpay/v1/billers} within 30 s, with its catalogue
 * fields; a later DISABLE version makes the bill fetch answer its bn/en maintenance text. Real
 * Kafka (Testcontainers).
 */
@SpringBootTest(properties = "mw.biller-catalog.consumer.enabled=true")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
class BillerProjectionIT {

  static final Path SAMPLE =
      Path.of(System.getProperty("mwContractsDir", "../../contracts"))
          .resolve("events/mw-admin-api/samples/mw.config.changed.v1/valid-biller-upsert.json");

  private final MockMvcTester mvc;
  private final JsonMapper json;

  @Autowired
  BillerProjectionIT(MockMvcTester mvc, JsonMapper json) {
    this.mvc = mvc;
    this.json = json;
  }

  @Test
  void aPublishedBillerAppearsInTheCatalogueAndItsDisableIsAnswered() throws Exception {
    String sample = Files.readString(SAMPLE, StandardCharsets.UTF_8);
    publish("biller/DESCO", sample);

    JsonNode desco = await(b -> "UTILITY".equals(b.path("category").asString()));
    assertThat(desco.path("partnerId").asString()).isEqualTo("billagg");
    assertThat(desco.path("fallbackPartnerId").asString()).isEqualTo("ekpay");
    assertThat(desco.path("fields").get(0).path("regex").asString()).isEqualTo("^[0-9]{8,12}$");
    assertThat(desco.path("billMonthRequired").asBoolean()).isTrue();

    String disabled =
        sample
            .replace("\"version\": 1", "\"version\": 2")
            .replace(
                "\"enabled\": true",
                "\"enabled\": false, \"maintenanceEn\": \"DESCO is under maintenance\","
                    + " \"maintenanceBn\": \"ডেসকো রক্ষণাবেক্ষণে আছে\"");
    publish("biller/DESCO", disabled);
    await(b -> !b.path("enabled").asBoolean(true));

    MvcTestResult r =
        mvc.post()
            .uri("/billpay/v1/billers/DESCO/bills/fetch")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"billNumber\":\"12345678\",\"billMonth\":\"202611\"}")
            .exchange();
    assertThat(r).hasStatus(503);
    JsonNode body = json.readTree(r.getResponse().getContentAsByteArray());
    assertThat(body.path("code").asString()).isEqualTo("MW-SYS-BILLER_DISABLED");
    assertThat(body.path("message").asString()).isEqualTo("DESCO is under maintenance");
    assertThat(body.path("messageBn").asString()).isEqualTo("ডেসকো রক্ষণাবেক্ষণে আছে");
  }

  private JsonNode await(java.util.function.Predicate<JsonNode> ready) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      JsonNode list = json.readTree(mvc.get().uri("/billpay/v1/billers").exchange().getResponse().getContentAsByteArray());
      for (JsonNode b : list) {
        if ("DESCO".equals(b.path("code").asString()) && ready.test(b)) {
          return b;
        }
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
