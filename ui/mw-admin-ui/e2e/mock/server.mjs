// Test doubles for the Playwright suite (never shipped in the image):
//   - a minimal Keycloak realm `ops` (OIDC discovery, login form with password + one-time code,
//     authorization code with mandatory PKCE S256, token, refresh, JWKS, logout) on MOCK_IDP_PORT
//   - the stub mw-admin-api (contract admin.v1, answers from e2e/mock/admin-api-stub.mjs) on
//     MOCK_API_PORT; it accepts only access tokens the IdP issued and records what it received
//   - GET /__test/state on the IdP port: issued access tokens and the API's view, for assertions
// All credentials are fake test values.

import { createHash, randomBytes, randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { exportJWK, generateKeyPair, SignJWT } from "jose";
import { detailAnswer, errorAnswer, loadFixtures, searchAnswer } from "./admin-api-stub.mjs";

const IDP_PORT = Number(process.env.MOCK_IDP_PORT ?? 4010);
const API_PORT = Number(process.env.MOCK_API_PORT ?? 4020);
const ISSUER = `http://localhost:${IDP_PORT}/realms/ops`;
const CLIENT_ID = "mw-admin-ui";
const PASSWORD = "fake-test-password";
const OTP = "123456";
const USERS = {
  "ops-viewer01": ["viewer"],
  "ops-compliance01": ["viewer", "compliance-officer"],
};

const { publicKey, privateKey } = await generateKeyPair("RS256");
const jwk = { ...(await exportJWK(publicKey)), kid: "mock-1", alg: "RS256", use: "sig" };
const fx = loadFixtures();

const codes = new Map(); // code -> { user, clientId, redirectUri, challenge, nonce }
const refreshTokens = new Map(); // refresh token -> user
const state = { issuedAccessTokens: [], pkceVerified: 0, apiCalls: [] };
const tokenRoles = new Map(); // access token -> roles

function send(res, status, body, headers = {}) {
  const text = typeof body === "string" ? body : JSON.stringify(body);
  res.writeHead(status, { "content-type": typeof body === "string" ? "text/html; charset=utf-8" : "application/json", ...headers });
  res.end(text);
}

async function readForm(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return new URLSearchParams(Buffer.concat(chunks).toString("utf8"));
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

function loginForm(params, error = "") {
  const hidden = [...params].map(([k, v]) => `<input type="hidden" name="${esc(k)}" value="${esc(v)}">`).join("");
  return `<!doctype html><html><head><title>Sign in to ops</title></head><body>
<h1>Sign in to ops</h1>${error ? `<p id="error">${esc(error)}</p>` : ""}
<form method="post" action="/realms/ops/protocol/openid-connect/auth">${hidden}
<label>Username <input id="username" name="username"></label>
<label>Password <input id="password" name="password" type="password"></label>
<label>One-time code <input id="otp" name="otp" inputmode="numeric"></label>
<button id="kc-login" type="submit">Sign In</button></form></body></html>`;
}

async function tokensFor(user, nonce) {
  const now = Math.floor(Date.now() / 1000);
  const roles = USERS[user];
  const access = await new SignJWT({
    typ: "Bearer", azp: CLIENT_ID, preferred_username: user, scope: "openid profile",
    realm_access: { roles }, jti: randomUUID(),
  }).setProtectedHeader({ alg: "RS256", kid: jwk.kid }).setIssuer(ISSUER).setSubject(`sub-${user}`)
    .setAudience("account").setIssuedAt(now).setExpirationTime(now + 300).sign(privateKey);
  const id = await new SignJWT({ preferred_username: user, ...(nonce ? { nonce } : {}), azp: CLIENT_ID })
    .setProtectedHeader({ alg: "RS256", kid: jwk.kid }).setIssuer(ISSUER).setSubject(`sub-${user}`)
    .setAudience(CLIENT_ID).setIssuedAt(now).setExpirationTime(now + 300).sign(privateKey);
  const refresh = randomBytes(24).toString("base64url");
  refreshTokens.set(refresh, user);
  state.issuedAccessTokens.push(access);
  tokenRoles.set(access, roles);
  return { access_token: access, id_token: id, refresh_token: refresh, token_type: "Bearer", expires_in: 300, scope: "openid profile" };
}

const idp = createServer(async (req, res) => {
  const url = new URL(req.url, ISSUER);
  const p = url.pathname;
  try {
    if (p === "/realms/ops/.well-known/openid-configuration") {
      const base = `${ISSUER}/protocol/openid-connect`;
      return send(res, 200, {
        issuer: ISSUER, authorization_endpoint: `${base}/auth`, token_endpoint: `${base}/token`,
        userinfo_endpoint: `${base}/userinfo`, jwks_uri: `${base}/certs`, end_session_endpoint: `${base}/logout`,
        response_types_supported: ["code"], subject_types_supported: ["public"],
        id_token_signing_alg_values_supported: ["RS256"], code_challenge_methods_supported: ["S256"],
        token_endpoint_auth_methods_supported: ["none", "client_secret_basic", "client_secret_post"],
        grant_types_supported: ["authorization_code", "refresh_token"],
      });
    }
    if (p === "/realms/ops/protocol/openid-connect/certs") return send(res, 200, { keys: [jwk] });
    if (p === "/realms/ops/protocol/openid-connect/auth" && req.method === "GET") {
      const q = url.searchParams;
      if (q.get("client_id") !== CLIENT_ID || q.get("response_type") !== "code") return send(res, 400, "<p>bad request</p>");
      if (q.get("code_challenge_method") !== "S256" || !q.get("code_challenge")) return send(res, 400, "<p>PKCE required</p>");
      return send(res, 200, loginForm(q));
    }
    if (p === "/realms/ops/protocol/openid-connect/auth" && req.method === "POST") {
      const f = await readForm(req);
      const user = f.get("username");
      const params = new URLSearchParams([...f].filter(([k]) => !["username", "password", "otp"].includes(k)));
      if (!USERS[user] || f.get("password") !== PASSWORD) return send(res, 200, loginForm(params, "Invalid username or password."));
      if (f.get("otp") !== OTP) return send(res, 200, loginForm(params, "Invalid one-time code."));
      const code = randomBytes(16).toString("base64url");
      codes.set(code, {
        user, redirectUri: f.get("redirect_uri"), challenge: f.get("code_challenge"), nonce: f.get("nonce"),
      });
      const back = new URL(f.get("redirect_uri"));
      back.searchParams.set("code", code);
      if (f.get("state")) back.searchParams.set("state", f.get("state"));
      back.searchParams.set("iss", ISSUER);
      res.writeHead(302, { location: back.toString() });
      return res.end();
    }
    if (p === "/realms/ops/protocol/openid-connect/token" && req.method === "POST") {
      const f = await readForm(req);
      if (f.get("client_id") !== CLIENT_ID) return send(res, 401, { error: "invalid_client" });
      if (f.get("grant_type") === "authorization_code") {
        const c = codes.get(f.get("code"));
        codes.delete(f.get("code"));
        if (!c || c.redirectUri !== f.get("redirect_uri")) return send(res, 400, { error: "invalid_grant" });
        const verifier = f.get("code_verifier") ?? "";
        if (createHash("sha256").update(verifier).digest("base64url") !== c.challenge) {
          return send(res, 400, { error: "invalid_grant", error_description: "PKCE verification failed" });
        }
        state.pkceVerified += 1;
        return send(res, 200, await tokensFor(c.user, c.nonce));
      }
      if (f.get("grant_type") === "refresh_token") {
        const user = refreshTokens.get(f.get("refresh_token"));
        if (!user) return send(res, 400, { error: "invalid_grant" });
        return send(res, 200, await tokensFor(user));
      }
      return send(res, 400, { error: "unsupported_grant_type" });
    }
    if (p === "/realms/ops/protocol/openid-connect/logout") {
      if (req.method === "POST") refreshTokens.delete((await readForm(req)).get("refresh_token"));
      return send(res, 204, "");
    }
    if (p === "/__test/state") return send(res, 200, state);
    return send(res, 404, { error: "not_found" });
  } catch (e) {
    return send(res, 500, { error: String(e) });
  }
});

const api = createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${API_PORT}`);
  const correlationId = req.headers["x-correlation-id"] ?? randomUUID();
  const auth = req.headers.authorization ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  state.apiCalls.push({ path: url.pathname + url.search, bearer: token, correlationId });
  const reply = ({ status, body }) => send(res, status, body, { "x-correlation-id": correlationId });

  if (!tokenRoles.has(token)) return reply(errorAnswer(401, "MW-AUTH-001", "Authentication required", correlationId));
  if (req.method !== "GET") return reply(errorAnswer(403, "MW-AUTH-002", "Forbidden", correlationId));
  if (url.pathname === "/admin/v1/transactions") return reply(searchAnswer(fx, url.searchParams, correlationId));
  const m = url.pathname.match(/^\/admin\/v1\/transactions\/([0-9a-f-]{36})$/);
  if (m) {
    const mayReveal = tokenRoles.get(token).some((r) => r === "compliance-officer" || r === "auditor");
    return reply(detailAnswer(fx, m[1], { reveal: url.searchParams.get("reveal") === "true", mayReveal }, correlationId));
  }
  return reply(errorAnswer(404, "MW-VAL-003", "Not found", correlationId));
});

idp.listen(IDP_PORT, () => console.log(`mock keycloak on ${ISSUER}`));
api.listen(API_PORT, () => console.log(`stub mw-admin-api on http://localhost:${API_PORT}`));
