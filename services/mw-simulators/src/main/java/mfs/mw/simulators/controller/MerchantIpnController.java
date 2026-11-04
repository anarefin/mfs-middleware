package mfs.mw.simulators.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mfs.mw.simulators.dto.MerchantIpnScenario;
import mfs.mw.simulators.dto.ReceivedIpn;
import mfs.mw.simulators.service.MerchantIpnService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant IPN receiver (ticket 32): {@code POST /merchant/ipn/**} is where a checkout session's
 * {@code ipnUrl} points in the dev stack ({@code http://mw-simulators:8080/merchant/ipn/...}).
 * Control: {@code POST /sim/merchant/ipn/scenario {failNext, failStatus?}}, {@code GET
 * /sim/merchant/ipn/received[?ipnId=]}, {@code DELETE /sim/merchant/ipn/received} (resets all).
 */
@RestController
public class MerchantIpnController {

  private static final java.util.Set<String> SKIPPED_HEADERS = java.util.Set.of("authorization", "cookie");

  private final MerchantIpnService merchant;

  public MerchantIpnController(MerchantIpnService merchant) {
    this.merchant = merchant;
  }

  @PostMapping("/merchant/ipn/**")
  ResponseEntity<Void> ipn(HttpServletRequest request, @RequestBody(required = false) String body) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (String name : Collections.list(request.getHeaderNames())) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (!SKIPPED_HEADERS.contains(lower)) {
        headers.put(lower, request.getHeader(name));
      }
    }
    int status = merchant.receive(request.getRequestURI(), headers, body == null ? "" : body);
    return ResponseEntity.status(status).build();
  }

  @PostMapping("/sim/merchant/ipn/scenario")
  MerchantIpnScenario scenario(@Valid @RequestBody MerchantIpnScenario scenario) {
    merchant.setScenario(scenario);
    return scenario;
  }

  @GetMapping("/sim/merchant/ipn/received")
  List<ReceivedIpn> received(@RequestParam(required = false) String ipnId) {
    return merchant.received(ipnId);
  }

  @DeleteMapping("/sim/merchant/ipn/received")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reset() {
    merchant.reset();
  }
}
