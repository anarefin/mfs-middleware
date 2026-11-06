import { createHmac } from "node:crypto";

/**
 * RFC 6238 TOTP as realm ops is configured (HmacSHA256, 6 digits, 30 s). Keycloak keys the HMAC with
 * the stored secret's UTF-8 bytes as they are (never base32-decoded): a copy of mw-seeder's
 * util/Totp, the reference.
 */
export function totpStep(epochMillis: number): number {
  return Math.floor(epochMillis / 1000 / 30);
}

export function totp(secret: string, step: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const h = createHmac("sha256", Buffer.from(secret, "utf8")).update(counter).digest();
  const o = h[h.length - 1]! & 0x0f;
  const bin = ((h[o]! & 0x7f) << 24) | (h[o + 1]! << 16) | (h[o + 2]! << 8) | h[o + 3]!;
  return String(bin % 1_000_000).padStart(6, "0");
}
