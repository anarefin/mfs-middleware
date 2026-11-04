package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.ussd.client.BillPayClient;
import mfs.mw.ussd.config.BillPayProperties;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.exception.BillPayUnavailableException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The biller catalogue as the USSD menu numbers it: sorted, cached, kept through an outage. */
class BillPayDirectoryTest {

  static final BillPayProperties PROPS =
      new BillPayProperties(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(60));

  static final class ScriptedClient extends BillPayClient {
    List<Biller> next = List.of();
    int calls;

    ScriptedClient() {
      super(PROPS, JsonMapper.builder().build());
    }

    @Override
    public List<Biller> billers() {
      calls++;
      if (next == null) {
        throw new BillPayUnavailableException("down", null);
      }
      return next;
    }
  }

  static final class MovableClock extends Clock {
    Instant now = Instant.parse("2026-11-04T10:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  final ScriptedClient client = new ScriptedClient();
  final MovableClock clock = new MovableClock();
  final BillPayDirectory directory = new BillPayDirectory(client, PROPS, clock);

  @Test
  void billersAreSortedByCodeAndThoseWithoutAnAggregatorLeftOut() {
    client.next = new ArrayList<>(List.of(
        new Biller("WASA", "Dhaka WASA", "WATER", "billagg"),
        new Biller("ORPHAN", "No partner", "GAS", null),
        new Biller("DESCO", "Dhaka Electric", "ELECTRICITY", "billagg")));
    assertThat(directory.billers()).extracting(Biller::code).containsExactly("DESCO", "WASA");
  }

  @Test
  void theCatalogueIsReusedWithinItsTtlAndKeptThroughAnOutage() {
    client.next = List.of(new Biller("DESCO", "Dhaka Electric", "ELECTRICITY", "billagg"));
    directory.billers();
    directory.billers();
    assertThat(client.calls).isEqualTo(1);

    clock.now = clock.now.plusSeconds(61);
    client.next = null;
    assertThat(directory.billers()).extracting(Biller::code).containsExactly("DESCO");
    assertThat(client.calls).isEqualTo(2);
  }

  @Test
  void withoutAnyCatalogueAnOutageIsThrown() {
    client.next = null;
    assertThatThrownBy(directory::billers).isInstanceOf(BillPayUnavailableException.class);
  }
}
