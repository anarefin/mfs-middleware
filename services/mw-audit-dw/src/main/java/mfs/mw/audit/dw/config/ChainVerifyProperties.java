package mfs.mw.audit.dw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The scheduled audit hash-chain verification under {@code mw.audit-dw.chain-verify} (spec 14.3,
 * ticket 50). It connects to the service's database as the read-only {@code audit_reader} role;
 * the password comes from OpenBao ({@code secret/mw-audit-dw}), never from config files.
 *
 * @param enabled whether the verify job and its reader connection exist
 * @param cron Spring cron of the job ({@code -} switches the schedule off)
 * @param readerUsername the read-only role
 * @param readerPassword its password (OpenBao)
 */
@ConfigurationProperties("mw.audit-dw.chain-verify")
public record ChainVerifyProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("0 30 * * * *") String cron,
    @DefaultValue("audit_reader") String readerUsername,
    String readerPassword) {}
