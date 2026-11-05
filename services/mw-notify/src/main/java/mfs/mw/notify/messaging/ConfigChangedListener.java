package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.ConfigChangedEvent;
import mfs.mw.notify.service.TemplatePublisher;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.config.changed.v1} route (ticket 39): reads the record and hands it
 * to {@link TemplatePublisher}, which stores a template version in one short transaction and
 * ignores every other config type. A tombstone (empty body, compaction) is skipped. A record that
 * cannot be read, or a store that fails, throws: the route parks it on a retry topic and finally
 * the DLQ. The body is not logged.
 */
@Component("configChangedListener")
public class ConfigChangedListener {

  private final TemplatePublisher templates;
  private final JsonMapper json;

  public ConfigChangedListener(TemplatePublisher templates, JsonMapper json) {
    this.templates = templates;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    String body = exchange.getMessage().getBody(String.class);
    if (body == null || body.isBlank()) {
      return;
    }
    ConfigChangedEvent e = json.readValue(body, ConfigChangedEvent.class);
    if (e.configKey() == null || e.configType() == null || e.change() == null) {
      throw new IllegalArgumentException("config change without configKey, configType or change");
    }
    templates.onConfigChanged(e);
  }
}
