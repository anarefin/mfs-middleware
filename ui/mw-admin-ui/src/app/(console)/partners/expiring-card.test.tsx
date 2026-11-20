import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { expiringCredential, unavailable } from "@/lib/testing/admin-fixtures";
import { ExpiringCredentialsCard } from "./expiring-card";

const SECRET = "leaked-credential-value";

describe("credentials expiring card", () => {
  it("lists metadata for a credential inside the window and links to its partner", () => {
    const html = renderToStaticMarkup(<ExpiringCredentialsCard result={{ ok: true, data: { items: [expiringCredential] } }} />);
    expect(html).toContain("Credentials expiring");
    expect(html).toContain('href="/partners/ocs?tab=credentials"');
    expect(html).toContain("hmac-secret");
    expect(html).toContain("HMAC_SECRET");
    expect(html).toContain(">12<");
    expect(html).not.toContain(SECRET);
    expect(html).not.toContain('type="password"');
  });

  it("says when nothing expires soon", () => {
    const html = renderToStaticMarkup(<ExpiringCredentialsCard result={{ ok: true, data: { items: [] } }} />);
    expect(html).toContain("No credentials expire within 30 days.");
  });

  it("shows the admin API envelope when the list fails", () => {
    const html = renderToStaticMarkup(<ExpiringCredentialsCard result={{ ok: false, status: 503, error: unavailable }} />);
    expect(html).toContain("MW-SYS-001");
  });
});
