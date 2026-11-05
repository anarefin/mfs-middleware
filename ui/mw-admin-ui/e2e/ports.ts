// Ports of the self-contained Playwright run (override with E2E_*_PORT when taken).
export const APP_PORT = Number(process.env.E2E_APP_PORT ?? 3940);
export const IDP_PORT = Number(process.env.E2E_IDP_PORT ?? 4910);
export const API_PORT = Number(process.env.E2E_API_PORT ?? 4920);
export const APP = `http://localhost:${APP_PORT}`;
export const IDP = `http://localhost:${IDP_PORT}`;
export const API = `http://localhost:${API_PORT}`;
