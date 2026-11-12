const encoder = new TextEncoder();
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
    } catch (_) {
      throw new Error("Supabase publishable-key configuration is invalid.");
    }
  }
  if (legacy?.trim()) return legacy.trim();
  throw new Error("Supabase publishable key is unavailable in the Edge Function environment.");
}

function normalizePublicBase(value: string): string {
  let url: URL;
  try { url = new URL(value); } catch (_) { throw new Error("R2 public URL is invalid."); }
  if (url.protocol !== "https:" || url.username || url.password ||
      url.pathname !== "/" || url.search || url.hash) {
    throw new Error("R2 public URL must be an HTTPS origin without a path.");
  }
  return url.origin;
}

function validateObjectPath(path: unknown, action: string): string {
  if (typeof path !== "string" || path.length > 240 || path.startsWith("/") ||
      path.includes("\\") || path.includes("%") ||
      path.split("/").some((part) => !part || part === "." || part === "..")) {
    throw new Error("Invalid media object path.");
  }
  const product = /^products\/(?:[A-Z]{2}|INT)\/[A-Za-z0-9_-]{1,80}\/[0-9a-f]{16}\.(?:jpeg|png|webp)$/.test(path);
  const brand = /^brands\/[a-z0-9]+(?:-[a-z0-9]+)*\.jpeg$/.test(path);
  if (action === "upload" && (product || brand)) return path;
  if (action === "delete" && product) return path;
  throw new Error(action === "delete"
    ? "Only official product photos can be deleted through this endpoint."
    : "Only FOSCH product photos and brand logos can be uploaded.");
}

function bytesFromBase64(value: unknown): Uint8Array {
  if (typeof value !== "string" || !value || value.length > Math.ceil(MAX_OBJECT_BYTES * 4 / 3) + 8 ||
      !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    throw new Error("The image data is missing or invalid.");
  }
  let decoded: string;
  try { decoded = atob(value); } catch (_) { throw new Error("The image data is not valid base64."); }
  if (!decoded.length || decoded.length > MAX_OBJECT_BYTES) throw new Error("Images must be 5 MB or smaller.");
  const bytes = new Uint8Array(decoded.length);
  for (let i = 0; i < decoded.length; i++) bytes[i] = decoded.charCodeAt(i);
  return bytes;
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

async function sha256(bytes: Uint8Array): Promise<string> {
  return toHex(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)));
}

async function hmac(key: Uint8Array, message: string): Promise<Uint8Array> {
  const imported = await crypto.subtle.importKey(
    "raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", imported, encoder.encode(message)));
}

function awsEncode(value: string): string {
  return encodeURIComponent(value).replace(/[!'()*]/g, (character) =>
    "%" + character.charCodeAt(0).toString(16).toUpperCase()
  );
}

async function signedR2Request(method: "PUT" | "DELETE", path: string, body: Uint8Array): Promise<void> {
  const accountId = requiredEnv("CLOUDFLARE_ACCOUNT_ID");
  const accessKeyId = requiredEnv("R2_ACCESS_KEY_ID");
  const secretAccessKey = requiredEnv("R2_SECRET_ACCESS_KEY");
  const bucket = requiredEnv("R2_BUCKET_NAME");
  if (!/^[a-f0-9]{32}$/i.test(accountId)) throw new Error("Cloudflare account ID is invalid.");
  if (!/^[a-z0-9-]{3,63}$/.test(bucket)) throw new Error("R2 bucket name is invalid.");

  const canonicalUri = "/" + awsEncode(bucket) + "/" + path.split("/").map(awsEncode).join("/");
  const requestUrl = "https://" + accountId + ".r2.cloudflarestorage.com" + canonicalUri;
  const host = new URL(requestUrl).host;
  const now = new Date();
  const amzDate = now.toISOString().replace(/[:-]|\.\d{3}/g, "");
  const dateStamp = amzDate.slice(0, 8);
  const payloadHash = await sha256(body);
  const signedHeaders = "host;x-amz-content-sha256;x-amz-date";
  const canonicalHeaders =
    "host:" + host + "\n" +
    "x-amz-content-sha256:" + payloadHash + "\n" +
    "x-amz-date:" + amzDate + "\n";
  const canonicalRequest = [
    method, canonicalUri, "", canonicalHeaders, signedHeaders, payloadHash
  ].join("\n");
  const region = "auto";
  const scope = dateStamp + "/" + region + "/s3/aws4_request";
  const stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" +
    await sha256(encoder.encode(canonicalRequest));
  const kDate = await hmac(encoder.encode("AWS4" + secretAccessKey), dateStamp);
  const kRegion = await hmac(kDate, region);
  const kService = await hmac(kRegion, "s3");
  const kSigning = await hmac(kService, "aws4_request");
  const signature = toHex(await hmac(kSigning, stringToSign));
  const headers: Record<string, string> = {
    "Authorization": "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope +
      ", SignedHeaders=" + signedHeaders + ", Signature=" + signature,
    "x-amz-content-sha256": payloadHash,
    "x-amz-date": amzDate
  };
  if (method === "PUT") {
    headers["Content-Type"] = path.endsWith(".png") ? "image/png" :
      (path.endsWith(".webp") ? "image/webp" : "image/jpeg");
    headers["Cache-Control"] = path.startsWith("brands/") ? "public, max-age=3600" :
      "public, max-age=31536000, immutable";
  }

  const response = await fetch(requestUrl, {
    method,
    headers,
    body: method === "PUT" ? body : undefined
  });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 240);
    console.error("FOSCH R2 request failed with HTTP", response.status, detail);
    throw new Error("Cloudflare R2 rejected the media operation (HTTP " + response.status + ").");
  }
}

async function authorizeAdmin(request: Request): Promise<{ token: string; publishableKey: string; supabaseUrl: string }> {
  const authorization = request.headers.get("Authorization") || "";
  const match = authorization.match(/^Bearer\s+(.+)$/i);
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
    headers: {
      "apikey": publishableKey,
      "Authorization": "Bearer " + token,
      "Content-Type": "application/json",
      "Accept": "application/json"
    },
    body: "{}"
  });
  if (!adminResponse.ok) {
    console.error("FOSCH admin authorization RPC failed with HTTP", adminResponse.status);
    throw new Response("FOSCH could not verify administrator permissions.", { status: 403 });
  }
  const isAdmin = await adminResponse.json();
  if (isAdmin !== true) throw new Response("Administrator access is required.", { status: 403 });
  return { token, publishableKey, supabaseUrl };
}

async function savePublicBaseUrl(
  auth: { token: string; publishableKey: string; supabaseUrl: string },
  publicBase: string
): Promise<void> {
  const response = await fetch(auth.supabaseUrl + "/rest/v1/rpc/admin_set_fosch_r2_public_url", {
    method: "POST",
    headers: {
      "apikey": auth.publishableKey,
      "Authorization": "Bearer " + auth.token,
      "Content-Type": "application/json",
      "Accept": "application/json"
    },
    body: JSON.stringify({ p_public_url: publicBase })
  });
  if (!response.ok) {
    console.error("FOSCH R2 public URL configuration RPC failed with HTTP", response.status);
    throw new Error("The FOSCH R2 media SQL migration must be applied before publishing images.");
  }
  const result = await response.json();
  if (result !== true) throw new Error("FOSCH could not save the R2 public URL configuration.");
}

Deno.serve(async (request: Request): Promise<Response> => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { status: 200, headers: corsHeaders });
  }
  if (request.method !== "POST") return jsonResponse(405, { error: "Method not allowed." });

  try {
    const auth = await authorizeAdmin(request);
    const body = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body)) {
      return jsonResponse(400, { error: "A JSON request body is required." });
    }
    const action = String(body.action || "");
    if (action !== "upload" && action !== "delete") {
      return jsonResponse(400, { error: "Unsupported media action." });
    }
    const path = validateObjectPath(body.path, action);
    const publicBase = normalizePublicBase(requiredEnv("R2_PUBLIC_URL"));
    await savePublicBaseUrl(auth, publicBase);

    if (action === "upload") {
      const contentType = String(body.contentType || "");
      const expectedType = path.endsWith(".png") ? "image/png" :
        (path.endsWith(".webp") ? "image/webp" : "image/jpeg");
      if (contentType !== expectedType) return jsonResponse(400, { error: "The image type does not match its file extension." });
      const bytes = bytesFromBase64(body.base64);
      await signedR2Request("PUT", path, bytes);
      const publicUrl = publicBase + "/" + path.split("/").map(awsEncode).join("/");
      return jsonResponse(200, { ok: true, publicUrl });
    }

    await signedR2Request("DELETE", path, new Uint8Array());
    return jsonResponse(200, { ok: true });
  } catch (error) {
    if (error instanceof Response) {
      return jsonResponse(error.status, { error: await error.text() });
    }
    console.error("FOSCH R2 media function error:", error);
    return jsonResponse(500, { error: error instanceof Error ? error.message : "Media operation failed." });
  }
});
