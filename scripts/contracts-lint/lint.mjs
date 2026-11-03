// contracts-lint: validates the root contracts/ folder (spec 4.3, 6.1, 22.1; WP04).
// Usage: node lint.mjs <contracts-dir>
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { parse as parseYaml } from "yaml";

const root = process.argv[2];
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
const schemas = files.filter((f) => f.endsWith(".schema.json"));

// Strict mode catches unknown keywords and typos; strictRequired is off so if/then
// blocks may require properties declared at the top level.
const ajv = new Ajv2020({ strict: true, strictRequired: false, allErrors: true });
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

const sampleDirOf = (schemaFile) => schemaFile.replace(/([^/]+)\.schema\.json$/, "samples/$1/");
const sampleFiles = files.filter((f) => /(^|\/)samples\//.test(f));
const claimed = new Set();

let samples = 0;
for (const file of schemas) {
  const schema = readJson(file);
  if (schema === undefined) continue;
  const sv = schema.properties?.schemaVersion;
  if (!sv || !("const" in sv) || !schema.required?.includes("schemaVersion"))
    err(file, "must declare a required schemaVersion property with a const value (spec 6.1)");
  for (const p of findNumbers(schema, "", []))
    err(file, `uses type "number" at ${p}; amounts are integer minor units (valueMinor), never floats`);
  const own = sampleFiles.filter((f) => f.startsWith(sampleDirOf(file)));
  own.forEach((f) => claimed.add(f));
  const names = own.map((f) => f.split("/").pop());
  if (!names.some((n) => /^valid/.test(n))) err(file, `no valid sample in ${sampleDirOf(file)}`);
  if (!names.some((n) => /^invalid/.test(n))) err(file, `no invalid sample in ${sampleDirOf(file)}`);
  let validate;
  try {
    validate = ajv.compile(schema);
  } catch (e) {
    err(file, `does not compile: ${e.message}`);
    continue;
  }
  for (const s of own) {
    const payload = readJson(s);
    if (payload === undefined) continue;
    samples++;
    const name = s.split("/").pop();
    const ok = validate(payload);
    if (/^valid/.test(name) && !ok) err(s, `valid sample rejected: ${ajv.errorsText(validate.errors)}`);
    else if (/^invalid/.test(name) && ok) err(s, "invalid sample was accepted by the schema");
    else if (!/^(valid|invalid)/.test(name)) err(s, "sample name must start with valid or invalid");
  }
}

for (const s of sampleFiles.filter((f) => !claimed.has(f)))
  err(s, "sample has no schema (expected <dir>/<name>.schema.json next to samples/<name>/)");

// OWNERS.yml (spec 4.3 rule 4): every contract file has exactly one owning service, and it
// matches the <kind>/<owning-service>/ folder the file lives in. Samples belong to their schema.
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
  const folder = path.split("/")[1];
  if (path.split("/").length > 2 && folder !== owner)
    err(OWNERS, `entry ${path} is owned by ${owner} but lives under the ${folder}/ folder`);
}
for (const f of files.filter((f) => f !== OWNERS && !claimed.has(f)))
  if (!(f in owners)) err(f, `no owner in ${OWNERS}`);

if (errors.length) {
  for (const e of errors) console.error(`CONTRACTS: ${e}`);
  console.error(`contracts-lint FAILED: ${errors.length} problem(s)`);
  process.exit(1);
}
console.log(`==> contracts-lint passed: ${schemas.length} schema(s), ${samples} sample(s)`);
