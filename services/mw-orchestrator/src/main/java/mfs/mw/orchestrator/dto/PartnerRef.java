package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Partner and product of a transaction (contract {@code PartnerRef}). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerRef(String partnerId, String productCode, String billerCode) {}
