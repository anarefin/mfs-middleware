package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** mw-crypto's answer to {@code /internal/v1/tokens} ({@code crypto-internal.v1} Tokens); unknown fields ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CryptoTokens(String domain, String kid, List<String> tokens) {}
