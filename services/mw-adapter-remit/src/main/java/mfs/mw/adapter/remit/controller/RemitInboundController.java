package mfs.mw.adapter.remit.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import mfs.mw.adapter.remit.dto.BeneficiaryLookupResult;
import mfs.mw.adapter.remit.dto.RemitCreditAck;
import mfs.mw.adapter.remit.service.BeneficiaryLookupService;
import mfs.mw.adapter.remit.mapper.InboundCallMapper;
import mfs.mw.adapter.remit.service.RemitInboundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound remittance API (ticket 119, {@code contracts/api/mw-adapter-remit/remit-inbound.v1.openapi.yaml}):
 * the partner (UCB IMT) pushes a credit and asks its status. The partner names itself in {@code
 * X-Partner-Id} and signs every request with its own secret, a timestamp and a once-only nonce
 * (checked in the service). The raw body is passed on as received, because that is what was signed.
 * A new credit answers 202 once it is on Kafka; a re-send 200 with the same Upay reference.
 */
@RestController
@Validated
public class RemitInboundController {

  /** A partner instance id (adapter framework rule). */
  static final String PARTNER_ID = "^[a-z0-9][a-z0-9-]{0,63}$";
  static final String PARTNER_REF = "^[A-Za-z0-9._-]{1,64}$";

  private final RemitInboundService service;
  private final BeneficiaryLookupService lookups;
  private final InboundCallMapper mapper;

  public RemitInboundController(RemitInboundService service, BeneficiaryLookupService lookups, InboundCallMapper mapper) {
    this.service = service;
    this.lookups = lookups;
    this.mapper = mapper;
  }

  /** The beneficiary's masked name and eligibility before a remittance is sent (ticket 119c). */
  @PostMapping("/remit/v1/beneficiary-lookup")
  BeneficiaryLookupResult lookup(
      @RequestHeader("X-Partner-Id") @Pattern(regexp = PARTNER_ID) String partnerId,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest request) {
    return lookups.lookup(mapper.toInboundCall(partnerId, request, body));
  }

  @PostMapping("/remit/v1/credits")
  ResponseEntity<RemitCreditAck> credit(
      @RequestHeader("X-Partner-Id") @Pattern(regexp = PARTNER_ID) String partnerId,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest request) {
    RemitCreditAck ack = service.credit(mapper.toInboundCall(partnerId, request, body));
    return ResponseEntity.status(ack.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ack);
  }

  @GetMapping("/remit/v1/credits/{partnerRef}")
  RemitCreditAck status(
      @RequestHeader("X-Partner-Id") @Pattern(regexp = PARTNER_ID) String partnerId,
      @PathVariable @Pattern(regexp = PARTNER_REF) String partnerRef,
      HttpServletRequest request) {
    return service.status(mapper.toInboundCall(partnerId, request, null), partnerRef);
  }
}
