package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.dto.PayShieldTestKey;
import mfs.mw.crypto.dto.PvvRequest;
import mfs.mw.crypto.dto.PvvView;
import mfs.mw.crypto.service.PayShieldTestService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only payShield test API (ticket 76, contract {@code api/mw-crypto/crypto-test.v1.openapi.yaml}):
 * only with {@code mw.crypto.test-api.enabled=true}, only on the plain test connector ({@code
 * filter/TestApiPortFilter}), no token: the stack's test-only APISIX route (key-auth) is the gate,
 * and every operation runs as the test-only client under the normal caller policy.
 */
@RestController
@Validated
@RequestMapping("/test/v1/payshield")
@ConditionalOnProperty(name = "mw.crypto.test-api.enabled", havingValue = "true")
public class PayShieldTestController {

  private final PayShieldTestService service;

  public PayShieldTestController(PayShieldTestService service) {
    this.service = service;
  }

  @GetMapping("/keys/{purpose}")
  public PayShieldTestKey pinKey(@PathVariable @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String purpose) {
    return service.pinKey(purpose);
  }

  @PostMapping("/pvv/generate")
  public PvvView generate(@Valid @RequestBody PvvRequest request) {
    return service.generatePvv(request);
  }

  @PostMapping("/pvv/verify")
  public PvvView verify(@Valid @RequestBody PvvRequest request) {
    return service.verifyPvv(request);
  }
}
