package mfs.mw.recon.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/** One {@code mw.partner.recon.v1} line (contract owned by mw-adapter-billpay). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerReconEvent(
    int schemaVersion,
    String partnerId,
    String family,
    String fileName,
    String fileId,
    int lineNumber,
    boolean last,
    Map<String, String> fields,
    String reportedBy,
    Instant receivedAt) {}
