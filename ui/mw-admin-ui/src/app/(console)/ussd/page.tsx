import { History } from "lucide-react";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
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
      <PageHeader title={<>USSD menu <span className="font-mono">{menuId}</span></>} description="Versions made in the console, a tree editor with a preview walk, and the proposal as a change request." />
      <Card>
        <form className="flex flex-wrap items-end gap-3 p-4" method="get" action={`${BASE_PATH}/ussd`}>
          <Field label="Menu id" className="w-64 max-w-full"><Input name="menuId" defaultValue={menuId} pattern="[a-z0-9][a-z0-9_\-]{0,31}" className="font-mono" /></Field>
          <Button type="submit">Open</Button>
        </form>
      </Card>

      <Card className="overflow-hidden">
        <CardHeader><CardTitle><History />Versions</CardTitle></CardHeader>
        {!versions.ok ? <div className="p-4"><ApiError status={versions.status} error={versions.error} /></div> : items.length === 0 ? (
          <p className="m-0 px-4 py-8 text-center text-muted-foreground">No versions made in the console yet (the seeded first version lives in mw-ussd).</p>
        ) : (
          <Table data-testid="menu-versions">
            <TableHeader><TableRow><TableHead>Version</TableHead><TableHead>Status</TableHead><TableHead>Go-live</TableHead><TableHead>Maker</TableHead><TableHead>Checker</TableHead><TableHead>Created</TableHead><TableHead>Decided</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {items.map((v) => (
                <TableRow key={v.version} className={v.version === baseVersion?.version ? "highlight" : undefined}>
                  <TableCell className="font-medium tabular-nums">v{v.version}</TableCell>
                  <TableCell><StatusBadge status={v.status} /></TableCell>
                  <TableCell className="text-xs">{v.version === baseVersion?.version ? activations(baseVersion) : ""}</TableCell>
                  <TableCell className="text-muted-foreground">{v.makerId}</TableCell><TableCell className="text-muted-foreground">{v.checkerId ?? ""}</TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(v.createdAt)}</TableCell><TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(v.decidedAt)}</TableCell>
                  <TableCell className="text-right whitespace-nowrap"><a href={`${BASE_PATH}/ussd?menuId=${menuId}&from=${v.version}`}>edit from</a></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
      {base && !base.ok ? <ApiError status={base.status} error={base.error} /> : null}

      {open ? <PendingNotice cr={open} /> : null}
      <MenuEditor
        key={`${menuId}@${baseVersion?.version ?? 0}`}
        initial={menu}
        baseLabel={baseVersion ? `v${baseVersion.version} (${baseVersion.status})` : null}
        action={maker ? proposeMenu.bind(null, menuId) : null}
      />
      {!canMake(session?.roles ?? []) ? <p className="m-0 text-sm text-muted-foreground">Only an ops-maker proposes menu versions; the preview works for everyone.</p> : null}
    </>
  );
}
