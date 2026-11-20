package mfs.mw.app.gateway.dto;

import java.util.List;

/** {@code GET /app/v1/saved-billers}. */
public record SavedBillerList(List<SavedBiller> billers) {}
