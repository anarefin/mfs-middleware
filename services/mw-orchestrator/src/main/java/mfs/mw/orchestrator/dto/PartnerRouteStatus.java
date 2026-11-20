package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code mw.partner.route.status.v1} (ticket 97). Only {@code partnerId} and {@code health} are
 * used: a load report has no health and is ignored. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerRouteStatus(String partnerId, String health) {}
