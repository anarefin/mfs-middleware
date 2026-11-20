import type { ApiResult, ChangeRequest, ChangeRequestPage, ResourceType } from "@/lib/types";
import { adminApi } from "@/lib/server/admin";

export { PendingNotice } from "./pending-notice-view";

/** The open (PENDING) request of a resource, if any (mw-admin-api allows one per resource). */
export async function openRequest(token: string, resourceType: ResourceType, resourceId: string): Promise<ChangeRequest | undefined> {
  const q = new URLSearchParams({ resourceType, resourceId, status: "PENDING", limit: "1" });
  const r: ApiResult<ChangeRequestPage> = await adminApi().listChangeRequests(token, q);
  return r.ok ? r.data.items[0] : undefined;
}
