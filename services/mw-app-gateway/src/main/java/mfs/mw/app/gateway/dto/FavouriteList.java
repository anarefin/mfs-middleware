package mfs.mw.app.gateway.dto;

import java.util.List;

/** {@code GET /app/v1/favourites}. */
public record FavouriteList(List<Favourite> favourites) {}
