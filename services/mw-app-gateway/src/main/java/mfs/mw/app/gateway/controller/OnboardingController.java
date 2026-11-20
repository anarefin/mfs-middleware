package mfs.mw.app.gateway.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import mfs.mw.app.gateway.dto.OnboardingApplicationRequest;
import mfs.mw.app.gateway.dto.OnboardingDocumentRequest;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.OnboardingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * eKYC onboarding (ticket 124b, FRS UC-01): {@code POST /app/v1/onboarding/applications}, {@code POST
 * .../{id}/documents}, {@code POST .../{id}/submit}, {@code GET .../{id}}, answered by mw-compliance. Customer,
 * DSO and Agent apps only ({@link OnboardingService}). Session level ({@code X-Device-Id}, a live session).
 */
@RestController
@Validated
public class OnboardingController {

  private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  private final OnboardingService service;
  private final AppUserResolver users;

  public OnboardingController(OnboardingService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @PostMapping("/app/v1/onboarding/applications")
  @ResponseStatus(HttpStatus.CREATED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public JsonNode create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OnboardingApplicationRequest body) {
    return service.create(users.resolve(jwt), body);
  }

  @PostMapping("/app/v1/onboarding/applications/{id}/documents")
  @ResponseStatus(HttpStatus.CREATED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public JsonNode addDocument(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Pattern(regexp = UUID) String id,
      @Valid @RequestBody OnboardingDocumentRequest body) {
    return service.addDocument(users.resolve(jwt), id, body);
  }

  @PostMapping("/app/v1/onboarding/applications/{id}/submit")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public JsonNode submit(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = UUID) String id) {
    return service.submit(users.resolve(jwt), id);
  }

  @GetMapping("/app/v1/onboarding/applications/{id}")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public JsonNode get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Pattern(regexp = UUID) String id) {
    return service.get(users.resolve(jwt), id);
  }
}
