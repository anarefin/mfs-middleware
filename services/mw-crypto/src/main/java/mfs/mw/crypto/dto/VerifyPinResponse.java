package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Answer of {@code POST /internal/v1/pin/verify}. A wrong PIN is {@code match: false}, not an error.
 *
 * @param rehash on a match under a VERIFY_ONLY {@code PIN_HMAC} version: the value under the ACTIVE
 *     version, which the caller stores in place of the old one (spec 18.8)
 */
public record VerifyPinResponse(boolean match, @JsonInclude(JsonInclude.Include.NON_NULL) PinHmac rehash) {}
