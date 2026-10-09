// Checks the backend's runtime libraries against the OSV vulnerability database (https://osv.dev).
//
//   mvn -B dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt   (in backend/)
//   node scripts/audit-dependencies.mjs backend/target/runtime-dependencies.txt
//
// Exits 1 if any library has a known advisory, 2 if the check itself could not run. Only the names and versions of
// open-source libraries are sent. The frontend is covered by "npm audit --omit=dev".
import fs from "node:fs";

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

const affected = libraries
  .map((library, i) => ({ ...library, advisories: (results[i]?.vulns ?? []).map((v) => v.id) }))
  .filter((library) => library.advisories.length > 0);

if (affected.length === 0) {
  console.log(`${libraries.length} runtime libraries checked: no known advisories.`);
  process.exit(0);
}
console.error(`${affected.length} of ${libraries.length} runtime libraries have known advisories:`);
for (const library of affected) {
  console.error(`  ${library.name} ${library.version}: ${library.advisories.join(", ")}  (https://osv.dev/list?q=${encodeURIComponent(library.name)})`);
}
process.exit(1);
