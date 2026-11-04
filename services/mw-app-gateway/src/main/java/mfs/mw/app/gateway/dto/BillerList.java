package mfs.mw.app.gateway.dto;

import java.util.List;

/** Body of {@code GET /app/v1/billers}. */
public record BillerList(List<Biller> billers) {}
