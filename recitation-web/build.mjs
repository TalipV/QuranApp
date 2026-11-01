// Baut die Tilawa-WebView-Seite nach app/src/main/assets/recitation/
// und lädt Modell + Korpus (NPL-1.2) herunter, falls noch nicht vorhanden.
import { build } from "esbuild";
import { copyFile, mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, "..", "app", "src", "main", "assets", "recitation");
const cache = join(here, ".cache");
await mkdir(out, { recursive: true });
await mkdir(cache, { recursive: true });

await build({
  entryPoints: [join(here, "src", "main.ts")],
  bundle: true,
  format: "esm",
  target: "es2022",
  minify: true,
  outfile: join(out, "bundle.js"),
  logLevel: "info",
});

await copyFile(join(here, "src", "index.html"), join(out, "index.html"));
await copyFile(
  join(here, "node_modules", "onnxruntime-web", "dist", "ort-wasm-simd-threaded.wasm"),
  join(out, "ort-wasm-simd-threaded.wasm"),
);

const assets = JSON.parse(await readFile(join(here, "assets.json"), "utf8"));
for (const [name, url] of Object.entries(assets)) {
  // Cache-Dateiname enthält die URL, damit eine neue URL in assets.json neu lädt
  const cached = join(cache, Buffer.from(url).toString("base64url").slice(-60) + "-" + name);
  const exists = await stat(cached).then(() => true, () => false);
  if (!exists) {
    console.log(`Lade ${name} …`);
    const res = await fetch(url);
    if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
    await writeFile(cached, Buffer.from(await res.arrayBuffer()));
  }
  await copyFile(cached, join(out, name));
}
console.log(`Fertig: ${out}`);
