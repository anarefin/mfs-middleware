import { describe, expect, it } from "vitest";
import { menuProblems, parseActivations, parseMenu, startPreview, stepPreview, tree, type Menu } from "./ussd-menu";

const t = (bn: string, en: string) => ({ bn, en });
const MESSAGES = {
  invalid: t("ভুল ইনপুট।", "Invalid input."), tooManyAttempts: t("অনেকবার ভুল", "Too many"),
  expired: t("শেষ", "Expired"), processing: t("প্রক্রিয়াধীন", "Processing"),
  unavailable: t("বন্ধ", "Unavailable"), more: t("0 আরও", "0 More"),
};

const MENU: Menu = {
  start: "main",
  messages: MESSAGES,
  nodes: {
    main: {
      type: "MENU", text: t("Bits", "Bits"),
      options: [
        { key: "1", label: t("সেন্ড মানি", "Send Money"), next: "p2p.recipient" },
        { key: "9", label: t("English", "বাংলা"), next: "main", switchLanguage: true },
      ],
    },
    "p2p.recipient": {
      type: "INPUT", text: t("প্রাপকের নম্বর দিন", "Enter recipient number"),
      validator: { type: "msisdn", notSelf: true, error: t("সঠিক নম্বর দিন।", "Enter a valid number.") },
      store: "recipient", next: "p2p.amount",
    },
    "p2p.amount": {
      type: "INPUT", text: t("টাকার পরিমাণ দিন", "Enter amount"),
      validator: { type: "amount", min: "10", max: "25000" }, store: "amount", next: "p2p.confirm",
    },
    "p2p.confirm": {
      type: "CONFIRM", text: t("{recipient} নম্বরে {amount} টাকা পাঠাবেন?", "Send Tk {amount} to {recipient}?"),
      options: [{ key: "1", label: t("নিশ্চিত", "Confirm"), next: "p2p.pin" }, { key: "2", label: t("বাতিল", "Cancel"), next: "cancelled" }],
    },
    "p2p.pin": { type: "INPUT", text: t("পিন দিন", "Enter PIN"), validator: { type: "pin" }, next: "p2p.send" },
    "p2p.send": {
      type: "ACTION", flow: "P2P", mapping: { creditParty: "recipient", amount: "amount" },
      onSuccess: "p2p.done", onFailure: "p2p.failed",
    },
    "p2p.done": { type: "END", text: t("সফল। TxnID {txnId}", "Done. TxnID {txnId}") },
    "p2p.failed": { type: "END", text: t("ব্যর্থ: {error}", "Failed: {error}") },
    cancelled: { type: "END", text: t("বাতিল", "Cancelled") },
  },
};

describe("USSD preview walk (mw-ussd UssdEngine rules, no flow is run)", () => {
  it("walks Send Money in Bangla to the success screen", () => {
    let p = startPreview(MENU, { msisdn: "01712345678" });
    expect(p.screen).toBe("Bits\n1 সেন্ড মানি\n9 English");
    p = stepPreview(p, "1");
    expect(p.screen).toBe("প্রাপকের নম্বর দিন");
    p = stepPreview(p, "+880 1812-345321");
    p = stepPreview(p, "500");
    expect(p.screen).toBe("01812345321 নম্বরে 500.00 টাকা পাঠাবেন?\n1 নিশ্চিত\n2 বাতিল");
    p = stepPreview(p, "1");
    p = stepPreview(p, "1234");
    expect(p.ended).toBe(true);
    expect(p.screen).toBe("সফল। TxnID PREVIEW-TXN");
    expect(p.trail.map((s) => s.input)).toEqual([null, "1", "+880 1812-345321", "500", "1", "****"]);
  });

  it("re-asks with the validator error, ends after 3 invalid inputs", () => {
    let p = stepPreview(startPreview(MENU, { msisdn: "01712345678" }), "1");
    p = stepPreview(p, "01712345678"); // own number, notSelf
    expect(p.screen).toBe("সঠিক নম্বর দিন।\nপ্রাপকের নম্বর দিন");
    p = stepPreview(p, "abc");
    p = stepPreview(p, "12");
    expect(p.ended).toBe(true);
    expect(p.screen).toBe("অনেকবার ভুল");
  });

  it("switches language, and an ACTION may fail or stay processing", () => {
    let p = stepPreview(startPreview(MENU, { msisdn: "01712345678" }), "9");
    expect(p.lang).toBe("en");
    expect(p.screen).toBe("Bits\n1 Send Money\n9 বাংলা");
    for (const i of ["1", "01812345321", "25000", "1"]) p = stepPreview(p, i);
    const failed = stepPreview(p, "1234", "FAILED");
    expect(failed.screen).toBe("Failed: Preview: the core refused");
    const processing = stepPreview(p, "1234", "PROCESSING");
    expect(processing.screen).toBe("Processing");
    expect(processing.ended).toBe(true);
    const badAmount = stepPreview(stepPreview(stepPreview(startPreview(MENU, { msisdn: "01712345678" }), "1"), "01812345321"), "9");
    expect(badAmount.screen).toBe("ভুল ইনপুট।\nটাকার পরিমাণ দিন");
  });
});

describe("menu checks before filing (the API checks again, MW-VAL-040)", () => {
  it("a complete menu has no problems", () => {
    expect(menuProblems(MENU)).toEqual([]);
  });

  it("reports dangling references and missing languages", () => {
    const broken: Menu = structuredClone(MENU);
    broken.nodes["p2p.amount"]!.next = "nowhere";
    broken.nodes["main"]!.options![0]!.label = { bn: "সেন্ড মানি", en: "" };
    delete (broken.messages as Record<string, unknown>).more;
    expect(menuProblems(broken)).toEqual([
      "messages.more: needs bn and en",
      "nodes.main.options[0].label: needs bn and en",
      "nodes.p2p.amount.next: no node nowhere",
    ]);
  });

  it("parses a pasted tree, drops menuId / version", () => {
    expect(parseMenu('{"menuId":"main","version":2,"start":"a","messages":{},"nodes":{"a":{"type":"END"}}}'))
      .toEqual({ start: "a", messages: {}, nodes: { a: { type: "END" } } });
    expect(parseMenu("not json")).toBeNull();
    expect(parseMenu('{"start":"a"}')).toBeNull();
  });

  it("lists the tree from start, depth-first, unreachable nodes last", () => {
    const m: Menu = { start: "a", messages: {}, nodes: { a: { type: "MENU", options: [{ key: "1", next: "b" }, { key: "2", next: "a" }] }, b: { type: "END" }, z: { type: "END" } } };
    expect(tree(m)).toEqual([{ id: "a", depth: 0 }, { id: "b", depth: 1 }, { id: "z", depth: 0, unreachable: true }]);
  });

  it("activations: each known MNO once, optional go-live as UTC ISO", () => {
    expect(parseActivations(["gp", "robi"], { gp: "2026-12-01T10:00" })).toEqual([
      { mno: "gp", goLiveAt: "2026-12-01T10:00:00.000Z" }, { mno: "robi" },
    ]);
    expect(parseActivations([], {})).toBeNull();
    expect(parseActivations(["gp", "xx"], {})).toBeNull();
  });
});
