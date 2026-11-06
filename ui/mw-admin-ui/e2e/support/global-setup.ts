import { BASE_URL, COMPOSE_PROJECT, manifestPath } from "./env";
import { liveProbes, notReadyMessage, stackProblems } from "./stack";
import { resetState } from "./state";

/** Fails fast, with the fix, when the stack is not up or not seeded; then starts a fresh run state. */
export default async function globalSetup() {
  const problems = await stackProblems(
    { baseURL: BASE_URL, project: COMPOSE_PROJECT, manifestPath: manifestPath(), env: process.env },
    liveProbes,
  );
  if (problems.length) throw new Error(notReadyMessage(problems));
  resetState();
}
