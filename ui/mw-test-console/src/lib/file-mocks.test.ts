import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { amlSampleFile, FILE_FAULTS, FILE_PARTIES, faultBody, isFileParty, runBody } from "./file-mocks";

describe("file mocks", () => {
  it("lists the three file parties with their operations", () => {
    expect(FILE_PARTIES.map((p) => p.name)).toEqual(["aml", "trustbank", "dw"]);
    expect(FILE_PARTIES.find((p) => p.name === "trustbank")?.operations).toEqual(["mt940", "mt942"]);
    expect(isFileParty("aml")).toBe(true);
    expect(isFileParty("ocs")).toBe(false);
    expect(FILE_FAULTS).toContain("INJECTED_MISMATCH");
  });

  it("builds a run body from the form, leaving out what is empty", () => {
    expect(runBody({ operation: "mt942", date: "2026-11-20", at: "14:05" })).toEqual({ operation: "mt942", date: "2026-11-20", at: "14:05" });
    expect(runBody({ operation: "", date: " ", at: undefined })).toEqual({});
  });

  it("builds a fault scenario body with the mismatch kind and count", () => {
    expect(faultBody({ operation: "mt940", behaviour: "INJECTED_MISMATCH", kind: "AMOUNT_MISMATCH", count: "2", nextN: "1" }))
      .toEqual({ operation: "mt940", behaviour: "INJECTED_MISMATCH", code: "AMOUNT_MISMATCH", count: 2, nextN: 1 });
    expect(faultBody({ behaviour: "LATE_FILE", delayMs: "5000", key: "2026-11-20" }))
      .toEqual({ behaviour: "LATE_FILE", delayMs: 5000, key: "2026-11-20" });
  });

  it("makes an AML transaction file whose trailer counts, totals and checksums its rows", () => {
    const file = amlSampleFile("2026112010", ["01711000777", "01811116666", "01712345678"], 1000);
    const lines = file.trimEnd().split("\n");
    expect(lines[0]).toBe("H|AML_TXN|1|F-2026112010|2026-11-20T10:00:00+06:00");
    expect(lines).toHaveLength(5);
    expect(lines[1]?.split("|")[4]).toBe("WALLET:01711000777");
    const body = lines.slice(0, 4).join("\n") + "\n";
    expect(lines[4]).toBe(`T|3|3000|${createHash("sha256").update(body, "utf8").digest("hex")}`);
  });
});
