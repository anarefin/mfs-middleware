package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /app/v1/notifications/push-token} (ticket 110; contract {@code PushTokenRequest} of
 * {@code api/mw-app-gateway/app.v1.openapi.yaml}): the FCM / APNs device token of the app on the bound
 * device. The wallet and the device are never in the body: the token's {@code msisdn} and {@code
 * X-Device-Id}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PushTokenRequest(
    @NotBlank @Size(max = 4096) String token, @NotNull @Pattern(regexp = "ANDROID|IOS") String platform) {}
