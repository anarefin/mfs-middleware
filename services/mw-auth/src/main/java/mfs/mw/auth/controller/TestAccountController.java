package mfs.mw.auth.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import mfs.mw.auth.dto.TestAccount;
import mfs.mw.auth.dto.TestPinRequest;
import mfs.mw.auth.service.TestAccountService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev only: the test console's account actions ({@code auth-test.v1}; APISIX test route
 * {@code test-auth}, {@code /test/auth/v1/accounts/*}). Exists only with
 * {@code mw.auth.seed-api.enabled=true} (the seed API's switch); then {@code config/SecurityConfig}
 * lets it through without a token on the plain public port only. The wallet id is the core's id
 * (mw-core-mock: the MSISDN), as in the seed API.
 */
@RestController
@RequestMapping("/test/v1/accounts/{walletId}")
@ConditionalOnProperty(name = "mw.auth.seed-api.enabled", havingValue = "true")
public class TestAccountController {

  static final String WALLET_ID = "[A-Za-z0-9._:+-]{1,64}";

  private final TestAccountService accounts;

  public TestAccountController(TestAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping
  public TestAccount get(@PathVariable @Pattern(regexp = WALLET_ID) String walletId) {
    return accounts.view(walletId);
  }

  @PostMapping("/unlock")
  public TestAccount unlock(@PathVariable @Pattern(regexp = WALLET_ID) String walletId) {
    return accounts.unlock(walletId);
  }

  @PostMapping("/unbind")
  public TestAccount unbind(@PathVariable @Pattern(regexp = WALLET_ID) String walletId) {
    return accounts.unbind(walletId);
  }

  @PostMapping("/pin")
  public TestAccount pin(
      @PathVariable @Pattern(regexp = WALLET_ID) String walletId, @Valid @RequestBody TestPinRequest body) {
    return accounts.setPin(walletId, body.encryptedPin());
  }
}
