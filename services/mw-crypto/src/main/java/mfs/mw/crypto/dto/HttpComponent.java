package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * One covered component of an RFC 9421 HTTP message signature (ticket 65): a derived component
 * ({@code @method}, {@code @authority}, {@code @path}, {@code @query-param}, ...) or a field name in
 * lower case, its parameters ({@code sf}, {@code bs}, {@code req}, {@code tr}: {@code true}; {@code
 * key}, {@code name}: a string) and its component value as RFC 9421 section 2 derives it from the
 * message (the caller has the message; printable ASCII, no line break).
 */
public record HttpComponent(
    @NotNull @Pattern(regexp = Formats.HTTP_COMPONENT) String name,
    @Size(max = 4) Map<String, Object> params,
    @NotNull @Size(max = Formats.MAX_COMPONENT_VALUE) @Sensitive(SensitiveType.SECRET) String value) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
