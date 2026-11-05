package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/** One side of a transaction (contract {@code Party}); {@code id} is a wallet id or MSISDN. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Party(
    String type,
    @Sensitive(SensitiveType.MSISDN) String id,
    @Sensitive(SensitiveType.NAME) String name,
    String walletType) {}
