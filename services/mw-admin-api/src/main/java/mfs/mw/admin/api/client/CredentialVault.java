package mfs.mw.admin.api.client;

import java.util.Map;
import java.util.Optional;

/**
 * The Vault side of a partner credential upload (ticket 131). An upload is staged in this service's
 * own secret tree while its change request waits ({@link #stage}); the approval copies it into the
 * partner's secret ({@link #writeFields}), write-only: this service's policy may create, update and
 * patch the adapters' per-partner secrets but never read them, so nothing it wrote can be read
 * back. Every failure to reach Vault is a {@code CredentialVaultUnavailableException}.
 */
public interface CredentialVault {

  /** Stores {@code fields} under staging id {@code ref}. */
  void stage(String ref, Map<String, String> fields);

  /** The staged fields of {@code ref}, empty when there are none (dropped or never staged). */
  Optional<Map<String, String>> staged(String ref);

  /** Removes staging id {@code ref} with every version of it; nothing when absent. */
  void dropStaged(String ref);

  /**
   * Adds or replaces {@code fields} in the secret at {@code path} (e.g. {@code
   * mw-adapter-telco/ocs}), keeping its other fields; creates the secret when absent. Never reads it.
   */
  void writeFields(String path, Map<String, String> fields);
}
