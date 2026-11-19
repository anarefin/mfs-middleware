package mfs.mw.simulators.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.config.CoreMockControlProperties;
import mfs.mw.simulators.dto.ResetResult;
import mfs.mw.simulators.dto.ResetResult.PartyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-core-mock's {@code POST /mock-control/reset} (contract {@code api/mw-core-mock/core-mock-control.v1},
 * ticket 103), called by the global reset. Never throws: a core that is unreachable or does not answer
 * 200 is one FAILED line with the reason.
 */
@Component
public class CoreMockControlClient {

  private static final Logger log = LoggerFactory.getLogger(CoreMockControlClient.class);
  private static final String CORE = "core";

  private final CoreMockControlProperties props;
  private final JsonMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public CoreMockControlClient(CoreMockControlProperties props, JsonMapper json) {
    this.props = props;
    this.json = json;
  }

  /** Resets the core (ledger back to the seed of {@code seedVersion}, null = the version in force there). */
  public List<PartyResult> reset(String seedVersion) {
    try {
      String body = seedVersion == null ? "" : json.writeValueAsString(Map.of("seedVersion", seedVersion));
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/mock-control/reset"))
          .timeout(props.timeout());
      if (seedVersion == null) {
        b.POST(HttpRequest.BodyPublishers.noBody());
      } else {
        b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
      }
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) {
        return failed("mw-core-mock answered " + res.statusCode());
      }
      ResetResult result = json.readValue(res.body(), ResetResult.class);
      return result.parties() == null || result.parties().isEmpty() ? failed("mw-core-mock reported no party") : result.parties();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed("interrupted");
    } catch (Exception e) {
      return failed("mw-core-mock unreachable: " + e.getClass().getSimpleName());
    }
  }

  private static List<PartyResult> failed(String detail) {
    log.atWarn().addKeyValue("event", "sim.mock.reset.core_failed").log("core reset failed: {}", detail);
    return List.of(PartyResult.failed(CORE, detail));
  }
}
