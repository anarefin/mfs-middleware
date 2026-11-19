package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.idempotent-replay.*}: the stored first 200 response per idempotency key
 * (FRS PLT-011, ticket 99; {@code service/IdempotentReplay}).
 *
 * @param enabled keeps and replays responses in Redis; off in tests that are not about it
 * @param ttl how long a response is kept: the orchestrator's idempotency TTL (72 h)
 */
@Validated
@ConfigurationProperties("mw.app-gateway.idempotent-replay")
public record IdempotentReplayProperties(
    @DefaultValue("true") boolean enabled, @NotNull @DefaultValue("72h") Duration ttl) {}
