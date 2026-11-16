import "server-only";
// The account and wallet tools of the seed data page (lib/account-tools.ts: the fixed action list).
// Wallets: mw-auth's account test API (/test/auth/v1/accounts/{walletId}, key-auth) and core-mock's
// seed PUT. Keycloak users: the admin REST API with the bootstrap admin through the key-auth test
// route /test/kc (realms ops and portals only). Every call is a Trace step (answers redacted).

import type { KcRealm, UserAction, WalletAction } from "@/lib/account-tools";
import { EdgeError, type Trace } from "./edge";
import { kcAdminPassword, kcAdminUser, optional } from "./env";
import { pins } from "./test-apis";

export type AccountStatus = {
  walletId?: string;
  pinSet?: boolean;
  locked?: boolean;
  lockedUntil?: string | null;
  failedPinAttempts?: number;
  devices?: { deviceId?: string; status?: string; boundAt?: string }[];
};

const acct = (id: string) => `/test/auth/v1/accounts/${encodeURIComponent(id)}`;

export function summariseAccount(a: AccountStatus | null): string {
  if (!a) return "no account at mw-auth";
  const active = (a.devices ?? []).filter((d) => !d.status || d.status === "ACTIVE");
  return [
    a.pinSet === undefined ? null : a.pinSet ? "PIN set" : "no PIN",
    a.locked ? `LOCKED${a.lockedUntil ? ` until ${a.lockedUntil}` : ""}` : a.locked === false ? "not locked" : null,
    a.failedPinAttempts === undefined ? null : `${a.failedPinAttempts} failed PIN attempt(s)`,
    `${active.length} bound device(s)`,
  ].filter(Boolean).join(", ");
}

export async function accountStatus(t: Trace, walletId: string, label = "mw-auth account status"): Promise<AccountStatus | null> {
  const r = await t.call(label, "GET", acct(walletId), { apikey: true }, [200, 404]);
  return r.status === 200 && r.body && typeof r.body === "object" ? (r.body as AccountStatus) : null;
}

/** One wallet action; returns the headline. */
export async function walletAction(t: Trace, action: WalletAction, walletId: string, opts: { pin?: string; balanceMinor?: number; type?: string } = {}): Promise<string> {
  switch (action) {
    case "status":
      return summariseAccount(await accountStatus(t, walletId));
    case "unlock": {
      await t.call("unlock the PIN at mw-auth", "POST", `${acct(walletId)}/unlock`, { apikey: true }, [200, 204]);
      return `unlocked: ${summariseAccount(await accountStatus(t, walletId, "status after"))}`;
    }
    case "unbind": {
      await t.call("unbind the device at mw-auth", "POST", `${acct(walletId)}/unbind`, { apikey: true }, [200, 204]);
      return `device unbound: ${summariseAccount(await accountStatus(t, walletId, "status after"))}`;
    }
    case "resetPin": {
      if (!opts.pin) throw new EdgeError("MW_SEED_APP_PIN not set in deploy/.env");
      const encryptedPin = await pins.encrypt(opts.pin);
      t.note("encrypt the seeded PIN", `RSA-OAEP-SHA256 under the mw-crypto transport key ${encryptedPin.keyId}`);
      await t.call("set the PIN at mw-auth", "POST", `${acct(walletId)}/pin`, { apikey: true, json: { encryptedPin } }, [200, 204]);
      return `PIN reset to the seeded PIN: ${summariseAccount(await accountStatus(t, walletId, "status after"))}`;
    }
    case "setBalance": {
      if (opts.balanceMinor === undefined) throw new EdgeError("type a balance in Tk");
      const path = `/test/core-mock/v1/wallets/${encodeURIComponent(walletId)}`;
      const before = await t.call("core-mock wallet before", "GET", path, { apikey: true }, [200, 400]);
      const type = opts.type ?? (before.body as { type?: string } | null)?.type ?? "CUSTOMER";
      // balanceMinor: the AVAILABLE balance the wallet ends with; core-mock posts the difference as an
      // ADJUSTMENT ledger pair against SETTLEMENT (contracts/api/mw-core-mock/core-mock-seed.v1.openapi.yaml).
      const r = await t.call("core-mock seed PUT (set balance)", "PUT", path,
        { apikey: true, json: { type, openingBalanceMinor: opts.balanceMinor, balanceMinor: opts.balanceMinor } }, [200, 201]);
      const after = await t.call("core-mock wallet after", "GET", path, { apikey: true });
      const bal = (after.body as { availableMinor?: number } | null)?.availableMinor;
      if (bal !== opts.balanceMinor) {
        t.note("balance not set", `core-mock reports an available balance of ${bal ?? "?"} poisha`, false);
        return `core-mock did not set the balance (available ${bal ?? "?"} poisha)`;
      }
      return r.status === 201 ? `wallet created with available balance ${bal} poisha` : `available balance is now ${bal} poisha`;
    }
  }
}

// --- Keycloak --------------------------------------------------------------------------------

const KC = "/test/kc";

async function adminToken(t: Trace): Promise<string> {
  const password = kcAdminPassword();
  if (!password) throw new EdgeError("KC_BOOTSTRAP_ADMIN_PASSWORD not set in deploy/.env");
  const r = await t.call("Keycloak admin token (master, admin-cli)", "POST", `${KC}/realms/master/protocol/openid-connect/token`, {
    apikey: true, form: { grant_type: "password", client_id: "admin-cli", username: kcAdminUser(), password },
  });
  const tok = (r.body as { access_token?: string } | null)?.access_token;
  if (r.status !== 200 || !tok) throw new EdgeError(`no Keycloak admin token (HTTP ${r.status}): is the /test/kc route on (scripts/tester.sh)?`);
  return tok;
}

const DEV_PASSWORD_ENV: Record<KcRealm, string> = { ops: "KC_OPS_TEST_PASSWORD", portals: "KC_PORTALS_TEST_PASSWORD" };

/** One Keycloak user action; returns the headline. */
export async function userAction(t: Trace, action: UserAction, realm: KcRealm, username: string): Promise<string> {
  const headers = { Authorization: `Bearer ${await adminToken(t)}` };
  const base = `${KC}/admin/realms/${realm}`;
  const found = await t.call(`find ${username}`, "GET", `${base}/users?username=${encodeURIComponent(username)}&exact=true`, { apikey: true, headers });
  const id = Array.isArray(found.body) ? (found.body[0] as { id?: string } | undefined)?.id : undefined;
  if (!id) throw new EdgeError(`no user ${username} in realm ${realm}`);
  const user = `${base}/users/${encodeURIComponent(id)}`;
  switch (action) {
    case "unlock": {
      await t.call("enable the user", "PUT", user, { apikey: true, headers, json: { enabled: true } }, [204]);
      const bf = await t.call("clear the brute-force lock", "DELETE", `${base}/attack-detection/brute-force/users/${encodeURIComponent(id)}`, { apikey: true, headers }, [204, 404]);
      if (bf.status === 404) t.note("brute-force lock", "the attack-detection path did not answer through /test/kc (route or user); the user is enabled", false);
      return `${username} enabled${bf.status === 204 ? " and its brute-force lock cleared" : ""}`;
    }
    case "resetPassword": {
      const value = optional(DEV_PASSWORD_ENV[realm]);
      if (!value) throw new EdgeError(`${DEV_PASSWORD_ENV[realm]} not set in deploy/.env`);
      const reset = { apikey: true, headers, json: { type: "password", value, temporary: false } };
      const first = await t.call("reset the password", "PUT", `${user}/reset-password`, reset, [204, 400]);
      if (first.status === 400) {
        // The realms' passwordHistory(5) also refuses an admin reset to a recent password (the dev value
        // usually is the current one). Keycloak keeps the history on the password credential: drop it,
        // then set the dev value (verified against Keycloak 2026-11-15).
        if (!JSON.stringify(first.body ?? "").includes("invalidPasswordHistoryMessage")) throw new EdgeError("Keycloak refused the password");
        const creds = await t.call("list the credentials", "GET", `${user}/credentials`, { apikey: true, headers });
        const pw = (Array.isArray(creds.body) ? (creds.body as { id?: string; type?: string }[]) : []).filter((c) => c.type === "password" && c.id);
        for (const c of pw) await t.call("drop the password and its history", "DELETE", `${user}/credentials/${encodeURIComponent(String(c.id))}`, { apikey: true, headers }, [204]);
        await t.call("set the dev password", "PUT", `${user}/reset-password`, reset, [204]);
      }
      return `${username}'s password is the dev value of ${DEV_PASSWORD_ENV[realm]} again`;
    }
  }
}
