import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Where the suite finds the running stack. `make ui-e2e` sets every value (scripts/ui-e2e.sh); the
 * defaults match deploy/docker-compose.yml (APISIX on https://localhost, project mfs-mw).
 */
export const BASE_URL = (process.env.E2E_BASE_URL ?? "https://localhost").replace(/\/+$/, "");

/** Compose project of the stack, for the container health check (COMPOSE_PROJECT_NAME). */
export const COMPOSE_PROJECT = process.env.E2E_COMPOSE_PROJECT ?? "mfs-mw";

/** The seed manifest `make seed` copies out (default: the repo's build/seed/manifest.json). */
export function manifestPath(): string {
  return process.env.E2E_SEED_MANIFEST ?? resolve(fileURLToPath(new URL(".", import.meta.url)), "../../../../build/seed/manifest.json");
}

/** The USSD gateway credential of MNO robi (HTTP transport), to play a subscriber: deploy/.env. */
export const USSD_MNO = "robi";
export const USSD_SECRET_ENV = "USSD_ROBI_GATEWAY_SECRET";

/** The seeded customer wallets (mw-seeder application.yml); seed PIN of mw-core-mock (fake 1234). */
export const CUSTOMER_A = "01712000001";
export const CUSTOMER_B = "01712000002";
export const SEED_PIN = "1234";
