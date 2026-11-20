package mfs.mw.recon.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;
import mfs.mw.recon.config.ReconProperties;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.Response;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.stereotype.Component;

/**
 * Pulls the trust bank's MT940 and MT942 (ticket 126). sshj, no Camel. The read is idempotent, so
 * the Resilience4j retry named {@code trust-sftp} is safe. Nothing connects at startup.
 */
@Component
public class SftpClient {

  private final ReconProperties.Trust props;
  private final RetryRegistry retries;
  private final CircuitBreakerRegistry breakers;

  public SftpClient(ReconProperties properties, RetryRegistry retries, CircuitBreakerRegistry breakers) {
    this.props = properties.trust();
    this.retries = retries;
    this.breakers = breakers;
  }

  public boolean configured() {
    return props.password() != null && !props.password().isBlank() && props.host() != null && !props.host().isBlank();
  }

  public List<String> list(String prefix) {
    return call(() -> {
      try (SSHClient ssh = connect(); SFTPClient sftp = ssh.newSFTPClient()) {
        try {
          return sftp.ls(props.directory()).stream()
              .filter(RemoteResourceInfo::isRegularFile)
              .map(RemoteResourceInfo::getName)
              .filter(name -> name.startsWith(prefix) && !name.startsWith("."))
              .sorted()
              .toList();
        } catch (SFTPException e) {
          if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
            return List.of();
          }
          throw e;
        }
      }
    });
  }

  public byte[] read(String name) {
    String path = props.directory().replaceAll("/+$", "") + "/" + name;
    return call(() -> {
      try (SSHClient ssh = connect(); SFTPClient sftp = ssh.newSFTPClient();
          RemoteFile remote = sftp.open(path); InputStream in = remote.new RemoteFileInputStream()) {
        return in.readAllBytes();
      }
    });
  }

  private SSHClient connect() throws IOException {
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
    ssh.connect(props.host(), props.port());
    ssh.authPassword(props.username(), props.password());
    return ssh;
  }

  private <T> T call(IoSupplier<T> work) {
    Supplier<T> call = io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(
        breakers.circuitBreaker("trust-sftp"),
        io.github.resilience4j.retry.Retry.decorateSupplier(retries.retry("trust-sftp"), () -> {
          try {
            return work.get();
          } catch (MwException e) {
            throw e;
          } catch (IOException e) {
            throw new MwException(ErrorCodes.SYS_UPSTREAM, "SFTP read failed", e);
          }
        }));
    try {
      return call.get();
    } catch (MwException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "SFTP read failed", e);
    }
  }

  @FunctionalInterface
  private interface IoSupplier<T> {
    T get() throws IOException;
  }
}
