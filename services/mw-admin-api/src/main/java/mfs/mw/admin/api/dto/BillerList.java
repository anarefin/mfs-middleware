package mfs.mw.admin.api.dto;

import java.util.List;
import java.util.Map;

/** {@code GET /admin/v1/billers} (ticket 113): every catalogue entry, its value and version. */
public record BillerList(List<Map<String, Object>> billers) {}
