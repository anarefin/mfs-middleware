// @vitest-environment happy-dom
// A refused submit (e.g. MW-VAL-041 on a partner route, e2e/31-partner-route.spec.ts) must keep what
// the operator typed: React 19 resets a form after its `action` runs, which put every uncontrolled
// field back to its default. Fake values only.
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ActionState } from "./actions";
import { ActionForm } from "./action-form";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const REFUSAL: ActionState = {
  status: 400,
  error: {
    code: "MW-VAL-041", message: "Invalid partner route", correlationId: "00000000-0000-4000-8000-000000000001",
    retryable: false, details: [{ field: "route", issue: "line 3: unknown construct" }],
  },
};

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

describe("ActionForm", () => {
  it("keeps the entered values when the action refuses the submit", async () => {
    const action = vi.fn(async (_prev: ActionState, _form: FormData) => REFUSAL);
    await act(async () => {
      root.render(
        <ActionForm action={action} label="Partner route">
          <textarea name="route" defaultValue="- route: {}" />
          <select name="dryRunOperation" defaultValue="PAY">
            <option>PAY</option>
            <option>INQUIRY</option>
          </select>
          <input name="reason" />
          <button type="submit" name="intent" value="submit">Submit route</button>
        </ActionForm>,
      );
    });

    field<HTMLTextAreaElement>("textarea[name=route]").value = "- route: bad";
    field<HTMLSelectElement>("select[name=dryRunOperation]").value = "INQUIRY";
    field<HTMLInputElement>("input[name=reason]").value = "onboard the fake biller";

    await act(async () => {
      field<HTMLFormElement>("form").requestSubmit(field<HTMLButtonElement>("button"));
    });

    expect(action).toHaveBeenCalledTimes(1);
    const sent = action.mock.calls[0]?.[1];
    expect(sent?.get("route")).toBe("- route: bad");
    expect(sent?.get("reason")).toBe("onboard the fake biller");
    expect(sent?.get("intent")).toBe("submit");
    expect(container.textContent).toContain("MW-VAL-041");
    expect(field<HTMLTextAreaElement>("textarea[name=route]").value).toBe("- route: bad");
    expect(field<HTMLSelectElement>("select[name=dryRunOperation]").value).toBe("INQUIRY");
    expect(field<HTMLInputElement>("input[name=reason]").value).toBe("onboard the fake biller");
  });
});
