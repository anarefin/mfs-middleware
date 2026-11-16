import { describe, expect, it } from "vitest";
import { APP_PERSONAS, PORTAL_PERSONAS } from "./personas";

describe("personas", () => {
  it("label the users with their real names, ids unchanged", () => {
    expect(APP_PERSONAS.map((p) => [p.user, p.msisdn])).toEqual([
      ["customer01", "01711000001"], ["customer02", "01711000002"], ["agent01", "01811000001"], ["merchant01", "01911000001"], ["merchant02", "01911000002"],
    ]);
    expect(APP_PERSONAS[0]?.label).toBe("Rahim Uddin (customer01)");
    expect(PORTAL_PERSONAS.find((p) => p.user === "org-maker01")?.label).toMatch(/^Mizanur Rahman \(org-maker01/);
  });
});
