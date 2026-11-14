#!/usr/bin/env node
// An mw-auth access token for a seeded app wallet (ticket 69), for scripts/edge-test.sh and manual
// checks: logs in exactly as an app does, through the APISIX edge, with no dependency but node (>= 22):
//   1. a new device key (EC P-256) and device id; the PIN transport key (GET /auth/v1/pin-key)
//   2. POST /auth/v1/otp (purpose BIND), the OTP read from the SMSC simulator (its dev host port)
//   3. POST /auth/v1/devices signed by the new key, with the OTP and the PIN (RSA-OAEP SHA-256)
//   4. POST /auth/v1/sessions signed, with the PIN -> {accessToken, refreshToken, deviceId, ...}
// Binding a new device unbinds the wallet's previous one (its sessions end), as on a phone.
//
// Usage: node scripts/lib/mw-auth-token.mjs MSISDN        prints the token response JSON (+ deviceId)
//        node scripts/lib/mw-auth-token.mjs --self-test   offline check of the signing and PIN encryption
// Env: MW_EDGE_URL (default https://localhost), MW_SIM_URL (default http://127.0.0.1:8085, the
//      mw-simulators dev host port), MW_SEED_APP_PIN (the seeded app PIN, deploy/.env). The edge's
//      dev certificate is self-signed: it is accepted for a loopback edge URL only.
import crypto from "node:crypto";
import http from "node:http";
import https from "node:https";

const BIND_OTP = /Your Bits code to bind a new device is ([0-9]{6})/;

/** {status, body} of one request; body parsed as JSON when it is JSON. */
function request(method, url, headers = {}, body) {
  const u = new URL(url);
  const lib = u.protocol === "https:" ? https : http;
  // the dev edge's self-signed certificate is accepted on this host's loopback only (as curl -k in
  // scripts/edge-test.sh); any other host is verified
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname);
  return new Promise((resolve, reject) => {
    const req = lib.request(u, { method, headers, rejectUnauthorized: !loopback, timeout: 20000 }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        let parsed = text;
        try { parsed = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
        resolve({ status: res.statusCode, body: parsed });
      });
    });
    req.on("timeout", () => req.destroy(new Error(`${method} ${url}: timeout`)));
    req.on("error", reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

export function newDevice() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
  return {
    id: "edge-" + crypto.randomUUID(),
    privateKey,
    publicKey: publicKey.export({ type: "spki", format: "der" }).toString("base64"),
  };
}

/** The signature headers of spec 9.1: ECDSA P-256 SHA-256 (DER) over the six canonical lines. */
export function signedHeaders(device, method, path, body) {
  const ts = String(Date.now());
  const nonce = "edge-" + crypto.randomUUID();
  const bodyHash = crypto.createHash("sha256").update(body, "utf8").digest("hex");
  const canonical = [method, path, bodyHash, ts, nonce, device.id].join("\n");
  const signature = crypto.sign("sha256", Buffer.from(canonical, "utf8"), { key: device.privateKey, dsaEncoding: "der" });
  return { "X-Device-Id": device.id, "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": signature.toString("base64") };
}

/** The PIN as an app encrypts it: RSA-OAEP, SHA-256 for the hash and MGF1, empty label. */
export function encryptPin(pin, key) {
  const ct = crypto.publicEncrypt(
    { key: key.publicKeyPem, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" },
    Buffer.from(String(pin), "ascii"));
  return { keyId: key.keyId, ciphertext: ct.toString("base64") };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function smsCodes(sim, msisdn) {
  const r = await request("GET", `${sim}/sim/smsc/messages?msisdn=${msisdn}`);
  if (r.status !== 200 || !Array.isArray(r.body)) throw new Error(`SMSC simulator: HTTP ${r.status}`);
  return r.body.map((m) => BIND_OTP.exec(m.text || "")).filter(Boolean).map((m) => m[1]);
}

async function signedPost(edge, device, path, json) {
  const body = JSON.stringify(json);
  return request("POST", edge + path,
    { "Content-Type": "application/json", ...signedHeaders(device, "POST", path, body) }, body);
}

export async function login(msisdn, { edge, sim, pin }) {
  const key = await request("GET", `${edge}/auth/v1/pin-key`);
  if (key.status !== 200) throw new Error(`GET /auth/v1/pin-key: HTTP ${key.status} ${JSON.stringify(key.body)}`);
  const device = newDevice();
  const before = await smsCodes(sim, msisdn);
  const otp = await request("POST", `${edge}/auth/v1/otp`, { "Content-Type": "application/json", "X-Device-Id": device.id },
    JSON.stringify({ msisdn, purpose: "BIND", locale: "en" }));
  if (otp.status !== 202) throw new Error(`POST /auth/v1/otp: HTTP ${otp.status} ${JSON.stringify(otp.body)}`);
  let code;
  for (let i = 0; i < 60 && !code; i++) {
    code = (await smsCodes(sim, msisdn)).filter((c) => !before.includes(c)).pop();
    if (!code) await sleep(500);
  }
  if (!code) throw new Error("no binding OTP SMS reached the wallet in 30 s");
  const bound = await signedPost(edge, device, "/auth/v1/devices",
    { msisdn, publicKey: device.publicKey, otp: code, encryptedPin: encryptPin(pin, key.body) });
  if (bound.status !== 201) throw new Error(`POST /auth/v1/devices: HTTP ${bound.status} ${JSON.stringify(bound.body)}`);
  const session = await signedPost(edge, device, "/auth/v1/sessions", { msisdn, encryptedPin: encryptPin(pin, key.body) });
  if (session.status !== 200) throw new Error(`POST /auth/v1/sessions: HTTP ${session.status} ${JSON.stringify(session.body)}`);
  return { ...session.body, deviceId: device.id };
}

function selfTest() {
  const device = newDevice();
  const h = signedHeaders(device, "POST", "/auth/v1/sessions", "{}");
  const canonical = ["POST", "/auth/v1/sessions", crypto.createHash("sha256").update("{}").digest("hex"),
    h["X-Timestamp"], h["X-Nonce"], h["X-Device-Id"]].join("\n");
  const pub = crypto.createPublicKey({ key: Buffer.from(device.publicKey, "base64"), format: "der", type: "spki" });
  if (!crypto.verify("sha256", Buffer.from(canonical), { key: pub, dsaEncoding: "der" }, Buffer.from(h["X-Signature"], "base64"))) {
    throw new Error("signature does not verify");
  }
  const rsa = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const pem = rsa.publicKey.export({ type: "spki", format: "pem" });
  const enc = encryptPin("13579", { keyId: "pin-transport.v1", publicKeyPem: pem });
  const clear = crypto.privateDecrypt(
    { key: rsa.privateKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" },
    Buffer.from(enc.ciphertext, "base64")).toString("ascii");
  if (clear !== "13579" || enc.keyId !== "pin-transport.v1") throw new Error("PIN encryption round trip failed");
  console.log("mw-auth-token self-test: ok");
}

const arg = process.argv[2];
if (import.meta.url === `file://${process.argv[1]}`) {
  if (arg === "--self-test") {
    selfTest();
  } else if (arg && /^01[3-9][0-9]{8}$/.test(arg)) {
    const pin = process.env.MW_SEED_APP_PIN;
    if (!pin) { console.error("mw-auth-token: MW_SEED_APP_PIN is not set (deploy/.env)"); process.exit(2); }
    login(arg, {
      edge: process.env.MW_EDGE_URL || "https://localhost",
      sim: process.env.MW_SIM_URL || "http://127.0.0.1:8085",
      pin,
    }).then((t) => console.log(JSON.stringify(t)), (e) => { console.error("mw-auth-token: " + e.message); process.exit(1); });
  } else {
    console.error("usage: node scripts/lib/mw-auth-token.mjs MSISDN | --self-test");
    process.exit(2);
  }
}
