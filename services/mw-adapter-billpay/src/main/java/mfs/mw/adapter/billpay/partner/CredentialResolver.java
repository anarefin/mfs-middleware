package mfs.mw.adapter.billpay.partner;

/** Resolves a {@link CredentialRef} to the secret value at call time. */
@FunctionalInterface
public interface CredentialResolver {

  /**
   * @throws IllegalStateException when no secret is stored under the reference
   */
  String secret(CredentialRef ref);
}
