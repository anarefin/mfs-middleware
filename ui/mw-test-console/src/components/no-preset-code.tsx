/**
 * For a login the manifest names no pre-set TOTP secret for: every ops and portals user has one, so
 * this shows only with a manifest from before that (re-run the seed profile).
 */
export function NoPresetCode() {
  return <span className="text-xs text-muted-foreground">no pre-set code: re-run the seed profile</span>;
}
