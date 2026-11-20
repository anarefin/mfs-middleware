import { describe, expect, it } from "vitest";
import {
  addChild, deleteNode, menuProblems, MNO_SCREENS, moveNode, moveOption, parseActivations, parseMenu, screenPages, startPreview, stepPreview,
  textCounter, tree, type Menu,
} from "./ussd-menu";

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

describe("screen paging (mw-ussd ScreenFormatter: GSM-7 182, UCS-2 70)", () => {
  const SMALL = { gsm: 30, ucs2: 70, overflow: "PAGE" as const };

  it("a screen that fits is shown as it is", () => {
    expect(screenPages("Bits\n1 Send Money", MNO_SCREENS.gp, "0. Next", "00. Back")).toEqual(["Bits\n1 Send Money"]);
  });

  it("splits at line breaks, each page ends with Next and Back, the last with Back", () => {
    expect(screenPages("Pick one\n1 Send Money\n2 Cash Out\n3 Pay Bill", SMALL, "0. Next", "00. Back")).toEqual([
      "Pick one\n0. Next\n00. Back",
      "1 Send Money\n0. Next\n00. Back",
      "2 Cash Out\n3 Pay Bill\n00. Back",
    ]);
  });

  it("TRUNCATE cuts at the limit (teletalk)", () => {
    expect(screenPages("x".repeat(200), MNO_SCREENS.teletalk, "0. Next", "00. Back")).toEqual(["x".repeat(182)]);
  });

  it("a long Bangla menu pages at the UCS-2 limit of 70 and shows 00 Back", () => {
    const text = ["আপনার সেবা বেছে নিন", "1 সেন্ড মানি", "2 ক্যাশ আউট", "3 মোবাইল রিচার্জ", "4 পেমেন্ট", "5 পে বিল", "6 অ্যাড মানি", "7 আমার অ্যাকাউন্ট"].join("\n");
    expect(text.length).toBeGreaterThan(70);
    const pages = screenPages(text, MNO_SCREENS.gp, "0. পরবর্তী", "00. পেছনে");
    expect(pages.length).toBeGreaterThan(1);
    for (const p of pages) expect(p.length).toBeLessThanOrEqual(70);
    for (const p of pages.slice(0, -1)) expect(p.endsWith("\n0. পরবর্তী\n00. পেছনে")).toBe(true);
    expect(pages.at(-1)!.endsWith("\n00. পেছনে")).toBe(true);
    expect(pages[0]!.startsWith("আপনার সেবা বেছে নিন\n1 সেন্ড মানি")).toBe(true);
    expect(pages.at(-1)).toContain("7 আমার অ্যাকাউন্ট");
  });
});

describe("per-text counter", () => {
  it("English is GSM-7 against 182, Bangla UCS-2 against 70", () => {
    expect(textCounter("Send Money")).toEqual({ length: 10, encoding: "GSM-7", limit: 182, over: false });
    expect(textCounter("সেন্ড মানি")).toEqual({ length: 10, encoding: "UCS-2", limit: 70, over: false });
    expect(textCounter("ক".repeat(71)).over).toBe(true);
    expect(textCounter("a€").encoding).toBe("UCS-2"); // mw-ussd's GSM-7 check has no extension table
  });
});

describe("preview pages a long screen: 0 next page, 00 back", () => {
  const LONG: Menu = {
    start: "main",
    messages: { ...MESSAGES, more: t("0. পরবর্তী", "0. Next"), back: t("00. পেছনে", "00. Back") },
    nodes: {
      main: {
        type: "MENU", text: t("আপনার সেবা বেছে নিন", "Pick a service"),
        options: ["সেন্ড মানি", "ক্যাশ আউট", "মোবাইল রিচার্জ", "পেমেন্ট", "পে বিল", "অ্যাড মানি"]
          .map((bn, i) => ({ key: String(i + 1), label: t(bn, `Item ${i + 1}`), next: "done" })),
      },
      done: { type: "END", text: t("শেষ", "Done") },
    },
  };

  it("shows page 1, 0 goes on, 00 goes back a page, an option still works", () => {
    let p = startPreview(LONG, { msisdn: "01712345678", mno: "gp" });
    expect(p.pages.length).toBeGreaterThan(1);
    expect(p.screen).toBe(p.pages[0]);
    expect(p.screen.endsWith("00. পেছনে")).toBe(true);
    p = stepPreview(p, "0");
    expect(p.screen).toBe(p.pages[1]);
    p = stepPreview(p, "00");
    expect(p.screen).toBe(p.pages[0]);
    p = stepPreview(p, "6");
    expect(p.screen).toBe("শেষ");
    expect(p.ended).toBe(true);
  });

  it("00 on a first page returns to the node before", () => {
    let p = stepPreview(startPreview(MENU, { msisdn: "01712345678" }), "1");
    expect(p.screen).toBe("প্রাপকের নম্বর দিন");
    p = stepPreview(p, "abc"); // an invalid answer stays on the node, pushes nothing
    p = stepPreview(p, "00");
    expect(p.screen).toBe("Bits\n1 সেন্ড মানি\n9 English");
    expect(stepPreview(p, "00").screen).toBe("ভুল ইনপুট।\nBits\n1 সেন্ড মানি\n9 English"); // nothing before start
  });
});

describe("tree editing", () => {
  it("adds a child under a MENU as a new option with an empty bn/en label", () => {
    const m = addChild(MENU, "main", "balance", "END");
    expect(m.nodes.balance).toEqual({ type: "END", text: { bn: "", en: "" } });
    expect(m.nodes.main!.options!.at(-1)).toEqual({ key: "3", label: { bn: "", en: "" }, next: "balance" });
    expect(MENU.nodes.balance).toBeUndefined(); // the input menu is not changed
  });

  it("adds a child under an INPUT as its next, refuses a taken or bad id", () => {
    const m = addChild({ start: "a", messages: {}, nodes: { a: { type: "INPUT", text: t("a", "a") } } }, "a", "b", "END");
    expect(m.nodes.a!.next).toBe("b");
    expect(addChild(MENU, "main", "p2p.pin", "END")).toBe(MENU);
    expect(addChild(MENU, "main", "bad id", "END")).toBe(MENU);
  });

  it("moves a node under another MENU, keeping its option label", () => {
    const m = moveNode(MENU, "cancelled", "main");
    expect(m.nodes["p2p.confirm"]!.options!.map((o) => o.next)).toEqual(["p2p.pin"]);
    expect(m.nodes.main!.options!.at(-1)).toEqual({ key: "3", label: t("বাতিল", "Cancel"), next: "cancelled" });
    expect(moveNode(MENU, "main", "p2p.confirm")).toBe(MENU); // start never moves
    expect(moveNode(MENU, "p2p.done", "p2p.done")).toBe(MENU);
  });

  it("reorders an option up or down", () => {
    expect(moveOption(MENU, "main", 1, -1).nodes.main!.options!.map((o) => o.key)).toEqual(["9", "1"]);
    expect(moveOption(MENU, "main", 0, -1)).toBe(MENU);
  });

  it("deletes a node and every reference to it", () => {
    const m = deleteNode(MENU, "p2p.failed");
    expect(m.nodes["p2p.failed"]).toBeUndefined();
    expect(m.nodes["p2p.send"]!.onFailure).toBeUndefined();
    const n = deleteNode(MENU, "cancelled");
    expect(n.nodes["p2p.confirm"]!.options!.map((o) => o.key)).toEqual(["1"]);
    expect(deleteNode(MENU, "main")).toBe(MENU);
  });
});
