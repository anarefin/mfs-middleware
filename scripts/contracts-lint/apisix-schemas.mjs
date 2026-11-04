// apisix-schemas: request-validation schemas for the APISIX edge (spec 8.3), derived from the
// OpenAPI contracts in contracts/ (ticket 13). Usage: node apisix-schemas.mjs --check|--write <repo-root>
//
// Every deploy/apisix/schemas/*.json names its source in "x-mw-derived-from"
// ("<contracts-relative file>#<JSON pointer>"). --check fails when a file is not exactly what
// the derivation gives today; --write rewrites the files. To add one, create the file with only
// { "x-mw-derived-from": "..." } and run scripts/apisix-schemas.sh --write.
//
// The derivation targets APISIX's Lua validator (api7 jsonschema, draft 4-7):
//   - local $refs are inlined;
//   - description/title/examples/deprecated/readOnly/writeOnly are dropped (documentation);
//   - pattern and format are dropped: the Lua validator writes a failed pattern or format
//     WITH the offending value (an MSISDN) to the APISIX error log (SEC-10); the owning
//     service still checks them;
//   - propertyNames { enum: [...] } (ignored by the Lua validator) becomes properties for each
//     allowed name plus additionalProperties: false, which accepts exactly the same objects;
//   - any other keyword not listed in KEEP fails the derivation, so a contract change the edge
//     cannot express is noticed instead of silently weakening the check.
import { readdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { parse as parseYaml } from "yaml";

const [mode, root] = process.argv.slice(2);
if (!["--check", "--write"].includes(mode) || !root) {
  console.error("usage: node apisix-schemas.mjs --check|--write <repo-root>");
  process.exit(2);
}
const SOURCE = "x-mw-derived-from";
const DROP = new Set(["description", "title", "example", "examples", "deprecated", "readOnly", "writeOnly", "pattern", "format"]);
const KEEP = new Set([
  "type", "required", "properties", "additionalProperties", "enum", "const",
  "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
  "minLength", "maxLength", "minProperties", "maxProperties",
  "items", "minItems", "maxItems", "uniqueItems", "oneOf", "anyOf", "allOf", "not",
]);
const SUB_SCHEMA = new Set(["additionalProperties", "items", "not"]);
const SUB_SCHEMA_LIST = new Set(["oneOf", "anyOf", "allOf"]);

const pointer = (doc, ptr, where) => {
  let node = doc;
  for (const raw of ptr.replace(/^#?\/?/, "").split("/").filter(Boolean)) {
    const key = raw.replace(/~1/g, "/").replace(/~0/g, "~");
    if (node == null || !(key in node)) throw new Error(`${where}: no ${ptr}`);
    node = node[key];
  }
  return node;
};

function derive(node, doc, where, seen = []) {
  if (typeof node !== "object" || node === null || Array.isArray(node)) return node;
  if (node.$ref !== undefined) {
    if (!node.$ref.startsWith("#/")) throw new Error(`${where}: only local $refs are supported (${node.$ref})`);
    if (seen.includes(node.$ref)) throw new Error(`${where}: recursive $ref ${node.$ref}`);
    return derive(pointer(doc, node.$ref, where), doc, `${where} -> ${node.$ref}`, [...seen, node.$ref]);
  }
  const out = {};
  for (const [k, v] of Object.entries(node)) {
    if (DROP.has(k) || k === "propertyNames") continue;
    if (!KEEP.has(k)) throw new Error(`${where}: keyword "${k}" has no edge equivalent; extend scripts/contracts-lint/apisix-schemas.mjs`);
    if (k === "properties") {
      out.properties = Object.fromEntries(Object.entries(v).map(([p, s]) => [p, derive(s, doc, `${where}/properties/${p}`, seen)]));
    } else if (SUB_SCHEMA.has(k)) {
      out[k] = typeof v === "boolean" ? v : derive(v, doc, `${where}/${k}`, seen);
    } else if (SUB_SCHEMA_LIST.has(k)) {
      out[k] = v.map((s, i) => derive(s, doc, `${where}/${k}/${i}`, seen));
    } else {
      out[k] = v;
    }
  }
  if (node.propertyNames !== undefined) {
    const names = node.propertyNames;
    const keys = Object.keys(names);
    if (keys.length !== 1 || !Array.isArray(names.enum)) {
      throw new Error(`${where}: only propertyNames { enum: [...] } is supported`);
    }
    const each = out.additionalProperties === undefined || out.additionalProperties === true ? {} : out.additionalProperties;
    out.properties = { ...(out.properties ?? {}) };
    for (const name of names.enum) if (!(name in out.properties)) out.properties[name] = each;
    out.additionalProperties = false;
  }
  return out;
}

const dir = join(root, "deploy", "apisix", "schemas");
if (!existsSync(dir)) process.exit(0);
const docs = new Map();
const load = (file) => {
  if (!docs.has(file)) {
    const path = join(root, "contracts", file);
    if (!existsSync(path)) throw new Error(`contracts/${file} does not exist`);
    const text = readFileSync(path, "utf8");
    docs.set(file, file.endsWith(".json") ? JSON.parse(text) : parseYaml(text));
  }
  return docs.get(file);
};

const problems = [];
let n = 0;
for (const name of readdirSync(dir).filter((f) => f.endsWith(".json")).sort()) {
  const file = `deploy/apisix/schemas/${name}`;
  try {
    const current = readFileSync(join(dir, name), "utf8");
    const source = JSON.parse(current)[SOURCE];
    if (typeof source !== "string" || !source.includes("#")) throw new Error(`no "${SOURCE}": "<contract file>#<pointer>"`);
    const [contract, ptr] = source.split("#");
    const doc = load(contract);
    const wanted = JSON.stringify({ [SOURCE]: source, ...derive(pointer(doc, ptr, contract), doc, source) }, null, 2) + "\n";
    n++;
    if (current === wanted) continue;
    if (mode === "--write") {
      writeFileSync(join(dir, name), wanted);
      console.log(`apisix-schemas: wrote ${file}`);
    } else {
      problems.push(`${file}: differs from ${source} (run scripts/apisix-schemas.sh --write)`);
    }
  } catch (e) {
    problems.push(`${file}: ${e.message}`);
  }
}
if (problems.length) {
  for (const p of problems) console.error(`apisix-schemas: ${p}`);
  process.exit(1);
}
console.log(`apisix-schemas: ${n} edge schema(s) match their contracts`);
