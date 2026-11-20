package mfs.mw.data.export.controller;

import java.time.LocalDate;
import mfs.mw.data.export.service.DwDailyExportJob;
import mfs.mw.data.export.service.DwDailyExportJob.ExportResult;
import mfs.mw.data.export.service.ExportPropertiesZone;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/test only (ticket 128): exports today in Asia/Dhaka so the API suite can ask the warehouse
 * mock to check the drop. Reached through the test-only APISIX route, key-auth, never in production.
 */
@RestController
@ConditionalOnProperty(name = "mw.data-export.test-api.enabled", havingValue = "true")
public class DwExportTestController {

  private final DwDailyExportJob exports;
  private final ExportPropertiesZone zone;

  public DwExportTestController(DwDailyExportJob exports, ExportPropertiesZone zone) {
    this.exports = exports;
    this.zone = zone;
  }

  @PostMapping("/test/v1/exports/today")
  public ExportResult today() {
    return exports.export(LocalDate.now(zone.zone()), "mw-e2e", false);
  }
}
