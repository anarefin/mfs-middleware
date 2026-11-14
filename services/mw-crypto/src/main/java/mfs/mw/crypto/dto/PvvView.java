package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * Answer of {@code POST /internal/v1/pvv/generate} ({@code pvv}, {@code pvkId} = the PVK version
 * (kid), {@code pvki}) and {@code /pvv/verify} ({@code match}: a wrong PIN is false, not an error;
 * {@code pvkId}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PvvView(@Sensitive(SensitiveType.SECRET) String pvv, String pvkId, Integer pvki, Boolean match) {

  public static PvvView generated(String pvv, String pvkId, int pvki) {
    return new PvvView(pvv, pvkId, pvki, null);
  }

  public static PvvView verified(boolean match, String pvkId) {
    return new PvvView(null, pvkId, null, match);
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
