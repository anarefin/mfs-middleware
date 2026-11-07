// What a step shows of an answer: tokens and ciphertexts are cut, the rest stays (test data only).

const CUT = new Set(["access_token", "refresh_token", "id_token", "ciphertext", "publicKeyPem", "publicKey", "privatePem"]);

export function redact(v: unknown, depth = 0): unknown {
  if (depth > 12 || v === null || typeof v !== "object") return v;
  if (Array.isArray(v)) return v.map((x) => redact(x, depth + 1));
  const out: Record<string, unknown> = {};
  for (const [k, x] of Object.entries(v as Record<string, unknown>)) {
    out[k] = CUT.has(k) && typeof x === "string" ? `${x.slice(0, 12)}… (${x.length} chars)` : redact(x, depth + 1);
  }
  return out;
}
