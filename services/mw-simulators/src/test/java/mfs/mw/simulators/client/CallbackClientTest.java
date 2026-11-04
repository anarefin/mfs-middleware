package mfs.mw.simulators.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallbackClientTest {

  private HttpServer server;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final CallbackClient client = new CallbackClient();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/cb",
        ex -> {
          bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          ex.sendResponseHeaders(204, -1);
          ex.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Test
  void postsJsonAndReturnsTheStatus() {
    assertThat(client.post(url("/cb"), "{\"a\":1}")).isEqualTo(204);
    assertThat(bodies).containsExactly("{\"a\":1}");
  }

  @Test
  void unreachableTargetReturnsMinusOneInsteadOfThrowing() {
    assertThat(client.post("http://127.0.0.1:1/cb", "{}")).isEqualTo(-1);
  }

  @Test
  void invalidUrlReturnsMinusOne() {
    assertThat(client.post("not a url", "{}")).isEqualTo(-1);
  }
}
