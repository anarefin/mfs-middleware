package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import mfs.mw.ussd.TestInfrastructure;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.messaging.ConfigChangedListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The biller catalogue projection (ticket 113b) against the module's PostgreSQL: the contract's
 * BILLER sample through the real listener is stored and listed with its field, an older version
 * never replaces a newer one, a disable carries its bn/en text, a DELETE removes the entry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class BillerCatalogIT {

  static final Path SAMPLE =
      Path.of(System.getProperty("mwContractsDir", "../../contracts"))
          .resolve("events/mw-admin-api/samples/mw.config.changed.v1/valid-biller-upsert.json");

  private final ConfigChangedListener listener;
  private final BillerCatalog catalog;

  @Autowired
  BillerCatalogIT(ConfigChangedListener listener, BillerCatalog catalog) {
    this.listener = listener;
    this.catalog = catalog;
  }

  @Test
  void aBillerIsStoredVersionedDisabledAndDeleted() throws Exception {
    String sample = Files.readString(SAMPLE, StandardCharsets.UTF_8);
    listener.apply(sample);

    assertThat(catalog.list()).singleElement().satisfies(b -> {
      assertThat(b.code()).isEqualTo("DESCO");
      assertThat(b.partnerId()).isEqualTo("billagg");
      assertThat(b.keyField().labelBn()).isEqualTo("হিসাব নম্বর");
      assertThat(b.isEnabled()).isTrue();
    });

    listener.apply(sample
        .replace("\"version\": 1", "\"version\": 3")
        .replace("\"enabled\": true", "\"enabled\": false, \"maintenanceEn\": \"DESCO is under maintenance\","
            + " \"maintenanceBn\": \"ডেসকো রক্ষণাবেক্ষণে আছে\""));
    listener.apply(sample.replace("\"version\": 1", "\"version\": 2"));
    Biller held = catalog.list().getFirst();
    assertThat(held.isEnabled()).isFalse();
    assertThat(held.maintenanceBn()).isEqualTo("ডেসকো রক্ষণাবেক্ষণে আছে");

    listener.apply("""
        {"schemaVersion": 1, "configKey": "biller/DESCO", "configType": "BILLER", "change": "DELETE",
         "version": 4, "changedAt": "2026-11-20T09:00:00Z"}
        """);
    assertThat(catalog.list()).isEmpty();
  }
}
