package mfs.mw.simulators.service.files;

import java.nio.charset.StandardCharsets;
import mfs.mw.simulators.client.RemoteFiles;
import mfs.mw.simulators.config.FileMockProperties;
import mfs.mw.simulators.dto.FileDropResult;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import mfs.mw.simulators.util.Hashing;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The test helper {@code POST /mock-control/{party}/files} (ticket 123): puts a file into the inbox of the file
 * mocks that read one ({@code aml}: {@code aml/in}, {@code dw}: {@code dw/in}) as the middleware would upload it,
 * so the API suite and the test console can trigger an AML response or a DW check without an SFTP client. Not
 * journaled (the mock journals it when it reads it).
 */
@Service
@Profile(MockControl.PROFILE)
public class FileInbox {

  private final RemoteFiles files;
  private final FileMockProperties props;

  public FileInbox(RemoteFiles files, FileMockProperties props) {
    this.files = files;
    this.props = props;
  }

  public FileDropResult drop(String party, String name, String content) {
    MockParties.require(party);
    String inbox = switch (party) {
      case "aml" -> props.aml().inbox();
      case "dw" -> props.dw().inbox();
      default -> throw new MwException(ErrorCodes.VAL_INVALID, "Party " + party + " has no inbox; aml and dw have one");
    };
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String path = inbox + "/" + name;
    files.write(path, bytes);
    return new FileDropResult(path, Hashing.sha256Hex(bytes), bytes.length);
  }
}
