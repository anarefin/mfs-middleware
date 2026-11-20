package mfs.mw.simulators.client;

import java.util.List;

/**
 * The partner file host the file mocks read and write (ticket 123): {@link SftpClient} on the stack's {@code
 * partner-sftp}. Paths are relative to the login's home ({@code aml/in/AML_TXN_1.txt}).
 */
public interface RemoteFiles {

  /** The plain files in {@code dir}, sorted by name; an absent folder is empty. */
  List<String> list(String dir);

  byte[] read(String path);

  /** Writes the whole file; a reader never sees half of it (written under a temporary name, then renamed). */
  void write(String path, byte[] content);

  boolean exists(String path);
}
