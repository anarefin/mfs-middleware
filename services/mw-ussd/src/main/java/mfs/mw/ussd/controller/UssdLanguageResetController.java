package mfs.mw.ussd.controller;

import mfs.mw.ussd.repository.CustomerPrefRepository;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/test only: forgets a remembered USSD language so the next dial starts in Bangla. The API
 * suite shares one MSISDN, and an English walk would otherwise leave the next walk in English.
 * Key-auth at the test-only APISIX route. Off unless {@code mw.ussd.test-api.enabled} is true.
 * The number is not logged.
 */
@RestController
@ConditionalOnProperty(name = "mw.ussd.test-api.enabled", havingValue = "true")
public class UssdLanguageResetController {

  private final CustomerPrefRepository prefs;

  public UssdLanguageResetController(CustomerPrefRepository prefs) {
    this.prefs = prefs;
  }

  @PostMapping("/test/v1/language/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@RequestBody Reset body) {
    prefs.forget(body.msisdn());
  }

  public record Reset(@Sensitive(SensitiveType.MSISDN) String msisdn) {}
}
