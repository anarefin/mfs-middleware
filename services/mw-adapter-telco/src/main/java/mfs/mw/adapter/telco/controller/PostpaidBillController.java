package mfs.mw.adapter.telco.controller;

import jakarta.validation.Valid;
import mfs.mw.adapter.telco.dto.FetchPostpaidBillRequest;
import mfs.mw.adapter.telco.dto.PostpaidBillView;
import mfs.mw.adapter.telco.service.PostpaidBillService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal bill fetch (contract {@code api/mw-adapter-telco/telco.v1.openapi.yaml}), called by
 * mw-orchestrator over the app zone network only; not routed by APISIX. A read: no money moves.
 */
@RestController
@RequestMapping("/telco/v1/bills")
public class PostpaidBillController {

  private final PostpaidBillService service;

  public PostpaidBillController(PostpaidBillService service) {
    this.service = service;
  }

  @PostMapping("/fetch")
  public PostpaidBillView fetch(@Valid @RequestBody FetchPostpaidBillRequest body) {
    return service.fetch(body.partnerId(), body.msisdn());
  }
}
