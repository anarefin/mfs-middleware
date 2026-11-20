package mfs.mw.app.gateway.service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import mfs.mw.app.gateway.client.ComplianceClient;
import mfs.mw.app.gateway.client.ComplianceClient.Subject;
import mfs.mw.app.gateway.config.ComplianceClientProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.OnboardingApplicationRequest;
import mfs.mw.app.gateway.dto.OnboardingDocumentRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * eKYC onboarding through mw-compliance (ticket 124b, FRS UC-01). Who the end user is comes from the app token
 * only and goes to mw-compliance as the subject (security review of ticket 124a):
 *
 * <ul>
 *   <li>Customer app: subject {@code WALLET} = the token's MSISDN, channel {@code APP}, the wallet is the token's
 *       own (another wallet in the body is 403);
 *   <li>DSO app: subject {@code AGENT} = the token's MSISDN, channel {@code DSO}, agent id the same;
 *   <li>Agent app: subject {@code AGENT}, channel {@code AGENT}, as the DSO;
 *   <li>any other app role: 403 {@code MW-AUTH-002}.
 * </ul>
 *
 * An assisted application names the customer's wallet in the body. An image of another type than {@code
 * mw.app-gateway.compliance.content-types} or over {@code max-document-bytes} (decoded) is 400 here and never
 * forwarded; mw-compliance checks the magic bytes again.
 */
@Service
public class OnboardingService {

  static final String APPLICATIONS = "/internal/v1/kyc/applications";

  private final ComplianceClient compliance;
  private final ComplianceClientProperties props;

  public OnboardingService(ComplianceClient compliance, ComplianceClientProperties props) {
    this.compliance = compliance;
    this.props = props;
  }

  public JsonNode create(AppUser user, OnboardingApplicationRequest body) {
    Subject subject = subject(user);
    String channel = channel(user);
    String walletId = body.walletId();
    if ("APP".equals(channel)) {
      if (walletId != null && !walletId.equals(user.msisdn())) {
        throw forbidden("A customer onboards their own wallet only");
      }
      walletId = user.msisdn();
    } else if (walletId == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "walletId is required for an assisted application")
          .withStatus(HttpStatus.BAD_REQUEST);
    }
    Map<String, Object> sent = new LinkedHashMap<>();
    sent.put("walletId", walletId);
    sent.put("channel", channel);
    if (!"APP".equals(channel)) {
      sent.put("agentId", user.msisdn());
    }
    sent.put("nid", body.nid());
    sent.put("dob", body.dob().toString());
    putIfPresent(sent, "nameEn", body.nameEn());
    putIfPresent(sent, "nameBn", body.nameBn());
    Map<String, Object> consent = new LinkedHashMap<>();
    consent.put("purpose", body.consent().purpose());
    putIfPresent(consent, "grantedAt", body.consent().grantedAt() == null ? null : body.consent().grantedAt().toString());
    sent.put("consent", consent);
    return compliance.post(APPLICATIONS, sent, subject);
  }

  public JsonNode addDocument(AppUser user, String id, OnboardingDocumentRequest body) {
    Subject subject = subject(user);
    if (!props.contentTypes().contains(body.contentType())) {
      throw invalid("Only " + String.join(", ", props.contentTypes().stream().sorted().toList()) + " images");
    }
    if (decodedSize(body.content()) > props.maxDocumentBytes()) {
      throw invalid("The image is larger than " + props.maxDocumentBytes() / (1024 * 1024) + " MB");
    }
    return compliance.post(APPLICATIONS + "/" + id + "/documents", body, subject);
  }

  public JsonNode submit(AppUser user, String id) {
    return compliance.post(APPLICATIONS + "/" + id + "/submit", null, subject(user));
  }

  public JsonNode get(AppUser user, String id) {
    return compliance.get(APPLICATIONS + "/" + id, subject(user));
  }

  private static Subject subject(AppUser user) {
    return new Subject("APP".equals(channel(user)) ? "WALLET" : "AGENT", user.msisdn());
  }

  private static String channel(AppUser user) {
    return switch (Objects.requireNonNullElse(user.walletType(), "")) {
      case "CUSTOMER" -> "APP";
      case "DSO" -> "DSO";
      case "AGENT" -> "AGENT";
      default -> throw forbidden("This app cannot onboard customers");
    };
  }

  private static long decodedSize(String base64) {
    try {
      return Base64.getMimeDecoder().decode(base64).length;
    } catch (IllegalArgumentException e) {
      throw invalid("The image is not base64");
    }
  }

  private static void putIfPresent(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static MwException invalid(String message) {
    return new MwException(ErrorCodes.VAL_INVALID, message).withStatus(HttpStatus.BAD_REQUEST);
  }

  private static MwException forbidden(String message) {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, message).withStatus(HttpStatus.FORBIDDEN);
  }
}
