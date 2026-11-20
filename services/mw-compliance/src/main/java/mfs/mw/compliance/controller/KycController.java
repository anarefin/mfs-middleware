package mfs.mw.compliance.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import mfs.mw.compliance.config.ComplianceProperties;
import mfs.mw.compliance.dto.ApplicationView;
import mfs.mw.compliance.dto.Caller;
import mfs.mw.compliance.dto.CreateApplicationRequest;
import mfs.mw.compliance.dto.DocumentRequest;
import mfs.mw.compliance.dto.DocumentView;
import mfs.mw.compliance.exception.ErrorCodes;
import mfs.mw.compliance.exception.MwException;
import mfs.mw.compliance.service.KycApplicationService;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * compliance.v1 internal eKYC API (ticket 124): mTLS plus a realm {@code services} JWT ({@code config/SecurityConfig}).
 * The caller is the JWT's client; a client outside {@code mw.compliance.unscoped-clients} must name the end user
 * its app token authenticated in {@code X-Mw-Subject-Type} ({@code WALLET} or {@code AGENT}) and {@code
 * X-Mw-Subject-Id}, else 403 {@code MW-AUTH-002}. APISIX strips {@code X-Mw-*} at the edge.
 */
@RestController
@RequestMapping("/internal/v1/kyc/applications")
public class KycController {

  static final String SUBJECT_TYPE = "X-Mw-Subject-Type";
  static final String SUBJECT_ID = "X-Mw-Subject-Id";

  private final KycApplicationService service;
  private final ComplianceProperties props;

  public KycController(KycApplicationService service, ComplianceProperties props) {
    this.service = service;
    this.props = props;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApplicationView create(
      @Valid @RequestBody CreateApplicationRequest body,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = SUBJECT_TYPE, required = false) String subjectType,
      @RequestHeader(name = SUBJECT_ID, required = false) String subjectId) {
    return service.create(body, caller(jwt, subjectType, subjectId), MDC.get("correlationId"));
  }

  @PostMapping("/{id}/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentView addDocument(
      @PathVariable UUID id,
      @Valid @RequestBody DocumentRequest body,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = SUBJECT_TYPE, required = false) String subjectType,
      @RequestHeader(name = SUBJECT_ID, required = false) String subjectId) {
    return service.addDocument(id, body, caller(jwt, subjectType, subjectId));
  }

  @PostMapping("/{id}/submit")
  public ApplicationView submit(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = SUBJECT_TYPE, required = false) String subjectType,
      @RequestHeader(name = SUBJECT_ID, required = false) String subjectId) {
    return service.submit(id, caller(jwt, subjectType, subjectId));
  }

  @GetMapping("/{id}")
  public ApplicationView get(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(name = SUBJECT_TYPE, required = false) String subjectType,
      @RequestHeader(name = SUBJECT_ID, required = false) String subjectId) {
    return service.get(id, caller(jwt, subjectType, subjectId));
  }

  private Caller caller(Jwt jwt, String subjectType, String subjectId) {
    String client = jwt == null ? null : jwt.getClaimAsString("azp");
    if (client != null && props.unscopedClients().contains(client)) {
      return Caller.unscoped(client);
    }
    if (subjectId == null || subjectId.isBlank()) {
      throw noSubject();
    }
    if ("WALLET".equals(subjectType)) {
      return Caller.wallet(client, subjectId);
    }
    if ("AGENT".equals(subjectType)) {
      return Caller.agent(client, subjectId);
    }
    throw noSubject();
  }

  private static MwException noSubject() {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, "The authenticated customer or agent is required")
        .withStatus(HttpStatus.FORBIDDEN);
  }
}
