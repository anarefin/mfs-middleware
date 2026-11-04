package mfs.mw.app.gateway.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import mfs.mw.app.gateway.dto.CollectionList;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.CollectionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Merchant app API v1 (spec WP20, ticket 30; contract {@code app.v1.openapi.yaml}). */
@RestController
@RequestMapping("/app/v1/merchant")
public class MerchantController {

  private final CollectionService service;
  private final AppUserResolver users;

  public MerchantController(CollectionService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  /** The token's own merchant wallet's collections, newest first. */
  @GetMapping("/collections")
  public CollectionList collections(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) @Min(1) @Max(CollectionService.MAX_LIMIT) Integer limit) {
    return service.collections(users.resolve(jwt), limit);
  }
}
