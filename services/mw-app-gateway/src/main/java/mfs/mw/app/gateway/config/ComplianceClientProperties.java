package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.compliance.*}: mw-compliance's eKYC API (ticket 124b, contract {@code
 * api/mw-compliance/compliance.v1.openapi.yaml}) over mTLS with this service's client-credentials token
 * (audience {@code mw-compliance}).
 *
 * @param baseUrl e.g. {@code https://mw-compliance:8080}
 * @param connectTimeout TCP connect limit
 * @param timeout answer limit; above mw-compliance's EC NID limit, so a submit is answered by mw-compliance
 * @param maxDocumentBytes the largest image, decoded (FRS UC-01: 2 MB)
 * @param contentTypes the image types an app may send
 */
@Validated
@ConfigurationProperties("mw.app-gateway.compliance")
public record ComplianceClientProperties(
    @NotNull @DefaultValue("https://mw-compliance:8080") URI baseUrl,
    @NotNull @DefaultValue("1s") Duration connectTimeout,
    @NotNull @DefaultValue("20s") Duration timeout,
    @Positive @DefaultValue("2097152") int maxDocumentBytes,
    @NotNull @DefaultValue({"image/jpeg", "image/png"}) Set<String> contentTypes) {}
