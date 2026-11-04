package mfs.mw.portal.api.dto;

import java.util.List;

/** {@code GET /portal/org/v1/bulk/batches}: the org's batches, newest first. */
public record BatchList(List<BatchView> batches) {}
