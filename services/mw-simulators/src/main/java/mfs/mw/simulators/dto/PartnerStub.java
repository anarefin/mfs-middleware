package mfs.mw.simulators.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * One canned answer of the generic partner stub (ticket 83), set through {@code POST
 * /sim/stubs/<partnerId>}: a request to {@code /sim/partners/<partnerId>}{@code path} with {@code
 * method} gets {@code status} and {@code body} (JSON; a string is sent as is) after {@code
 * delayMs}. A second stub with the same method and path replaces the first.
 *
 * @param operation free label (PAY, INQUIRY, STATUS_QUERY, ...), shown back only
 * @param path absolute path after the partner prefix; {@code *} matches one path segment
 */
public record PartnerStub(
    String operation,
    @NotBlank @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE") String method,
    @NotBlank @Pattern(regexp = "^/[A-Za-z0-9._~/*{}-]*$") String path,
    @Min(100) @Max(599) Integer status,
    Object body,
    Map<String, String> headers,
    @Min(0) @Max(60_000) Long delayMs) {

  public PartnerStub {
    status = status == null ? 200 : status;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    delayMs = delayMs == null ? 0L : delayMs;
  }

  /** True when this stub answers {@code method} on {@code requestPath}. */
  public boolean matches(String requestMethod, String requestPath) {
    if (!method.equalsIgnoreCase(requestMethod)) {
      return false;
    }
    String[] want = path.split("/", -1);
    String[] got = requestPath.split("/", -1);
    if (want.length != got.length) {
      return false;
    }
    for (int i = 0; i < want.length; i++) {
      if (!"*".equals(want[i]) && !want[i].equals(got[i])) {
        return false;
      }
    }
    return true;
  }
}
