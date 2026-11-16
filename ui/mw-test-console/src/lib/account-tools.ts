// The account and wallet tools of the seed data page: a FIXED list of actions the server action
// accepts (anything else is refused), never a free-form proxy to mw-auth or the Keycloak admin API.

export const WALLET_ACTIONS = ["status", "unlock", "resetPin", "unbind", "setBalance"] as const;
export type WalletAction = (typeof WALLET_ACTIONS)[number];

// No TOTP reset: every ops and portals user keeps the pre-set one-time code (the admin API cannot
// put a deleted one back).
export const USER_ACTIONS = ["unlock", "resetPassword"] as const;
export type UserAction = (typeof USER_ACTIONS)[number];

/** The Keycloak realms the user tools may touch (never master). */
export const KC_REALMS = ["ops", "portals"] as const;
export type KcRealm = (typeof KC_REALMS)[number];

const has = <T extends string>(list: readonly T[], v: unknown): v is T => typeof v === "string" && (list as readonly string[]).includes(v);

export const isWalletAction = (v: unknown): v is WalletAction => has(WALLET_ACTIONS, v);
export const isUserAction = (v: unknown): v is UserAction => has(USER_ACTIONS, v);
export const isKcRealm = (v: unknown): v is KcRealm => has(KC_REALMS, v);

/** Button label and confirm text per action (the dialog says what will change). */
export const WALLET_ACTION_TEXT: Record<WalletAction, { label: string; confirm: string | null }> = {
  status: { label: "Status", confirm: null },
  unlock: { label: "Unlock", confirm: "Clears the PIN lock and the failed-PIN count of this wallet at mw-auth." },
  resetPin: { label: "Reset PIN", confirm: "Sets this wallet's PIN at mw-auth back to the seeded PIN (MW_SEED_APP_PIN)." },
  unbind: { label: "Unbind device", confirm: "Unbinds the wallet's device at mw-auth: the next login needs a new binding (the console binds again by itself)." },
  setBalance: { label: "Set balance", confirm: "Sets the wallet's available balance at core-mock (test-only seed PUT; the difference is posted as an ADJUSTMENT ledger entry against SETTLEMENT). A missing wallet is created with it." },
};

export const USER_ACTION_TEXT: Record<UserAction, { label: string; confirm: string }> = {
  unlock: { label: "Unlock", confirm: "Enables the user and clears Keycloak's brute-force lock." },
  resetPassword: { label: "Reset password", confirm: "Sets the user's password back to the realm's dev value (deploy/.env), not temporary." },
};
