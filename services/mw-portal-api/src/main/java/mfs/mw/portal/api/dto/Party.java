package mfs.mw.portal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.portal.api.util.Sensitive;
import mfs.mw.portal.api.util.SensitiveType;

/** One side of a transaction (contract {@code common/defs.v1 Party}). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Party(String type, @Sensitive(SensitiveType.PARTY_ID) String id, String walletType) {}
