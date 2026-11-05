import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { canMake } from "@/lib/approvals";
import { formatTime } from "@/lib/format";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { BASE_PATH } from "@/lib/server/env";
import type { ContentVersion } from "@/lib/types";
import { parseMenu, parseMenuId, type Menu } from "@/lib/ussd-menu";
import { proposeMenu } from "../actions";
import { ApiError } from "../api-error";
import { openRequest, PendingNotice } from "../pending-notice";
import { MenuEditor } from "./menu-editor";

// USSD menus (spec 9.2, 15.2, tickets 39 / 42): the versions of one menu, a tree editor on the
// newest published version (or one picked with ?from=), a preview walk, and the proposal (go-live
// per MNO) as a change request an ops-checker approves.

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : undefined);

function activations(v: ContentVersion): string {
  const a = v.value?.activations;
  if (!Array.isArray(a)) return "";
  return a.map((x: { mno?: string; goLiveAt?: string }) => `${x.mno}${x.goLiveAt ? ` ${formatTime(x.goLiveAt)}` : ""}`).join(", ");
}

export default async function UssdPage({ searchParams }: Props) {
  const sp = await searchParams;
  const menuId = parseMenuId(one(sp.menuId) ?? "main") ?? "main";
  const from = Number(one(sp.from));
  const session = await auth();
  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");

  const api = adminApi();
  const versions = await api.listMenuVersions(token, menuId);
  const items = versions.ok ? versions.data.items : [];
  const baseNo = Number.isInteger(from) && from > 0 ? from : items.find((v) => v.status === "PUBLISHED")?.version;
  const base = baseNo ? await api.getMenuVersion(token, menuId, baseNo) : undefined;
  const baseVersion = base?.ok ? base.data : undefined;
  const menu: Menu | null = baseVersion?.value?.menu ? parseMenu(JSON.stringify(baseVersion.value.menu)) : null;
  const open = await openRequest(token, "ussd_menu", menuId);
  const maker = canMake(session?.roles ?? []) && !open;

  return (
    <>
      <h1>USSD menu <span className="mono">{menuId}</span></h1>
      <form className="panel search" method="get" action={`${BASE_PATH}/ussd`}>
        <label>Menu id<input name="menuId" defaultValue={menuId} pattern="[a-z0-9][a-z0-9_\-]{0,31}" /></label>
        <button className="primary" type="submit">Open</button>
      </form>

      <h2>Versions</h2>
      {!versions.ok ? <ApiError status={versions.status} error={versions.error} /> : items.length === 0 ? (
        <p className="hint">No versions made in the console yet (the seeded first version lives in mw-ussd).</p>
      ) : (
        <table data-testid="menu-versions">
          <thead><tr><th>Version</th><th>Status</th><th>Go-live</th><th>Maker</th><th>Checker</th><th>Created</th><th>Decided</th><th /></tr></thead>
          <tbody>
            {items.map((v) => (
              <tr key={v.version} className={v.version === baseVersion?.version ? "highlight" : undefined}>
                <td>v{v.version}</td>
                <td className={`status ${v.status}`}>{v.status}</td>
                <td>{v.version === baseVersion?.version ? activations(baseVersion) : ""}</td>
                <td>{v.makerId}</td><td>{v.checkerId ?? ""}</td>
                <td>{formatTime(v.createdAt)}</td><td>{formatTime(v.decidedAt)}</td>
                <td><a href={`${BASE_PATH}/ussd?menuId=${menuId}&from=${v.version}`}>edit from</a></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {base && !base.ok ? <ApiError status={base.status} error={base.error} /> : null}

      {open ? <PendingNotice cr={open} /> : null}
      <MenuEditor
        key={`${menuId}@${baseVersion?.version ?? 0}`}
        initial={menu}
        baseLabel={baseVersion ? `v${baseVersion.version} (${baseVersion.status})` : null}
        action={maker ? proposeMenu.bind(null, menuId) : null}
      />
      {!canMake(session?.roles ?? []) ? <p className="hint">Only an ops-maker proposes menu versions; the preview works for everyone.</p> : null}
    </>
  );
}
