package mfs.mw.seeder.client;

import java.net.URI;
import java.util.Map;
import mfs.mw.seeder.exception.RemoteCallException;
import org.springframework.stereotype.Component;

/** GET the readiness URL: 200 is ready; any other status or no answer is not ready yet. */
@Component
public class HttpHealthProbe implements HealthProbe {

  private final HttpJson http;

  public HttpHealthProbe(HttpJson http) {
    this.http = http;
  }

  @Override
  public boolean ready(URI url) {
    try {
      return http.get("GET readiness", url, Map.of()).status() == 200;
    } catch (RemoteCallException e) {
      return false;
    }
  }
}
