package mfs.mw.admin.api.entity;

import java.time.Instant;
import mfs.mw.admin.api.dto.PartnerConfigValue;

/**
 * One partner in schema {@code partner} (spec 16.2, ticket 38): row of {@code partner.partner}
 * with its {@code endpoint}, {@code mapping} and {@code error_map} rows folded into one config.
 * {@code version} is the last published {@code mw.config.changed.v1} version (0: a seed, never
 * published; the adapters hold their own seed at version 0 too).
 */
public record Partner(
    String id,
    String family,
    long version,
    PartnerConfigValue config,
    Instant updatedAt,
    String updatedBy,
    String approvedBy) {

  public static final String RESOURCE_TYPE = "partner";
}
