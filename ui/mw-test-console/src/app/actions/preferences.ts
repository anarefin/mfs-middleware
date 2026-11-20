"use server";

import { deviceHeaders, ready } from "@/lib/server/device";
import { Trace } from "@/lib/server/edge";
import type { Outcome } from "@/lib/types";

/** Saved billers, favourites and the notification inbox of one signed-in wallet (ticket 107a). */
export async function preferences(_prev: Outcome | null, form: FormData): Promise<Outcome> {
  const t = new Trace();
  const user = String(form.get("user") ?? "").trim();
  const op = String(form.get("op") ?? "");
  if (!/^[a-z0-9][a-z0-9._-]{1,63}$/.test(user)) return { ok: false, headline: "choose a user", steps: t.steps };
  try {
    await ready(t, user);
    const h = await deviceHeaders(user);
    const id = String(form.get("id") ?? "").trim();
    if (op === "billers") {
      await t.call("this wallet's saved billers", "GET", "/app/v1/saved-billers", { headers: h });
    } else if (op === "saveBiller") {
      const nickname = String(form.get("nickname") ?? "").trim();
      await t.call("save a biller account", "POST", "/app/v1/saved-billers", {
        headers: h,
        json: {
          billerCode: String(form.get("billerCode") ?? "").trim(),
          accountNo: String(form.get("accountNo") ?? "").trim(),
          ...(nickname ? { nickname } : {}),
        },
      }, [201]);
    } else if (op === "deleteBiller") {
      await t.call("remove that saved biller", "DELETE", `/app/v1/saved-billers/${encodeURIComponent(id)}`, { headers: h }, [204]);
    } else if (op === "favourites") {
      await t.call("this wallet's favourites", "GET", "/app/v1/favourites", { headers: h });
    } else if (op === "saveFavourite") {
      const label = String(form.get("label") ?? "").trim();
      await t.call("save a favourite", "POST", "/app/v1/favourites", {
        headers: h,
        json: { msisdn: String(form.get("msisdn") ?? "").trim(), ...(label ? { label } : {}) },
      }, [201]);
    } else if (op === "deleteFavourite") {
      await t.call("remove that favourite", "DELETE", `/app/v1/favourites/${encodeURIComponent(id)}`, { headers: h }, [204]);
    } else if (op === "inbox") {
      await t.call("this wallet's notification inbox", "GET", "/app/v1/notifications?limit=20&offset=0", { headers: h });
    } else if (op === "inboxRead") {
      await t.call("mark that message read", "POST", `/app/v1/notifications/${encodeURIComponent(id)}/read`, { headers: h }, [204]);
    } else {
      return { ok: false, headline: "choose an action", steps: t.steps };
    }
    return { ok: !t.failed, headline: t.failed ? "the app API refused the call" : `${op} for ${user}`, steps: t.steps };
  } catch (e) {
    t.note("stopped", (e as Error).message, false);
    return { ok: false, headline: (e as Error).message, steps: t.steps };
  }
}
