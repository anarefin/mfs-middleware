// contracts-lint: validates the root contracts/ folder (spec 4.3, 6.1, 22.1; WP04, ticket 48).
// Usage: node lint.mjs <contracts-dir>
//
// Layout (contracts/README.md):
//   common/<name>.schema.json                         shared types, owned like any other file
//   events/<owner>/<topic>.schema.json                message schema; an envelope when it has a
//                                                     discriminator over a oneOf of payloads
//   events/<owner>/<topic>/<Msg>.payload.schema.json  one payload per message type (owned by the envelope)
//   events/<owner>/samples/<topic>/[<Msg>/]           valid*.json, invalid*.json, expected-errors.json
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { parse as parseYaml } from "yaml";

const root = process.argv[2];
const ID_BASE = "https://contracts.upay.mw/";
const EXPECTED = "expected-errors.json";
const errors = [];
const err = (file, msg) => errors.push(`${file}: ${msg}`);

const walk = (d) =>
  readdirSync(d).flatMap((n) => {
    const p = join(d, n);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
const rel = (p) => relative(root, p).split(sep).join("/");
const readJson = (file) => {
  try {
    return JSON.parse(readFileSync(join(root, file), "utf8"));
  } catch (e) {
    err(file, `not valid JSON (${e.message})`);
    return undefined;
  }
};

const files = walk(root).map(rel).filter((f) => !/(^|\/)(\.gitkeep|README\.md|\.DS_Store)$/.test(f));
const isSample = (f) => /(^|\/)samples\//.test(f);
const PAYLOAD = /^(.+)\/([^/]+)\/([^/]+)\.payload\.schema\.json$/; // <dir>/<topic>/<Msg>.payload.schema.json
const schemaFiles = files.filter((f) => f.endsWith(".schema.json") && !isSample(f));
const payloadFiles = schemaFiles.filter((f) => PAYLOAD.test(f));
const isFragment = (f) => PAYLOAD.test(f) || f.startsWith("common/");
const messageFiles = schemaFiles.filter((f) => !isFragment(f));

// Strict mode catches unknown keywords and typos; strictRequired is off so if/then and oneOf
// branches may require properties declared at the top level. discriminator picks the one oneOf
// branch named by an envelope's type field, so an invalid sample reports only that branch's errors.
const ajv = new Ajv2020({ strict: true, strictRequired: false, allErrors: true, discriminator: true });
addFormats(ajv);

// Amounts travel as integer minor units (valueMinor, spec 5.1): no floating point on the wire.
const findNumbers = (node, path, out) => {
  if (Array.isArray(node)) node.forEach((n, i) => findNumbers(n, `${path}/${i}`, out));
  else if (node && typeof node === "object") {
    const t = node.type;
    if (t === "number" || (Array.isArray(t) && t.includes("number"))) out.push(path || "/");
    for (const [k, v] of Object.entries(node)) findNumbers(v, `${path}/${k}`, out);
  }
  return out;
};

// 1. Register every schema first so relative $refs across files resolve, then compile each.
const schemas = {};
for (const file of schemaFiles) {
  const schema = readJson(file);
  if (schema === undefined) continue;
  schemas[file] = schema;
  if (schema.$id !== ID_BASE + file) err(file, `$id must be ${ID_BASE}${file} (absolute $id, relative $refs)`);
  for (const p of findNumbers(schema, "", []))
    err(file, `uses type "number" at ${p}; amounts are integer minor units (valueMinor), never floats`);
  try {
    ajv.addSchema(schema, ID_BASE + file);
  } catch (e) {
    err(file, `does not compile: ${e.message}`);
    delete schemas[file];
  }
}
const validators = {};
for (const file of Object.keys(schemas)) {
  try {
    validators[file] = ajv.getSchema(ID_BASE + file);
  } catch (e) {
    err(file, `does not compile: ${e.message}`);
  }
}

// 2. Envelopes: the type enum, the oneOf branches and the payload files must name the same types.
const envelopeTag = (schema) => schema?.discriminator?.propertyName;
const topicOf = (file) => file.replace(/\.schema\.json$/, "");
const claimedPayloads = new Set();
for (const file of messageFiles) {
  const schema = schemas[file];
  if (!schema) continue;
  const sv = schema.properties?.schemaVersion;
  if (!sv || !("const" in sv) || !schema.required?.includes("schemaVersion"))
    err(file, "must declare a required schemaVersion property with a const value (spec 6.1)");
  const tag = envelopeTag(schema);
  if (!tag) continue;
  const topic = topicOf(file);
  const types = schema.properties?.[tag]?.enum;
  if (!Array.isArray(types)) {
    err(file, `envelope keyed on ${tag} must list its message types as properties.${tag}.enum`);
    continue;
  }
  const own = payloadFiles.filter((f) => f.startsWith(`${topic}/`) && !f.slice(topic.length + 1).includes("/"));
  own.forEach((f) => claimedPayloads.add(f));
  const names = own.map((f) => f.match(PAYLOAD)[3]);
  for (const f of own) if (!types.includes(f.match(PAYLOAD)[3])) err(f, `payload file has no ${tag} enum value in ${file}`);
  for (const t of types) if (!names.includes(t)) err(file, `${tag} ${t} has no payload file ${topic}/${t}.payload.schema.json`);
  const base = topic.split("/").pop();
  for (const t of types) {
    const branch = (schema.oneOf ?? []).find((b) => b?.properties?.[tag]?.const === t);
    const ref = branch?.properties?.payload?.$ref;
    if (ref !== `${base}/${t}.payload.schema.json`)
      err(file, `oneOf branch for ${t} must set payload to {"$ref": "${base}/${t}.payload.schema.json"} (found ${ref ?? "none"})`);
  }
}
for (const f of payloadFiles.filter((f) => !claimedPayloads.has(f)))
  err(f, "payload schema has no envelope (expected <dir>/<topic>.schema.json keyed on a discriminator)");

// 3. Samples. A message schema's samples live in <dir>/samples/<topic>/; an envelope's live in one
// folder per message type, <dir>/samples/<topic>/<Msg>/, plus optional envelope-level ones.
const sampleFiles = files.filter(isSample);
const claimedSamples = new Set();
const fmt = (e) => `${e.instancePath || "/"} ${e.message}`;
const WRAPPERS = new Set(["if", "anyOf", "oneOf"]); // wrappers repeat their inner errors' cause
let samples = 0;

const checkSampleDir = (file, dir, tag, msg, requireBoth) => {
  const own = sampleFiles.filter((f) => f.startsWith(dir) && !f.slice(dir.length).includes("/"));
  own.forEach((f) => claimedSamples.add(f));
  const names = own.map((f) => f.split("/").pop());
  if (requireBoth && !names.some((n) => /^valid/.test(n))) err(file, `no valid sample in ${dir}`);
  if (requireBoth && !names.some((n) => /^invalid/.test(n))) err(file, `no invalid sample in ${dir}`);
  const expected = names.includes(EXPECTED) ? (readJson(dir + EXPECTED) ?? {}) : {};
  for (const name of Object.keys(expected))
    if (!names.includes(name)) err(dir + EXPECTED, `states a reason for ${name}, which does not exist`);
  const validate = validators[file];
  for (const s of own) {
    const name = s.split("/").pop();
    if (name === EXPECTED) continue;
    if (!/^(valid|invalid)/.test(name)) {
      err(s, "sample name must start with valid or invalid");
      continue;
    }
    const payload = readJson(s);
    if (payload === undefined) continue;
    samples++;
    if (msg !== undefined && payload?.[tag] !== msg) err(s, `${tag} is ${JSON.stringify(payload?.[tag])}, expected ${msg} (its folder)`);
    if (!validate) continue;
    const ok = validate(payload);
    if (/^valid/.test(name)) {
      if (!ok) err(s, `valid sample rejected: ${ajv.errorsText(validate.errors)}`);
      continue;
    }
    if (ok) {
      err(s, "invalid sample was accepted by the schema");
      continue;
    }
    const actual = [...new Set(validate.errors.filter((e) => !WRAPPERS.has(e.keyword)).map(fmt))].sort();
    const stated = expected[name];
    if (!Array.isArray(stated)) {
      err(s, `invalid sample has no stated reason in ${dir}${EXPECTED}; it fails with ${JSON.stringify(actual)}`);
      continue;
    }
    const want = [...new Set(stated)].sort();
    if (JSON.stringify(want) !== JSON.stringify(actual))
      err(s, `fails for ${JSON.stringify(actual)}, not for its stated reason ${JSON.stringify(want)}`);
  }
};

for (const file of messageFiles) {
  const topic = topicOf(file);
  const base = topic.split("/").pop();
  const dir = `${topic.slice(0, topic.length - base.length)}samples/${base}/`;
  const schema = schemas[file];
  const tag = envelopeTag(schema);
  if (!tag) {
    checkSampleDir(file, dir, undefined, undefined, true);
    continue;
  }
  const types = schema.properties?.[tag]?.enum ?? [];
  checkSampleDir(file, dir, tag, undefined, false);
  for (const t of types) checkSampleDir(file, `${dir}${t}/`, tag, t, true);
}
for (const s of sampleFiles.filter((f) => !claimedSamples.has(f)))
  err(s, "sample has no schema or message type (expected samples/<topic>/ or samples/<topic>/<Msg>/ for <topic>.schema.json)");

// 4. OWNERS.yml (spec 4.3 rule 4): every contract file has exactly one owning service. A file
// under <kind>/<owner>/ is owned by that folder's service; common/ may name any service.
// Samples and payload schemas belong to their message schema and need no entry.
const OWNERS = "OWNERS.yml";
let owners = {};
if (!files.includes(OWNERS)) err(OWNERS, "missing; every contract file needs an owner");
else {
  try {
    const doc = parseYaml(readFileSync(join(root, OWNERS), "utf8"));
    owners = doc?.files ?? {};
    if (typeof owners !== "object" || Array.isArray(owners)) throw new Error("'files' must be a map of path: owner");
  } catch (e) {
    err(OWNERS, `cannot be read: ${e.message}`);
  }
}
for (const [path, owner] of Object.entries(owners)) {
  if (!files.includes(path)) err(OWNERS, `entry ${path} names a file that does not exist`);
  if (typeof owner !== "string" || !/^mw-[a-z0-9-]+$/.test(owner))
    err(OWNERS, `entry ${path} has owner '${owner}'; expected a service name like mw-orchestrator`);
  const parts = path.split("/");
  if (parts[0] !== "common" && parts.length > 2 && parts[1] !== owner)
    err(OWNERS, `entry ${path} is owned by ${owner} but lives under the ${parts[1]}/ folder`);
}
for (const f of files.filter((f) => f !== OWNERS && !claimedSamples.has(f) && !claimedPayloads.has(f) && !isSample(f)))
  if (!(f in owners)) err(f, `no owner in ${OWNERS}`);

if (errors.length) {
  for (const e of errors) console.error(`CONTRACTS: ${e}`);
  console.error(`contracts-lint FAILED: ${errors.length} problem(s)`);
  process.exit(1);
}
console.log(`==> contracts-lint passed: ${schemaFiles.length} schema(s), ${samples} sample(s)`);
