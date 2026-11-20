package mfs.mw.simulators.service.files;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import mfs.mw.simulators.client.RemoteFiles;

/** A file host in memory for the unit tests of the file mocks. */
class InMemoryRemoteFiles implements RemoteFiles {

  private final Map<String, byte[]> files = new ConcurrentSkipListMap<>();

  @Override
  public List<String> list(String dir) {
    String prefix = dir.endsWith("/") ? dir : dir + "/";
    return files.keySet().stream()
        .filter(p -> p.startsWith(prefix) && !p.substring(prefix.length()).contains("/"))
        .map(p -> p.substring(prefix.length()))
        .toList();
  }

  @Override
  public byte[] read(String path) {
    byte[] b = files.get(path);
    if (b == null) {
      throw new IllegalStateException("no file " + path);
    }
    return b.clone();
  }

  @Override
  public void write(String path, byte[] content) {
    files.put(path, content.clone());
  }

  @Override
  public boolean exists(String path) {
    return files.containsKey(path);
  }
}
