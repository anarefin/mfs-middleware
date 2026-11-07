// The client-side cryptography of the channels the test console plays, on Node's crypto (no
// third-party code). Ported from the API suite's tests/api/lib.js, which the middleware accepts:
//   - the app's bound device: EC P-256 key, request signature (contracts app.v1, X-Signature)
//   - the PIN as an app encrypts it: RSA-OAEP, SHA-256 for the hash and MGF1, the core's key
//   - the checkout merchant's APISIX hmac-auth headers, and its checks of the signed result / IPN
// Pure functions only (no I/O): src/lib/server/* calls them.

import { createHash, createHmac, createPublicKey, generateKeyPairSync, publicEncrypt, sign, constants } from "node:crypto";

export const sha256Hex = (s: string): string => createHash("sha256").update(s, "utf8").digest("hex");
const hmacHex = (secret: string, s: string): string => createHmac("sha256", secret).update(s, "utf8").digest("hex");
const hmacB64 = (secret: string, s: string): string => createHmac("sha256", secret).update(s, "utf8").digest("base64");

export type DeviceKey = { privatePem: string; publicKey: string };

/** A new device key: the private key as PKCS#8 PEM, the public key as base64 SPKI DER (what POST /app/v1/devices takes). */
export function newDeviceKey(): DeviceKey {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
  return {
    privatePem: privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
    publicKey: publicKey.export({ type: "spki", format: "der" }).toString("base64"),
  };
}

/** The string the app signs: method, path with query, hex SHA-256 of the exact body, timestamp, nonce, device id. */
export function canonical(method: string, path: string, body: string, ts: string, nonce: string, deviceId: string): string {
  return [method, path, sha256Hex(body), ts, nonce, deviceId].join("\n");
}

/** ECDSA P-256 with SHA-256 over the canonical string, base64 DER (the gateway also takes r||s). */
export function signCanonical(privatePem: string, text: string): string {
  return sign("sha256", Buffer.from(text, "utf8"), privatePem).toString("base64");
}

export type EncryptedPin = { keyId: string; ciphertext: string };

/** RSA-OAEP with SHA-256 (hash and MGF1) under the core's public key, as the app sends the PIN. */
export function encryptPin(pin: string, publicKeyPem: string, keyId: string): EncryptedPin {
  const ct = publicEncrypt(
    { key: createPublicKey(publicKeyPem), padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" },
    Buffer.from(pin, "utf8"),
  );
  return { keyId, ciphertext: ct.toString("base64") };
}

/** RFC 7231 IMF-fixdate, e.g. "Sat, 07 Nov 2026 10:00:00 GMT". */
export const httpDate = (d: Date): string => d.toUTCString();

/**
 * APISIX hmac-auth headers of the checkout merchant (deploy/apisix/README.md): Date, Digest when
 * there is a body, Authorization Signature over "keyId\nMETHOD path\ndate: …\n[digest: …\n]".
 */
export function checkoutHeaders(method: string, path: string, body: string | null, secret: string, keyId: string, date: Date): Record<string, string> {
  const d = httpDate(date);
  const h: Record<string, string> = { Date: d };
  let signing = `${keyId}\n${method} ${path}\ndate: ${d}\n`;
  let names = "@request-target date";
  if (body !== null) {
    const digest = "SHA-256=" + createHash("sha256").update(body, "utf8").digest("base64");
    h.Digest = digest;
    signing += `digest: ${digest}\n`;
    names += " digest";
  }
  h.Authorization = `Signature keyId="${keyId}",algorithm="hmac-sha256",headers="${names}",signature="${hmacB64(secret, signing)}"`;
  return h;
}

/** The merchant's check of the signed result on its success / fail URL (contract checkout.v1). */
export function verifyCheckoutResult(q: Record<string, string>, secret: string): boolean {
  const c = ["bits-checkout-result.v1", q.sessionId, q.orderId, q.status, q.txnId, q.amount, q.currency, q.errorCode, q.ts]
    .map((v) => v ?? "")
    .join("\n");
  return !!q.signature && hmacHex(secret, c) === q.signature;
}

/** The merchant's IPN check: hex HMAC over "bits-checkout-ipn.v1\n<timestamp>\n<body>". */
export function verifyIpn(headers: Record<string, string>, body: string, secret: string): boolean {
  const sig = headers["x-bits-signature"];
  const ts = headers["x-bits-timestamp"];
  return !!sig && !!ts && hmacHex(secret, `bits-checkout-ipn.v1\n${ts}\n${body}`) === sig;
}

/** The query string of a URL as an object (decoded, '+' as space). */
export function queryOf(url: string): Record<string, string> {
  const i = url.indexOf("?");
  const out: Record<string, string> = {};
  if (i < 0) return out;
  for (const [k, v] of new URLSearchParams(url.slice(i + 1))) out[k] = v;
  return out;
}
