package mfs.mw.compliance.dto;

import java.util.UUID;

/** compliance.v1 Document: a stored image, without its content. */
public record DocumentView(UUID documentId, UUID applicationId, DocumentType type, int sizeBytes, String sha256) {}
