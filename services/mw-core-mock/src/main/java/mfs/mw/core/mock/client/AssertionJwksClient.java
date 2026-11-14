package mfs.mw.core.mock.client;

import java.net.http.HttpClient;
import mfs.mw.core.mock.config.AssertionProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * mw-auth's assertion JWK Set ({@code GET /auth/v1/.well-known/assertion-jwks.json}, contract
 * {@code auth.v1}; ticket 71): public keys only, plain HTTP inside the network, a {@code timeout}
 * each way. Called by {@code security/AssertionKeys} at start and on its timer, never on a command,
 * so no breaker: a failure only keeps the last set.
 */
@Component
public class AssertionJwksClient {

  private final RestClient rest;
  private final String uri;

  public AssertionJwksClient(AssertionProperties props) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(props.timeout())
        .followRedirects(HttpClient.Redirect.NEVER).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(props.timeout());
    this.rest = RestClient.builder().requestFactory(factory).build();
    this.uri = props.jwksUri().toString();
  }

  /** The JWK Set document as served. */
  public String fetch() {
    return rest.get().uri(uri).accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
  }
}
