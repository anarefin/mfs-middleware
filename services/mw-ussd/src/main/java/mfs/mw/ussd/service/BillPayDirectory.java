package mfs.mw.ussd.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import mfs.mw.ussd.client.BillPayClient;
import mfs.mw.ussd.config.BillPayProperties;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link BillDirectory} over mw-adapter-billpay's internal API (the gate's documented read-only
 * exception: channel to billpay adapter over HTTP). The catalogue is reused for {@code
 * catalogue-ttl} and kept when the adapter cannot be asked; the bill fetch always asks.
 */
@Service
public class BillPayDirectory implements BillDirectory {

  private static final Logger log = LoggerFactory.getLogger(BillPayDirectory.class);

  private record Cached(List<Biller> billers, Instant at) {}

  private final BillPayClient client;
  private final BillPayProperties props;
  private final Clock clock;
  private volatile Cached cache;

  public BillPayDirectory(BillPayClient client, BillPayProperties props, Clock clock) {
    this.client = client;
    this.props = props;
    this.clock = clock;
  }

  @Override
  public List<Biller> billers() {
    Cached c = cache;
    Instant now = clock.instant();
    if (c != null && c.at().plus(props.catalogueTtl()).isAfter(now)) {
      return c.billers();
    }
    try {
      List<Biller> fresh =
          client.billers().stream()
              .filter(b -> b.code() != null && b.partnerId() != null)
              .sorted(Comparator.comparing(Biller::code))
              .toList();
      cache = new Cached(fresh, now);
      return fresh;
    } catch (RuntimeException e) {
      if (c == null) {
        throw e;
      }
      log.atWarn()
          .addKeyValue("event", "billpay.catalogue.stale")
          .addKeyValue("errorClass", e.getClass().getName())
          .log("biller catalogue could not be refreshed, using the last one");
      return c.billers();
    }
  }

  @Override
  public Bill fetch(String billerCode, String billNumber) {
    return client.fetch(billerCode, billNumber);
  }
}
