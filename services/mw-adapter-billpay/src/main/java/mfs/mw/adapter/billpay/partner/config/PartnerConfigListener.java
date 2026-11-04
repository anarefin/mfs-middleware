package mfs.mw.adapter.billpay.partner.config;

import mfs.mw.adapter.billpay.dto.ConfigChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.config.changed.v1} route: parses a record and applies it to the
 * {@link PartnerConfigStore}. Never throws: a record it cannot use is logged and skipped, so one
 * bad record cannot stop config delivery (the topic has no retry or DLQ topics). Tombstones
 * (null value, after a DELETE) carry nothing to apply.
 */
@Component
public class PartnerConfigListener {

  private static final Logger log = LoggerFactory.getLogger(PartnerConfigListener.class);

  private final JsonMapper json;
  private final PartnerConfigStore store;

  public PartnerConfigListener(JsonMapper json, PartnerConfigStore store) {
    this.json = json;
    this.store = store;
  }

  /** Called with the record value. */
  public void onRecord(String body) {
    if (body == null || body.isBlank()) {
      return;
    }
    try {
      store.apply(json.readValue(body, ConfigChangedEvent.class));
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "config.unreadable")
          .addKeyValue("errorClass", e.getClass().getName())
          .log("config-changed record skipped");
    }
  }
}
