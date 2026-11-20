// Baut die Tilawa-WebView-Seite nach app/src/main/assets/recitation/
// und lädt das Tilawa-Modell herunter (gecacht in .cache/, geprüft per sha256).
import { build } from "esbuild";
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
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

// Modell (Stufe aus src/model.json) von tilawa.dev, geprüft gegen die sha256 der Tilawa-Registry
const { MODEL_REGISTRY, DEFAULTS } = await import("@tilawa/core");
const { tier: tierName } = JSON.parse(await readFile(join(here, "src", "model.json"), "utf8"));
const tier = MODEL_REGISTRY.tiers.find((t) => t.tier === tierName);
if (!tier) throw new Error(`Unbekannte Modellstufe: ${tierName}`);
const modelUrl = `${DEFAULTS.assetBase}${tier.base}${tier.manifest.file ?? "model.onnx"}`;
const cached = join(cache, `${tier.id}-${tier.version}.onnx`);
const valid = async () => {
  const bytes = await readFile(cached).catch(() => null);
  return bytes !== null && createHash("sha256").update(bytes).digest("hex") === tier.sha256;
};
if (!(await valid())) {
  console.log(`Lade Modell ${tier.id}@${tier.version} (${(tier.bytes / 1e6).toFixed(0)} MB) …`);
  const res = await fetch(modelUrl);
  if (!res.ok) throw new Error(`${modelUrl}: HTTP ${res.status}`);
  await writeFile(cached, Buffer.from(await res.arrayBuffer()));
  if (!(await valid())) throw new Error(`${modelUrl}: sha256 stimmt nicht`);
}
await copyFile(cached, join(out, "model.onnx"));

// Quran-Referenz liegt im npm-Paket
await copyFile(join(here, "node_modules", "@tilawa", "core", "assets", "ref-v2.json"), join(out, "ref-v2.json"));

// Reste der 0.x-Version entfernen
for (const old of ["corpus.json", "quran.json"]) await rm(join(out, old), { force: true });

console.log(`Fertig: ${out}`);
