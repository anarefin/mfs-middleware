package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import mfs.mw.admin.api.dto.BillerChangeBody;
import mfs.mw.admin.api.dto.BillerList;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.BillerCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The biller catalogue (ticket 113, FRS ADM-05). */
@RestController
@Validated
@RequestMapping("/admin/v1/billers")
public class BillerController {

  private final BillerCatalogService service;
  private final OperatorResolver operators;

  public BillerController(BillerCatalogService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @GetMapping
  public BillerList list(@AuthenticationPrincipal Jwt jwt) {
    return service.list(operators.resolve(jwt));
  }

  @PostMapping("/{code}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView propose(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String code, @Valid @RequestBody BillerChangeBody body) {
    return service.propose(operators.resolve(jwt), code, body);
  }
}
