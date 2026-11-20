package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The health routing has noted for one partner. {@code health} is absent until a report arrives. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerHealthView(String partnerId, String health) {}
