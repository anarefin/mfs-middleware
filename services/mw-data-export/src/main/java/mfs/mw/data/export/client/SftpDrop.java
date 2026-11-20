package mfs.mw.data.export.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import mfs.mw.data.export.config.ExportProperties;
import mfs.mw.data.export.exception.ErrorCodes;
import mfs.mw.data.export.exception.MwException;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import org.springframework.stereotype.Component;

/**
 * Uploads one file to the Data Warehouse drop folder (ticket 128, ADR D32). sshj, with the
 * Resilience4j retry and circuit breaker named {@code dw-sftp}. The upload is the same bytes
 * again, so a retry is safe. The host key is checked unless {@code mw.export.sftp.insecure-host-key}
 * is set (the dev SFTP image regenerates its key). Nothing is connected at startup.
 */
@Component
public class SftpDrop {

  private final ExportProperties.Sftp props;
  private final RetryRegistry retries;
  private final CircuitBreakerRegistry breakers;

  public SftpDrop(ExportProperties properties, RetryRegistry retries, CircuitBreakerRegistry breakers) {
    this.props = properties.sftp();
    this.retries = retries;
    this.breakers = breakers;
  }

  /** Puts {@code name} under the configured directory. A failure is {@code MW-SYS-002}. */
  public void upload(String name, byte[] body) {
    Supplier<Void> call = io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(
        breakers.circuitBreaker("dw-sftp"),
        io.github.resilience4j.retry.Retry.decorateSupplier(retries.retry("dw-sftp"), () -> {
          put(name, body);
          return null;
        }));
    try {
      call.get();
    } catch (RuntimeException e) {
      if (e instanceof MwException mw) {
        throw mw;
      }
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "SFTP upload failed", e);
    }
  }

  private void put(String name, byte[] body) {
    SSHClient ssh = new SSHClient();
    ssh.setConnectTimeout(5_000);
    ssh.setTimeout(15_000);
    if (props.insecureHostKey()) {
      ssh.addHostKeyVerifier(new PromiscuousVerifier());
    } else if (props.fingerprint() != null && !props.fingerprint().isBlank()) {
      ssh.addHostKeyVerifier(props.fingerprint());
    } else {
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "SFTP host key is not configured");
    }
    try {
      ssh.connect(props.host(), props.port());
      ssh.authPassword(props.username(), props.password() == null ? "" : props.password());
      try (SFTPClient sftp = ssh.newSFTPClient()) {
        String dir = props.directory().replaceAll("/+$", "");
        sftp.put(source(name, body), dir + "/" + name);
      }
    } catch (IOException e) {
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "SFTP upload failed", e);
    } finally {
      try {
        ssh.disconnect();
      } catch (IOException ignored) {
        // the session is already unusable
      }
    }
  }

  private static InMemorySourceFile source(String name, byte[] body) {
    return new InMemorySourceFile() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public long getLength() {
        return body.length;
      }

      @Override
      public InputStream getInputStream() {
        return new ByteArrayInputStream(body);
      }
    };
  }
}
