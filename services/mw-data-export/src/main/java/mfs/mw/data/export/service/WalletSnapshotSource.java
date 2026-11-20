package mfs.mw.data.export.service;

import java.time.Instant;
import java.util.List;

/** One line of the wallet snapshot file, before the wallet id is tokenised. */
public interface WalletSnapshotSource {

  List<Line> lines(Instant at);

  /** {@code walletId} may still be a clear MSISDN; the file policy tokenises it. */
  record Line(String walletId, String type, String status, long balanceMinor, long heldMinor) {}
}
