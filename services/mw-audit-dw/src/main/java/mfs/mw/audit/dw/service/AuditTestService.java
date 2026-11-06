package mfs.mw.audit.dw.service;

import mfs.mw.audit.dw.exception.ErrorCodes;
import mfs.mw.audit.dw.exception.MwException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Test-only chain check (ticket 58, {@code controller/AuditTestController}): one month's hash chain
 * through the read-only {@link AuditChainVerifier}; 503 {@code MW-SYS-002} when chain verification
 * is switched off (no verifier bean). Only with {@code mw.audit-dw.test-api.enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "mw.audit-dw.test-api.enabled", havingValue = "true")
public class AuditTestService {

  private final ObjectProvider<AuditChainVerifier> verifier;

  public AuditTestService(ObjectProvider<AuditChainVerifier> verifier) {
    this.verifier = verifier;
  }

  /** @param month {@code yyyy-MM} */
  public ChainVerification verifyChain(String month) {
    AuditChainVerifier v = verifier.getIfAvailable();
    if (v == null) {
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Chain verification is switched off")
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return v.verify(month);
  }
}
