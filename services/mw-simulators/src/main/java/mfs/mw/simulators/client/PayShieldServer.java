package mfs.mw.simulators.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mfs.mw.simulators.config.PayShieldProperties;
import mfs.mw.simulators.dto.PayShieldScenario;
import mfs.mw.simulators.service.PayShieldScenarioStore;
import mfs.mw.simulators.service.PayShieldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * TCP front of the payShield 10K simulator (ticket 64, spec 18.8): each message is a 2-byte
 * big-endian length, then the header ({@code sim.payshield.header-length} characters, echoed in the
 * answer: the host correlates by it), then the command code and fields in payShield ASCII; any
 * number of commands per connection, one virtual thread per connection. The scenario's latency
 * holds each answer; an outage closes the connection unanswered. An LMK store that fails (the
 * SoftHSM token unreachable) also closes it. Message content is never logged (PIN blocks, keys).
 */
@Component
@ConditionalOnProperty(name = "sim.payshield.enabled", havingValue = "true", matchIfMissing = true)
public class PayShieldServer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PayShieldServer.class);

  private final PayShieldService service;
  private final PayShieldScenarioStore scenario;
  private final PayShieldProperties props;
  private final Set<Socket> open = ConcurrentHashMap.newKeySet();
  private ExecutorService threads;
  private ServerSocket listener;
  private volatile boolean running;

  public PayShieldServer(PayShieldService service, PayShieldScenarioStore scenario, PayShieldProperties props) {
    this.service = service;
    this.scenario = scenario;
    this.props = props;
  }

  @Override
  public synchronized void start() {
    try {
      listener = new ServerSocket();
      listener.setReuseAddress(true);
      listener.bind(new InetSocketAddress(props.port()));
    } catch (IOException e) {
      throw new IllegalStateException("payShield simulator cannot listen on port " + props.port(), e);
    }
    threads = Executors.newVirtualThreadPerTaskExecutor();
    running = true;
    threads.submit(this::accept);
    log.atInfo().addKeyValue("event", "payshield.listening").addKeyValue("port", port())
        .log("payShield simulator listening");
  }

  /** The bound port (the configured one, or the free one for port 0). */
  public int port() {
    return listener.getLocalPort();
  }

  private void accept() {
    while (running) {
      try {
        Socket s = listener.accept();
        open.add(s);
        threads.submit(() -> serve(s));
      } catch (IOException e) {
        if (running) {
          log.atWarn().addKeyValue("event", "payshield.accept_failed").log("payShield accept failed");
        }
      }
    }
  }

  private void serve(Socket s) {
    try (s) {
      DataInputStream in = new DataInputStream(s.getInputStream());
      DataOutputStream out = new DataOutputStream(s.getOutputStream());
      while (running) {
        int length;
        try {
          length = in.readUnsignedShort();
        } catch (EOFException e) {
          return;
        }
        byte[] message = new byte[length];
        in.readFully(message);
        PayShieldScenario now = scenario.get();
        if (now.isOutage()) {
          return;
        }
        String text = new String(message, StandardCharsets.ISO_8859_1);
        int h = Math.min(props.headerLength(), text.length());
        String answer = text.substring(0, h) + service.handle(text.substring(h));
        if (now.latency() > 0) {
          Thread.sleep(now.latency());
        }
        byte[] bytes = answer.getBytes(StandardCharsets.ISO_8859_1);
        out.writeShort(bytes.length);
        out.write(bytes);
        out.flush();
      }
    } catch (IOException e) {
      // the host closed the connection
    } catch (IllegalStateException e) {
      log.atWarn().addKeyValue("event", "payshield.lmk_unavailable")
          .log("LMK store unavailable: connection closed unanswered");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      open.remove(s);
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    try {
      if (listener != null) {
        listener.close();
      }
    } catch (IOException e) {
      // closing anyway
    }
    for (Socket s : open) {
      try {
        s.close();
      } catch (IOException e) {
        // closing anyway
      }
    }
    if (threads != null) {
      threads.shutdownNow();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
