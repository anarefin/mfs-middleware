package mfs.mw.admin.api.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ContentVersionView;
import mfs.mw.admin.api.dto.TemplateChangeBody;
import mfs.mw.admin.api.dto.UssdMenuChangeBody;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.ContentChangeService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * USSD menus and notify templates through maker-checker (ticket 39, spec 15.2; contract {@code
 * admin.v1}). Approve and reject are the generic {@code /admin/v1/change-requests/{id}/...}
 * endpoints. Roles are checked by {@code config/SecurityConfig} and again by the service.
 */
@RestController
@Validated
@RequestMapping("/admin/v1")
public class ContentController {

  private final ContentChangeService service;
  private final OperatorResolver operators;

  public ContentController(ContentChangeService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @PostMapping("/ussd-menus/{menuId}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView proposeMenu(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String menuId, @Valid @RequestBody UssdMenuChangeBody body) {
    return service.proposeMenu(operators.resolve(jwt), menuId, body);
  }

  @GetMapping("/ussd-menus/{menuId}/versions")
  public ContentVersionView.Page menuVersions(
      @PathVariable String menuId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return service.menuVersions(menuId, limit, offset);
  }

  @GetMapping("/ussd-menus/{menuId}/versions/{version}")
  public ContentVersionView menuVersion(@PathVariable String menuId, @PathVariable @Min(1) int version) {
    return service.menuVersion(menuId, version);
  }

  @PostMapping("/notify-templates/{code}/{channel}/{locale}/change-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public ChangeRequestView proposeTemplate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String code,
      @PathVariable String channel,
      @PathVariable String locale,
      @Valid @RequestBody TemplateChangeBody body) {
    return service.proposeTemplate(operators.resolve(jwt), code, channel, locale, body);
  }

  @GetMapping("/notify-templates/{code}/{channel}/{locale}/versions")
  public ContentVersionView.Page templateVersions(
      @PathVariable String code,
      @PathVariable String channel,
      @PathVariable String locale,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return service.templateVersions(code, channel, locale, limit, offset);
  }

  @GetMapping("/notify-templates/{code}/{channel}/{locale}/versions/{version}")
  public ContentVersionView templateVersion(
      @PathVariable String code, @PathVariable String channel, @PathVariable String locale,
      @PathVariable @Min(1) int version) {
    return service.templateVersion(code, channel, locale, version);
  }
}
