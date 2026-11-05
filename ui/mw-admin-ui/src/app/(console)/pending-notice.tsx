import Link from "next/link";
import { formatTime } from "@/lib/format";
import type { ApiResult, ChangeRequest, ChangeRequestPage } from "@/lib/types";
import { adminApi } from "@/lib/server/admin";
import type { ResourceType } from "@/lib/types";

/** The open (PENDING) request of a resource, if any (mw-admin-api allows one per resource). */
export async function openRequest(token: string, resourceType: ResourceType, resourceId: string): Promise<ChangeRequest | undefined> {
  const q = new URLSearchParams({ resourceType, resourceId, status: "PENDING", limit: "1" });
  const r: ApiResult<ChangeRequestPage> = await adminApi().listChangeRequests(token, q);
  return r.ok ? r.data.items[0] : undefined;
}

export function PendingNotice({ cr }: { cr: ChangeRequest }) {
  return (
    <p className="notice" data-testid="pending-request">
      {cr.action} requested by {cr.makerId} on {formatTime(cr.createdAt)}, waiting for approval in{" "}
      <Link href="/approvals">Approvals</Link>.
    </p>
  );
}
