import { createHash, createHmac, generateKeyPairSync, privateDecrypt, constants, verify, createPublicKey } from "node:crypto";
import { describe, expect, it } from "vitest";
import {
  canonical, checkoutHeaders, encryptPin, httpDate, newDeviceKey, queryOf, sha256Hex, signCanonical, verifyCheckoutResult, verifyIpn,
} from "./crypto";

describe("device signature", () => {
  it("builds the six-line canonical string with the body hash", () => {
    const c = canonical("POST", "/app/v1/transactions", '{"a":1}', "1700000000000", "n-1", "dev-1");
    expect(c.split("\n")).toEqual(["POST", "/app/v1/transactions", sha256Hex('{"a":1}'), "1700000000000", "n-1", "dev-1"]);
  });

  it("hashes an empty body as SHA-256 of nothing", () => {
    expect(sha256Hex("")).toBe("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  });

  it("signs with the device key so its SPKI public key verifies", () => {
    const k = newDeviceKey();
    const text = canonical("POST", "/app/v1/devices", "{}", "1", "n", "d");
    const sig = signCanonical(k.privatePem, text);
    const pub = createPublicKey({ key: Buffer.from(k.publicKey, "base64"), format: "der", type: "spki" });
    expect(verify("sha256", Buffer.from(text), pub, Buffer.from(sig, "base64"))).toBe(true);
    expect(verify("sha256", Buffer.from(text + "x"), pub, Buffer.from(sig, "base64"))).toBe(false);
  });

  it("exports a P-256 SPKI public key (91 bytes DER)", () => {
    expect(Buffer.from(newDeviceKey().publicKey, "base64").length).toBe(91);
  });
});

describe("PIN encryption", () => {
  it("is RSA-OAEP SHA-256 the core key can decrypt", () => {
    const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const pem = publicKey.export({ type: "spki", format: "pem" }).toString();
    const e = encryptPin("1234", pem, "k1");
    expect(e.keyId).toBe("k1");
    const clear = privateDecrypt({ key: privateKey, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, Buffer.from(e.ciphertext, "base64"));
    expect(clear.toString()).toBe("1234");
  });
});

describe("checkout merchant hmac-auth", () => {
  const date = new Date(Date.UTC(2026, 10, 7, 10, 0, 0));

  it("formats the Date header as IMF-fixdate", () => {
    expect(httpDate(date)).toBe("Sat, 07 Nov 2026 10:00:00 GMT");
  });

  it("signs request target, date and digest for a body", () => {
    const body = '{"orderId":"o1"}';
    const h = checkoutHeaders("POST", "/checkout/v1/sessions", body, "fake-secret", "merchant-demo", date);
    const digest = "SHA-256=" + createHash("sha256").update(body).digest("base64");
    const signing = `merchant-demo\nPOST /checkout/v1/sessions\ndate: ${h.Date}\ndigest: ${digest}\n`;
    const sig = createHmac("sha256", "fake-secret").update(signing).digest("base64");
    expect(h.Digest).toBe(digest);
    expect(h.Authorization).toBe(`Signature keyId="merchant-demo",algorithm="hmac-sha256",headers="@request-target date digest",signature="${sig}"`);
  });

  it("leaves the digest out of a GET", () => {
    const h = checkoutHeaders("GET", "/checkout/v1/sessions/s1", null, "fake-secret", "merchant-demo", date);
    expect(h.Digest).toBeUndefined();
    expect(h.Authorization).toContain('headers="@request-target date"');
  });

  it("verifies the signed result and refuses a tampered one", () => {
    const q: Record<string, string> = { sessionId: "s", orderId: "o", status: "PAID", txnId: "t", amount: "100", currency: "BDT", ts: "1" };
    const c = ["bits-checkout-result.v1", "s", "o", "PAID", "t", "100", "BDT", "", "1"].join("\n");
    q.signature = createHmac("sha256", "fake-secret").update(c).digest("hex");
    expect(verifyCheckoutResult(q, "fake-secret")).toBe(true);
    expect(verifyCheckoutResult({ ...q, status: "FAILED" }, "fake-secret")).toBe(false);
  });

  it("verifies an IPN signature", () => {
    const body = '{"status":"PAID"}';
    const sig = createHmac("sha256", "fake-secret").update(`bits-checkout-ipn.v1\n42\n${body}`).digest("hex");
    expect(verifyIpn({ "x-bits-signature": sig, "x-bits-timestamp": "42" }, body, "fake-secret")).toBe(true);
    expect(verifyIpn({ "x-bits-signature": sig, "x-bits-timestamp": "43" }, body, "fake-secret")).toBe(false);
  });

  it("reads a redirect's query", () => {
    expect(queryOf("https://shop.example.test/ok?cart=7&status=PAID&x=a+b")).toEqual({ cart: "7", status: "PAID", x: "a b" });
    expect(queryOf("https://shop.example.test/ok")).toEqual({});
  });
});
