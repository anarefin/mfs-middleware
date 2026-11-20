package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.client.BillPayClient;
import mfs.mw.app.gateway.exception.BillPayRefusedException;
import mfs.mw.app.gateway.exception.BillPayUnavailableException;
import mfs.mw.app.gateway.config.BillPayProperties;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.Bill;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.Biller;
import mfs.mw.app.gateway.dto.ConfigChangedEvent;
import mfs.mw.app.gateway.repository.BillFetchStore;
import java.util.Map;
import mfs.mw.app.gateway.dto.ErrorEnvelope;
import mfs.mw.app.gateway.dto.PartnerRef;
import mfs.mw.app.gateway.exception.MwException;
import org.junit.jupiter.api.Test;

/** Biller catalogue and bill fetch as the app sees them (ticket 21). */
class BillerServiceTest {

  static final Biller DESCO = new Biller("DESCO", "Dhaka Electric", "ELECTRICITY", "billagg");

  final BillPayClient client = mock(BillPayClient.class);
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T06:00:00Z"));
  final BillerCatalog projection = new BillerCatalog();
  final BillFetchStore fetches = mock(BillFetchStore.class);
  final BillerService service =
      new BillerService(
          client,
          new BillPayProperties(URI.create("http://x"), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(60)),
          clock,
          projection,
          fetches);

  static ConfigChangedEvent biller(String code, long version, boolean enabled) {
    Map<String, Object> value = new java.util.LinkedHashMap<>();
    value.put("code", code);
    value.put("nameEn", "Dhaka Electric Supply");
    value.put("nameBn", "ডেসকো");
    value.put("category", "UTILITY");
    value.put("primaryAggregator", "billagg");
    value.put("fields", List.of(Map.of("name", "accountNo", "labelEn", "Account number", "labelBn", "হিসাব নম্বর",
        "regex", "^[0-9]{8,12}$", "required", true)));
    value.put("amountRule", "FIXED");
    value.put("billMonthRequired", false);
    value.put("enabled", enabled);
    if (!enabled) {
      value.put("maintenanceEn", "DESCO is under maintenance");
      value.put("maintenanceBn", "ডেসকো রক্ষণাবেক্ষণে আছে");
    }
    return new ConfigChangedEvent(1, "biller/" + code, ConfigChangedEvent.ConfigType.BILLER,
        ConfigChangedEvent.Change.UPSERT, version, value, Instant.now(), "m", "c", null);
  }

  @Test
  void theProjectionReplacesTheAdaptersListWithItsCatalogueFields() {
    projection.apply(biller("DESCO", 1, true));
    List<Biller> seen = service.billers();
    assertThat(seen).singleElement().satisfies(b -> {
      assertThat(b.partnerId()).isNull();
      assertThat(b.nameBn()).isEqualTo("ডেসকো");
      assertThat(b.amountRule()).isEqualTo("FIXED");
      assertThat(b.fields()).singleElement().extracting(Biller.Field::labelBn).isEqualTo("হিসাব নম্বর");
    });
    assertThat(service.partnerFor("DESCO")).isEqualTo(new PartnerRef("billagg", "BILL", "DESCO"));
    verify(client, times(0)).billers();
  }

  @Test
  void aDisabledBillerIsRefusedWithItsMaintenanceText() {
    projection.apply(biller("DESCO", 1, true));
    projection.apply(biller("DESCO", 2, false));
    projection.apply(biller("DESCO", 1, true)); // older: ignored
    assertThatThrownBy(() -> service.partnerFor("DESCO"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-BILLER_DISABLED");
          assertThat(e.status().value()).isEqualTo(503);
          assertThat(e.getMessage()).isEqualTo("DESCO is under maintenance");
          assertThat(e.messageBn()).isEqualTo("ডেসকো রক্ষণাবেক্ষণে আছে");
        });
  }

  @Test
  void aFetchedBillIsKeptForTheWalletsPayment() {
    AppUser user = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER");
    when(client.fetch("DESCO", "12345678", "202611"))
        .thenReturn(new Bill("DESCO", "12345678", new Amount(66_700L, "BDT"), null, null));
    service.fetch(user, "DESCO", "12345678", "202611");
    verify(fetches).save("CUSTOMER_APP", "01711000001", "DESCO", "12345678", 66_700L);
  }

  @Test
  void theAppSeesTheCatalogueWithoutAggregators() {
    when(client.billers()).thenReturn(List.of(DESCO));
    assertThat(service.billers()).containsExactly(new Biller("DESCO", "Dhaka Electric", "ELECTRICITY", null));
  }

  @Test
  void theCatalogueIsCachedForItsTtlAndKeptWhenTheAdapterIsDown() {
    when(client.billers()).thenReturn(List.of(DESCO));
    service.billers();
    service.partnerFor("DESCO");
    verify(client, times(1)).billers();

    clock.advance(Duration.ofSeconds(61));
    when(client.billers()).thenThrow(new BillPayUnavailableException("down", null));
    assertThat(service.partnerFor("DESCO")).isEqualTo(new PartnerRef("billagg", "BILL", "DESCO"));
    verify(client, times(2)).billers();
  }

  @Test
  void noCatalogueAtAllIs503Retryable() {
    when(client.billers()).thenThrow(new BillPayUnavailableException("down", null));
    assertThatThrownBy(service::billers)
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-SYS-011");
              assertThat(e.status().value()).isEqualTo(503);
              assertThat(e.retryable()).isTrue();
            });
  }

  @Test
  void anUnknownBillerIsAValidationErrorOnBillerCode() {
    when(client.billers()).thenReturn(List.of(DESCO));
    assertThatThrownBy(() -> service.partnerFor("NOPE"))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-VAL-001");
              assertThat(e.details()).extracting(d -> d.field()).containsExactly("partner.billerCode");
            });
  }

  @Test
  void fetchReturnsTheBill() {
    Bill bill = new Bill("DESCO", "1234567", new Amount(66_700L, "BDT"), "2026-11-14", "2026-11");
    when(client.fetch("DESCO", "1234567", null)).thenReturn(bill);
    assertThat(service.fetch("DESCO", "1234567")).isEqualTo(bill);
  }

  @Test
  void aRefusalKeepsTheAdaptersCodeAndStatus() {
    when(client.fetch("DESCO", "1234404", null))
        .thenThrow(
            new BillPayRefusedException(
                502,
                new ErrorEnvelope("MW-PTR-BILLAGG-BILL_NOT_FOUND", "Bill not found", null, null, "c", false, null)));
    assertThatThrownBy(() -> service.fetch("DESCO", "1234404"))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-PTR-BILLAGG-BILL_NOT_FOUND");
              assertThat(e.status().value()).isEqualTo(502);
              assertThat(e.retryable()).isFalse();
            });
  }

  @Test
  void aRefusalWithAnUnusableCodeOrStatusIsASystemError() {
    when(client.fetch("DESCO", "1", null))
        .thenThrow(new BillPayRefusedException(418, new ErrorEnvelope("bogus", "x", null, null, "c", false, null)));
    assertThatThrownBy(() -> service.fetch("DESCO", "1"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-SYS-011"));
  }

  @Test
  void anUnreachableAdapterOnFetchIs503() {
    when(client.fetch("DESCO", "1", null)).thenThrow(new BillPayUnavailableException("down", null));
    assertThatThrownBy(() -> service.fetch("DESCO", "1"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.status().value()).isEqualTo(503));
  }

  /** A clock the test moves by hand. */
  static final class MutableClock extends java.time.Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
