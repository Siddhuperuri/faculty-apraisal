// Checks the backend's runtime libraries against the OSV vulnerability database (https://osv.dev).
//
//   mvn -B dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt   (in backend/)
//   node scripts/audit-dependencies.mjs backend/target/runtime-dependencies.txt
//
// Exits 1 if any library has a known advisory, 2 if the check itself could not run. Only the names and versions of
// open-source libraries are sent. The frontend is covered by "npm audit --omit=dev".
//
// An advisory that has been read and judged not to apply is recorded in scripts/accepted-advisories.json with the
// reason. It is then reported but does not fail the check, and only for the library version it was judged for and only
// until its reviewBy date: a new version, an expired date or an advisory that no longer appears all need a decision.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const file = process.argv[2];
if (!file || !fs.existsSync(file)) {
  console.error("usage: node scripts/audit-dependencies.mjs <file written by mvn dependency:list>");
  process.exit(2);
}

// lines look like "   group:artifact:jar:version:scope -- module ..."
const libraries = fs.readFileSync(file, "utf8").split(/\r?\n/)
  .map((line) => line.trim().split(" ")[0].split(":"))
  .filter((parts) => parts.length >= 5 && parts[2] === "jar")
  .map(([group, artifact, , version]) => ({ name: `${group}:${artifact}`, version }));
if (libraries.length === 0) {
  console.error(`No libraries found in ${file}.`);
  process.exit(2);
}

let results;
try {
  const response = await fetch("https://api.osv.dev/v1/querybatch", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ queries: libraries.map((l) => ({ package: { ecosystem: "Maven", name: l.name }, version: l.version })) }),
  });
  if (!response.ok) throw new Error(`OSV answered ${response.status}`);
  results = (await response.json()).results;
} catch (e) {
  console.error(`The vulnerability database could not be reached: ${e.message}`);
  process.exit(2);
}

// Advisories judged not to apply (see the header). Anything malformed stops the check rather than being skipped.
let accepted = [];
const acceptedFile = path.join(path.dirname(fileURLToPath(import.meta.url)), "accepted-advisories.json");
try {
  if (fs.existsSync(acceptedFile)) accepted = JSON.parse(fs.readFileSync(acceptedFile, "utf8"));
  const valid = Array.isArray(accepted) && accepted.every((a) =>
    a && [a.id, a.library, a.version, a.reason].every((v) => typeof v === "string" && v.trim()) && /^\d{4}-\d{2}-\d{2}$/.test(a.reviewBy ?? ""));
  if (!valid) throw new Error("every entry needs id, library, version, reason and reviewBy (YYYY-MM-DD)");
} catch (e) {
  console.error(`${acceptedFile} cannot be used: ${e.message}`);
  process.exit(2);
}
const today = new Date().toISOString().slice(0, 10);

const found = libraries.flatMap((library, i) => (results[i]?.vulns ?? []).map((v) => ({ ...library, id: v.id })));
const matches = (entry, f) => entry.id === f.id && entry.library === f.name && entry.version === f.version;
const waived = [];
const unresolved = [];
for (const f of found) {
  const entry = accepted.find((a) => matches(a, f));
  if (!entry) unresolved.push({ ...f, note: "" });
  else if (entry.reviewBy < today) unresolved.push({ ...f, note: `  [accepted until ${entry.reviewBy}: the review is overdue]` });
  else waived.push({ ...f, entry });
}

for (const w of waived) {
  console.log(`accepted, not applicable (review by ${w.entry.reviewBy}): ${w.name} ${w.version} ${w.id}\n    ${w.entry.reason}`);
}
for (const entry of accepted.filter((a) => !found.some((f) => matches(a, f)))) {
  console.warn(`no longer reported: ${entry.library} ${entry.version} ${entry.id}; remove it from scripts/accepted-advisories.json`);
}

if (unresolved.length === 0) {
  console.log(`${libraries.length} runtime libraries checked: no unreviewed advisories` + (waived.length ? ` (${waived.length} accepted, listed above).` : "."));
  process.exit(0);
}
const byLibrary = new Map();
for (const f of unresolved) {
  const label = `${f.name} ${f.version}`;
  byLibrary.set(label, [...(byLibrary.get(label) ?? []), f]);
}
console.error(`${byLibrary.size} of ${libraries.length} runtime libraries have known advisories:`);
for (const [label, list] of byLibrary) {
  console.error(`  ${label}: ${list.map((f) => f.id + f.note).join(", ")}  (https://osv.dev/list?q=${encodeURIComponent(list[0].name)})`);
}
process.exit(1);
