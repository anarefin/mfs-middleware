package mfs.mw.crypto.entity;

/** Life of a key version (spec 18.8): rotation moves ACTIVE to VERIFY_ONLY, retirement ends it. */
public enum KeyState {
  /** Signs, encrypts, verifies: the one version new operations use. */
  ACTIVE,
  /** The previous version: verification and decryption still accept it. */
  VERIFY_ONLY,
  /** No longer used for anything. */
  RETIRED
}
