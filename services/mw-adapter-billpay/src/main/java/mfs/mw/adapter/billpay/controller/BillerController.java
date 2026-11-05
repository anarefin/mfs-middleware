package mfs.mw.adapter.billpay.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import mfs.mw.adapter.billpay.dto.BillView;
import mfs.mw.adapter.billpay.dto.BillerView;
import mfs.mw.adapter.billpay.dto.FetchBillRequest;
import mfs.mw.adapter.billpay.service.BillFetchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API (contract {@code api/mw-adapter-billpay/billpay.v1.openapi.yaml}), called by
 * mw-app-gateway over the {@code app} zone network only; not routed by APISIX. Read-only.
 */
@RestController
@RequestMapping("/billpay/v1/billers")
public class BillerController {

  private final BillFetchService service;

  public BillerController(BillFetchService service) {
    this.service = service;
  }

  @GetMapping
  public List<BillerView> billers() {
    return service.billers();
  }

  @PostMapping("/{code}/bills/fetch")
  public BillView fetch(
      @PathVariable @Pattern(regexp = "[A-Z0-9_]{1,32}") String code,
      @Valid @RequestBody FetchBillRequest body) {
    return service.fetch(code, body.billNumber());
  }
}
