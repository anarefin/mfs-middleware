package mfs.mw.compliance.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import mfs.mw.compliance.dto.ApplicationView;
import mfs.mw.compliance.dto.CreateApplicationRequest;
import mfs.mw.compliance.dto.DocumentRequest;
import mfs.mw.compliance.dto.DocumentView;
import mfs.mw.compliance.service.KycApplicationService;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** compliance.v1 internal eKYC API (ticket 124): mTLS plus a realm {@code services} JWT ({@code config/SecurityConfig}). */
@RestController
@RequestMapping("/internal/v1/kyc/applications")
public class KycController {

  private final KycApplicationService service;

  public KycController(KycApplicationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApplicationView create(@Valid @RequestBody CreateApplicationRequest body) {
    return service.create(body, MDC.get("correlationId"));
  }

  @PostMapping("/{id}/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentView addDocument(@PathVariable UUID id, @Valid @RequestBody DocumentRequest body) {
    return service.addDocument(id, body);
  }

  @PostMapping("/{id}/submit")
  public ApplicationView submit(@PathVariable UUID id) {
    return service.submit(id);
  }

  @GetMapping("/{id}")
  public ApplicationView get(@PathVariable UUID id) {
    return service.get(id);
  }
}
