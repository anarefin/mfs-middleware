import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { StatusBadge, statusTone } from "./status-badge";

describe("StatusBadge", () => {
  it("colours known states and keeps the raw code as text", () => {
    expect(statusTone("COMPLETED")).toBe("good");
    expect(statusTone("MANUAL_REVIEW")).toBe("warn");
    expect(statusTone("REVERSED")).toBe("rev");
    expect(statusTone("enabled")).toBe("good");
    expect(statusTone("SOMETHING_NEW")).toBe("neutral");
    const html = renderToStaticMarkup(<StatusBadge status="PENDING" data-testid="status" />);
    expect(html).toContain('data-testid="status"');
    expect(html).toContain(">PENDING</span>");
  });
});
