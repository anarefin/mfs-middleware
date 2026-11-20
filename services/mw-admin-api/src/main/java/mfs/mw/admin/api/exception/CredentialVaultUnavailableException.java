package mfs.mw.admin.api.exception;

/**
 * Vault could not be asked, or refused a credential write (ticket 131). The message names the
 * operation and status only, never a path's value or Vault's answer body.
 */
public class CredentialVaultUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CredentialVaultUnavailableException(String message) {
    super(message);
  }

  public CredentialVaultUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
