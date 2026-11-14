package mfs.mw.crypto.dto;

/**
 * Answer of {@code POST /internal/v1/http-signature/sign}: the dictionary members the caller puts in
 * (or appends to) the {@code Signature-Input} and {@code Signature} fields, e.g. {@code
 * sig1=("@method" "content-digest");created=1763107200;keyid="http-sign-ec.v1";alg="ecdsa-p256-sha256"}
 * and {@code sig1=:<base64>:}.
 */
public record HttpSignatureSignResponse(String label, String kid, String alg, String signatureInput, String signature) {}
