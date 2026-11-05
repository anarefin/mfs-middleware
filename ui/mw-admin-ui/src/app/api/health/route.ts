// Liveness/readiness for the container healthcheck (20.1); no dependency is checked.
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ status: "UP" });
}
