package mfs.mw.simulators.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.List;
import mfs.mw.simulators.config.FileMockProperties;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.Response;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The partner SFTP host of the file mocks (ticket 123; stack {@code partner-sftp}), over sshj with password login.
 * One connection per operation (the mocks poll every few seconds, nothing to keep open). The host key is checked
 * against {@code sim.files.sftp.host-key} when set; otherwise any key is accepted: the simulator plays the partner
 * on its own test host, whose keys change with every new container. A failure is 503 {@code
 * MW-SYS-FILE_MOCK_UNAVAILABLE} (retryable); no path or content is logged here.
 */
@Component
@Profile(MockControl.PROFILE)
public class SftpClient implements RemoteFiles {

  private final FileMockProperties.Sftp props;

  public SftpClient(FileMockProperties props) {
    this.props = props.sftp();
  }

  @FunctionalInterface
  private interface SftpWork<T> {
    T run(SFTPClient sftp) throws IOException;
  }

  private <T> T withSftp(SftpWork<T> work) {
    try (SSHClient ssh = new SSHClient()) {
      if (props.hostKey().isBlank()) {
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
      } else {
        ssh.addHostKeyVerifier(props.hostKey());
      }
      int timeoutMs = (int) props.timeout().toMillis();
      ssh.setConnectTimeout(timeoutMs);
      ssh.setTimeout(timeoutMs);
      ssh.connect(props.host(), props.port());
      ssh.authPassword(props.username(), props.password());
      try (SFTPClient sftp = ssh.newSFTPClient()) {
        return work.run(sftp);
      }
    } catch (IOException e) {
      throw new MwException(ErrorCodes.SYS_FILE_MOCK_UNAVAILABLE,
          "File host " + props.host() + ":" + props.port() + " failed: " + e.getClass().getSimpleName(), e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE).withRetryable(true);
    }
  }

  @Override
  public List<String> list(String dir) {
    return withSftp(sftp -> {
      try {
        return sftp.ls(dir).stream().filter(RemoteResourceInfo::isRegularFile).map(RemoteResourceInfo::getName)
            .filter(n -> !n.startsWith(".")).sorted().toList();
      } catch (SFTPException e) {
        if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) {
          return List.of();
        }
        throw e;
      }
    });
  }

  @Override
  public byte[] read(String path) {
    return withSftp(sftp -> {
      try (RemoteFile f = sftp.open(path); InputStream in = f.new RemoteFileInputStream()) {
        return in.readAllBytes();
      }
    });
  }

  @Override
  public void write(String path, byte[] content) {
    int slash = path.lastIndexOf('/');
    String tmp = (slash >= 0 ? path.substring(0, slash + 1) : "") + "." + path.substring(slash + 1) + ".part";
    withSftp(sftp -> {
      try (RemoteFile f = sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC));
          OutputStream out = f.new RemoteFileOutputStream()) {
        out.write(content);
      }
      if (sftp.statExistence(path) != null) {
        sftp.rm(path);
      }
      sftp.rename(tmp, path);
      return null;
    });
  }

  @Override
  public boolean exists(String path) {
    return withSftp(sftp -> sftp.statExistence(path) != null);
  }
}
