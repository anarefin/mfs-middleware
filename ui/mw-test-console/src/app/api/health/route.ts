// Liveness/readiness for the container healthcheck; no dependency is checked.
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ status: "UP" });
}
