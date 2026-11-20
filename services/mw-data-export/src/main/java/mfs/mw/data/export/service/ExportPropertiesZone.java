package mfs.mw.data.export.service;

import java.time.ZoneId;
import mfs.mw.data.export.config.ExportProperties;
import org.springframework.stereotype.Component;

/** The warehouse's business zone, parsed once from {@code mw.export.zone}. */
@Component
public class ExportPropertiesZone {

  private final ZoneId zone;

  public ExportPropertiesZone(ExportProperties properties) {
    this.zone = ZoneId.of(properties.zone());
  }

  public ZoneId zone() {
    return zone;
  }
}
