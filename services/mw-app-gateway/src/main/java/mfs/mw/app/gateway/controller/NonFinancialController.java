package mfs.mw.app.gateway.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import mfs.mw.app.gateway.dto.Favourite;
import mfs.mw.app.gateway.dto.FavouriteList;
import mfs.mw.app.gateway.dto.InboxMessage;
import mfs.mw.app.gateway.dto.SaveBillerRequest;
import mfs.mw.app.gateway.dto.SaveFavouriteRequest;
import mfs.mw.app.gateway.dto.SavedBiller;
import mfs.mw.app.gateway.dto.SavedBillerList;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.CustomerInboxService;
import mfs.mw.app.gateway.service.PreferenceService;
import mfs.mw.app.gateway.util.Msisdn;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved billers, favourites and the notification inbox (ticket 107a, CH-APP-08). The wallet is
 * always the token's {@code msisdn}. Session level ({@code X-Device-Id}, a live session), no
 * signature: nothing moves. Another wallet's row is 404.
 */
@RestController
@Validated
public class NonFinancialController {

  private final PreferenceService prefs;
  private final CustomerInboxService inbox;
  private final AppUserResolver users;

  public NonFinancialController(PreferenceService prefs, CustomerInboxService inbox, AppUserResolver users) {
    this.prefs = prefs;
    this.inbox = inbox;
    this.users = users;
  }

  @GetMapping("/app/v1/saved-billers")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public SavedBillerList billers(@AuthenticationPrincipal Jwt jwt) {
    return prefs.billers(users.resolve(jwt).msisdn());
  }

  @PostMapping("/app/v1/saved-billers")
  @ResponseStatus(HttpStatus.CREATED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public SavedBiller saveBiller(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveBillerRequest body) {
    return prefs.saveBiller(users.resolve(jwt).msisdn(), body.billerCode(), body.accountNo(), blank(body.nickname()));
  }

  @DeleteMapping("/app/v1/saved-billers/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public void deleteBiller(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    prefs.deleteBiller(users.resolve(jwt).msisdn(), id);
  }

  @GetMapping("/app/v1/favourites")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public FavouriteList favourites(@AuthenticationPrincipal Jwt jwt) {
    return prefs.favourites(users.resolve(jwt).msisdn());
  }

  @PostMapping("/app/v1/favourites")
  @ResponseStatus(HttpStatus.CREATED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public Favourite saveFavourite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveFavouriteRequest body) {
    String msisdn = Msisdn.canonical(body.msisdn());
    if (msisdn == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Not a mobile number");
    }
    return prefs.saveFavourite(users.resolve(jwt).msisdn(), msisdn, blank(body.label()));
  }

  @DeleteMapping("/app/v1/favourites/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public void deleteFavourite(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    prefs.deleteFavourite(users.resolve(jwt).msisdn(), id);
  }

  @GetMapping("/app/v1/notifications")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public InboxMessage.Page notifications(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int offset) {
    return inbox.page(users.resolve(jwt).msisdn(), limit, offset);
  }

  @PostMapping("/app/v1/notifications/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    inbox.markRead(users.resolve(jwt).msisdn(), id);
  }

  private static String blank(String s) {
    if (s == null) {
      return null;
    }
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
