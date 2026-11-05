package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.orchestrator.util.Sensitive;
import mfs.mw.orchestrator.util.SensitiveType;

/** PIN ciphertext as the channels send it (RSA-OAEP, SHA-256; contract {@code EncryptedPin}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EncryptedPin(String keyId, @Sensitive(SensitiveType.SECRET) String ciphertext) {}
