package mfs.mw.adapter.billpay.controller;

import mfs.mw.adapter.billpay.dto.KameletUsageView;
import mfs.mw.adapter.billpay.service.KameletUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, read-only partner engine report (contract {@code billpay.v1}, ticket 83), over the
 * {@code app} zone network only; not routed by APISIX.
 */
@RestController
public class PartnerUsageController {

  private final KameletUsageService service;

  public PartnerUsageController(KameletUsageService service) {
    this.service = service;
  }

  @GetMapping("/internal/v1/partners/kamelet-usage")
  public KameletUsageView kameletUsage() {
    return service.usage();
  }
}
