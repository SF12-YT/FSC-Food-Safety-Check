const encoder = new TextEncoder();
const BUCKET = "fosch-public-product-media";
const SOURCE_BASE = "https://mqgvagftvuybietazbdc.supabase.co/storage/v1/object/public/" + BUCKET + "/";
const MAX_OBJECT_BYTES = 5 * 1024 * 1024;
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin"
};

function jsonResponse(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
  });
}
function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error("Server configuration is missing " + name + ".");
  return value;
}
function getPublishableKey(): string {
  const legacy = Deno.env.get("SUPABASE_ANON_KEY") || Deno.env.get("SUPABASE_PUBLISHABLE_KEY");
  const plural = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS");
  if (plural) {
    try {
      const keys = JSON.parse(plural);
      if (keys && typeof keys.default === "string" && keys.default) return keys.default;
    } catch (_) { throw new Error("Supabase publishable-key configuration is invalid."); }
  }
  if (legacy?.trim()) return legacy.trim();
  throw new Error("Supabase publishable key is unavailable in the Edge Function environment.");
}
function normalizePublicBase(value: string): string {
  let url: URL;
  try { url = new URL(value); } catch (_) { throw new Error("R2 public URL is invalid."); }
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error("R2 public URL must be an HTTPS origin without a path.");
  }
  return url.origin;
}
function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}
function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(copy).set(bytes);
  return copy;
}
async function sha256(bytes: Uint8Array): Promise<string> {
  return toHex(new Uint8Array(await crypto.subtle.digest("SHA-256", toArrayBuffer(bytes))));
}
async function hmac(key: Uint8Array, message: string): Promise<Uint8Array> {
  const imported = await crypto.subtle.importKey("raw", toArrayBuffer(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", imported, toArrayBuffer(encoder.encode(message))));
}
function awsEncode(value: string): string {
  return encodeURIComponent(value).replace(/[!'()*]/g, (character) =>
    "%" + character.charCodeAt(0).toString(16).toUpperCase()
  );
}
function validMediaPath(path: unknown): path is string {
  if (typeof path !== "string" || path.length > 240 || path.startsWith("/") || path.includes("\\") ||
      path.includes("%") || path.split("/").some((part) => !part || part === "." || part === "..")) return false;
  return /^products\/(?:[A-Z]{2}|INT)\/[A-Za-z0-9_-]{1,80}\/[0-9a-f]{16}\.(?:jpeg|png|webp)$/.test(path) ||
    /^brands\/[a-z0-9]+(?:-[a-z0-9]+)*\.jpeg$/.test(path);
}
function expectedMime(path: string): string {
  if (path.endsWith(".png")) return "image/png";
  if (path.endsWith(".webp")) return "image/webp";
  return "image/jpeg";
}
async function signedR2Put(path: string, bytes: Uint8Array, contentType: string): Promise<void> {
  const accountId = requiredEnv("CLOUDFLARE_ACCOUNT_ID");
  const accessKeyId = requiredEnv("R2_ACCESS_KEY_ID");
  const secretAccessKey = requiredEnv("R2_SECRET_ACCESS_KEY");
  const bucket = requiredEnv("R2_BUCKET_NAME");
  if (!/^[a-f0-9]{32}$/i.test(accountId) || !/^[a-z0-9-]{3,63}$/.test(bucket)) {
    throw new Error("Cloudflare R2 configuration is invalid.");
  }
  const canonicalUri = "/" + awsEncode(bucket) + "/" + path.split("/").map(awsEncode).join("/");
  const requestUrl = "https://" + accountId + ".r2.cloudflarestorage.com" + canonicalUri;
  const host = new URL(requestUrl).host;
  const amzDate = new Date().toISOString().replace(/[:-]|\.\d{3}/g, "");
  const dateStamp = amzDate.slice(0, 8);
  const payloadHash = await sha256(bytes);
  const signedHeaders = "host;x-amz-content-sha256;x-amz-date";
  const canonicalHeaders = "host:" + host + "\n" +
    "x-amz-content-sha256:" + payloadHash + "\n" +
    "x-amz-date:" + amzDate + "\n";
  const canonicalRequest = ["PUT", canonicalUri, "", canonicalHeaders, signedHeaders, payloadHash].join("\n");
  const scope = dateStamp + "/auto/s3/aws4_request";
  const stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" +
    await sha256(encoder.encode(canonicalRequest));
  const kDate = await hmac(encoder.encode("AWS4" + secretAccessKey), dateStamp);
  const kRegion = await hmac(kDate, "auto");
  const kService = await hmac(kRegion, "s3");
  const kSigning = await hmac(kService, "aws4_request");
  const signature = toHex(await hmac(kSigning, stringToSign));
  const response = await fetch(requestUrl, {
    method: "PUT",
    headers: {
      "Authorization": "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope +
        ", SignedHeaders=" + signedHeaders + ", Signature=" + signature,
      "x-amz-content-sha256": payloadHash,
      "x-amz-date": amzDate,
      "Content-Type": contentType,
      "Cache-Control": path.startsWith("brands/") ? "public, max-age=3600" : "public, max-age=31536000, immutable"
    },
    body: bytes as unknown as BodyInit
  });
  if (!response.ok) {
    console.error("FOSCH migration R2 upload failed with HTTP", response.status, (await response.text()).slice(0, 180));
    throw new Error("Cloudflare R2 rejected the upload (HTTP " + response.status + ").");
  }
}
async function signedR2Get(path: string): Promise<Uint8Array> {
  const accountId = requiredEnv("CLOUDFLARE_ACCOUNT_ID");
  const accessKeyId = requiredEnv("R2_ACCESS_KEY_ID");
  const secretAccessKey = requiredEnv("R2_SECRET_ACCESS_KEY");
  const bucket = requiredEnv("R2_BUCKET_NAME");
  const canonicalUri = "/" + awsEncode(bucket) + "/" + path.split("/").map(awsEncode).join("/");
  const requestUrl = "https://" + accountId + ".r2.cloudflarestorage.com" + canonicalUri;
  const host = new URL(requestUrl).host;
  const amzDate = new Date().toISOString().replace(/[:-]|\.\d{3}/g, "");
  const dateStamp = amzDate.slice(0, 8);
  const emptyPayloadHash = await sha256(new Uint8Array());
  const signedHeaders = "host;x-amz-content-sha256;x-amz-date";
  const canonicalHeaders = "host:" + host + "\n" +
    "x-amz-content-sha256:" + emptyPayloadHash + "\n" +
    "x-amz-date:" + amzDate + "\n";
  const canonicalRequest = ["GET", canonicalUri, "", canonicalHeaders, signedHeaders, emptyPayloadHash].join("\n");
  const scope = dateStamp + "/auto/s3/aws4_request";
  const stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" +
    await sha256(encoder.encode(canonicalRequest));
  const kDate = await hmac(encoder.encode("AWS4" + secretAccessKey), dateStamp);
  const kRegion = await hmac(kDate, "auto");
  const kService = await hmac(kRegion, "s3");
  const kSigning = await hmac(kService, "aws4_request");
  const signature = toHex(await hmac(kSigning, stringToSign));
  const response = await fetch(requestUrl, {
    method: "GET",
    headers: {
      "Authorization": "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope +
        ", SignedHeaders=" + signedHeaders + ", Signature=" + signature,
      "x-amz-content-sha256": emptyPayloadHash,
      "x-amz-date": amzDate
    }
  });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 180);
    console.error("FOSCH migration R2 read-back failed with HTTP", response.status, detail);
    throw new Error("Cloudflare R2 read-back failed (HTTP " + response.status + ").");
  }
  return new Uint8Array(await response.arrayBuffer());
}
async function authorizeAdmin(request: Request): Promise<{token: string; publishableKey: string; supabaseUrl: string}> {
  const match = (request.headers.get("Authorization") || "").match(/^Bearer\s+(.+)$/i);
  if (!match) throw new Response("Administrator sign-in is required.", { status: 401 });
  const token = match[1].trim();
  const supabaseUrl = requiredEnv("SUPABASE_URL").replace(/\/+$/, "");
  const publishableKey = getPublishableKey();
  const userResponse = await fetch(supabaseUrl + "/auth/v1/user", {
    headers: { "apikey": publishableKey, "Authorization": "Bearer " + token }
  });
  if (!userResponse.ok) throw new Response("Your FOSCH session is invalid or expired. Please sign in again.", { status: 401 });
  const adminResponse = await fetch(supabaseUrl + "/rest/v1/rpc/is_fosch_admin", {
    method: "POST",
    headers: { "apikey": publishableKey, "Authorization": "Bearer " + token,
      "Content-Type": "application/json", "Accept": "application/json" },
    body: "{}"
  });
  if (!adminResponse.ok || await adminResponse.json() !== true) {
    throw new Response("Administrator access is required.", { status: 403 });
  }
  return { token, publishableKey, supabaseUrl };
}
async function savePublicBaseUrl(auth: {token: string; publishableKey: string; supabaseUrl: string}, publicBase: string): Promise<void> {
  const response = await fetch(auth.supabaseUrl + "/rest/v1/rpc/admin_set_fosch_r2_public_url", {
    method: "POST",
    headers: { "apikey": auth.publishableKey, "Authorization": "Bearer " + auth.token,
      "Content-Type": "application/json", "Accept": "application/json" },
    body: JSON.stringify({ p_public_url: publicBase })
  });
  if (!response.ok || await response.json() !== true) {
    throw new Error("The FOSCH R2 SQL migration must be applied before media migration.");
  }
}
async function listObjects(auth: {token: string; publishableKey: string; supabaseUrl: string}): Promise<{
  objects: Array<{path: string; size: number; contentType: string}>;
  skipped: Array<{path: string; size: number; contentType: string}>
}> {
  const objects: Array<{path: string; size: number; contentType: string}> = [];
  const skipped: Array<{path: string; size: number; contentType: string}> = [];
  const queue = [""];
  const visited = new Set<string>();
  while (queue.length) {
    const prefix = queue.shift()!;
    if (visited.has(prefix)) continue;
    visited.add(prefix);
    let offset = 0;
    while (true) {
      const response = await fetch(auth.supabaseUrl + "/storage/v1/object/list/" + BUCKET, {
        method: "POST",
        headers: { "apikey": auth.publishableKey, "Authorization": "Bearer " + auth.token,
          "Content-Type": "application/json" },
        body: JSON.stringify({ prefix, limit: 1000, offset, sortBy: { column: "name", order: "asc" } })
      });
      if (!response.ok) {
        console.error("FOSCH source storage listing failed with HTTP", response.status);
        throw new Error("Supabase could not list the old public media bucket (HTTP " + response.status + ").");
      }
      const entries = await response.json();
      if (!Array.isArray(entries)) throw new Error("Supabase returned an unexpected storage listing.");
      for (const entry of entries) {
        if (!entry || typeof entry.name !== "string" || !entry.name) continue;
        const path = prefix ? prefix + "/" + entry.name : entry.name;
        if (entry.id == null && entry.metadata == null) {
          queue.push(path);
          continue;
        }
        const metadata = entry.metadata && typeof entry.metadata === "object" ? entry.metadata : {};
        const contentType = String(metadata.mimetype || metadata.contentType || "");
        const size = Number(metadata.size || metadata.contentLength || 0);
        const looksLikeImage = contentType.startsWith("image/") || /\.(?:jpe?g|png|webp)$/i.test(path);
        if (!looksLikeImage) continue;
        const item = { path, size: Number.isFinite(size) ? size : 0, contentType };
        if (validMediaPath(path)) objects.push(item);
        else skipped.push(item);
      }
      if (entries.length < 1000) break;
      offset += entries.length;
    }
  }
  objects.sort((a, b) => a.path.localeCompare(b.path));
  skipped.sort((a, b) => a.path.localeCompare(b.path));
  return { objects, skipped };
}
async function migrateOne(
  auth: {token: string; publishableKey: string; supabaseUrl: string},
  publicBase: string,
  path: string
): Promise<{path: string; size: number; sha256: string}> {
  if (!validMediaPath(path)) throw new Error("The object path is not an approved FOSCH media path.");
  const sourceResponse = await fetch(SOURCE_BASE + path.split("/").map(encodeURIComponent).join("/"), {
    headers: { "Cache-Control": "no-cache" }
  });
  if (!sourceResponse.ok) throw new Error("Could not download the original Supabase image (HTTP " + sourceResponse.status + ").");
  const bytes = new Uint8Array(await sourceResponse.arrayBuffer());
  if (!bytes.length || bytes.length > MAX_OBJECT_BYTES) throw new Error("The source image is empty or exceeds the 5 MB storage limit.");
  const mime = expectedMime(path);
  const responseMime = (sourceResponse.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
  if (responseMime && responseMime !== mime && !(path.startsWith("brands/") && responseMime === "image/jpg")) {
    throw new Error("The source image MIME type does not match its file extension (" + responseMime + ").");
  }
  const originalHash = await sha256(bytes);
  await signedR2Put(path, bytes, mime);
  const copied = await signedR2Get(path);
  if (copied.length !== bytes.length || await sha256(copied) !== originalHash) {
    throw new Error("R2 copy failed SHA-256 verification: the uploaded object does not match the Supabase source.");
  }
  return { path, size: bytes.length, sha256: originalHash };
}
async function finalizePaths(
  auth: {token: string; publishableKey: string; supabaseUrl: string},
  paths: string[]
): Promise<unknown> {
  if (!Array.isArray(paths) || !paths.length || paths.length > 100 || paths.some((path) => !validMediaPath(path))) {
    throw new Error("Reference finalization accepts 1–100 verified FOSCH media paths per request.");
  }
  const response = await fetch(auth.supabaseUrl + "/rest/v1/rpc/admin_finalize_fosch_r2_media_migration", {
    method: "POST",
    headers: { "apikey": auth.publishableKey, "Authorization": "Bearer " + auth.token,
      "Content-Type": "application/json", "Accept": "application/json" },
    body: JSON.stringify({ p_object_paths: paths })
  });
  if (!response.ok) {
    console.error("FOSCH reference finalization failed with HTTP", response.status, (await response.text()).slice(0, 200));
    throw new Error("Supabase could not switch the database references to R2. Run the new migration SQL first.");
  }
  return await response.json();
}

Deno.serve(async (request: Request): Promise<Response> => {
  if (request.method === "OPTIONS") return new Response("ok", { status: 200, headers: corsHeaders });
  if (request.method !== "POST") return jsonResponse(405, { error: "Method not allowed." });
  try {
    const auth = await authorizeAdmin(request);
    const body = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body)) return jsonResponse(400, { error: "A JSON request body is required." });
    const publicBase = normalizePublicBase(requiredEnv("R2_PUBLIC_URL"));
    await savePublicBaseUrl(auth, publicBase);
    const action = String(body.action || "");
    if (action === "inventory") {
      const inventory = await listObjects(auth);
      return jsonResponse(200, { ok: true, ...inventory, total: inventory.objects.length, skippedCount: inventory.skipped.length });
    }
    if (action === "migrate") {
      const paths = body.paths;
      if (!Array.isArray(paths) || paths.length < 1 || paths.length > 3) {
        return jsonResponse(400, { error: "A migration batch must contain 1–3 image paths." });
      }
      const migrated: Array<{path: string; size: number; sha256: string}> = [];
      const failed: Array<{path: string; error: string}> = [];
      for (const path of paths) {
        try { migrated.push(await migrateOne(auth, publicBase, String(path))); }
        catch (error) {
          failed.push({ path: String(path), error: error instanceof Error ? error.message : "Migration failed." });
        }
      }
      return jsonResponse(200, { ok: failed.length === 0, migrated, failed });
    }
    if (action === "finalize") {
      const paths = body.paths;
      const result = await finalizePaths(auth, paths);
      return jsonResponse(200, { ok: true, result });
    }
    return jsonResponse(400, { error: "Unsupported migration action." });
  } catch (error) {
    if (error instanceof Response) return jsonResponse(error.status, { error: await error.text() });
    console.error("FOSCH R2 migration error:", error);
    return jsonResponse(500, { error: error instanceof Error ? error.message : "Migration failed." });
  }
});
