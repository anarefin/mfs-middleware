import { describe, expect, it } from "vitest";
import { DEFAULT_USSD_MNO, mnoLabel, mnoOf, USSD_MNOS } from "./ussd-mnos";

describe("USSD MNOs", () => {
  it("lists the four MNOs mw-ussd serves, each with its own code", () => {
    expect(USSD_MNOS.map((m) => m.id).sort()).toEqual(["bl", "gp", "robi", "teletalk"]);
    expect(new Set(USSD_MNOS.map((m) => m.code)).size).toBe(USSD_MNOS.length);
    for (const m of USSD_MNOS) expect(m.code).toMatch(/^\*\d+#$/);
  });

  it("matches mw-ussd's short codes and transports", () => {
    expect(mnoOf("gp")).toMatchObject({ transport: "SMPP", code: "*268#" });
    expect(mnoOf("robi")).toMatchObject({ transport: "HTTP", code: "*269#" });
    expect(mnoOf("bl")).toMatchObject({ transport: "HTTP", code: "*270#" });
    expect(mnoOf("teletalk")).toMatchObject({ transport: "HTTP", code: "*271#" });
  });

  it("knows the default and refuses an unknown MNO", () => {
    expect(mnoOf(DEFAULT_USSD_MNO)).toBeDefined();
    expect(mnoOf("airtel")).toBeUndefined();
  });

  it("labels an option with transport and code", () => {
    expect(mnoLabel(mnoOf("robi")!)).toBe("robi (HTTP) *269#");
  });
});
