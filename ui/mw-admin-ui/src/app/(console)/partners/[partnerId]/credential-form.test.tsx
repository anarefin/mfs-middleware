// @vitest-environment happy-dom
// The upload form (ticket 131c) sends the secret once and then shows neither the field nor a
// refusal that repeats it. Fake values only.
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ActionState } from "../../actions";
import { CredentialUploadForm } from "./credential-form";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const SECRET = "leaked-credential-value";

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
});

function field<T extends Element>(selector: string): T {
  const el = container.querySelector<T>(selector);
  if (!el) throw new Error(`no ${selector}`);
  return el;
}

describe("credential upload form", () => {
  it("clears the secret after submit and does not echo it in a refusal", async () => {
    const action = vi.fn(async (_prev: ActionState, _form: FormData): Promise<ActionState> => ({
      status: 400,
      error: {
        code: "MW-VAL-001", message: `rejected ${SECRET}`, correlationId: "00000000-0000-4000-8000-000000000131",
        retryable: false, details: [{ field: "secret", issue: `too like ${SECRET}` }],
      },
    }));
    await act(async () => {
      root.render(<CredentialUploadForm action={action} />);
    });

    field<HTMLInputElement>("input[name=name]").value = "hmac-secret";
    field<HTMLInputElement>("input[name=secret]").value = SECRET;
    field<HTMLInputElement>("input[name=reason]").value = "rotate the fake key";

    await act(async () => {
      field<HTMLFormElement>("form").requestSubmit(field<HTMLButtonElement>("button"));
    });

    expect(action).toHaveBeenCalledTimes(1);
    expect(action.mock.calls[0]?.[1].get("secret")).toBe(SECRET);
    expect(field<HTMLInputElement>("input[name=secret]").value).toBe("");
    expect(field<HTMLInputElement>("input[name=reason]").value).toBe("rotate the fake key");
    expect(container.textContent).toContain("MW-VAL-001");
    expect(container.textContent).toContain("****");
    expect(container.textContent).not.toContain(SECRET);
    expect(container.innerHTML).not.toContain(SECRET);
  });
});
