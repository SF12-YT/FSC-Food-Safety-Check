const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin"
};

function jsonResponse(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...corsHeaders,
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store"
    }
  });
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error("Server configuration is missing " + name + ".");
  return value;
}

function publishableKey(): string {
  const value = Deno.env.get("SUPABASE_ANON_KEY") || Deno.env.get("SUPABASE_PUBLISHABLE_KEY");
  if (!value?.trim()) throw new Error("Supabase publishable key is unavailable.");
  return value.trim();
}

async function authorizeAdmin(request: Request): Promise<{ supabaseUrl: string; publishableKey: string; accessToken: string; serviceKey: string }> {
  const match = (request.headers.get("Authorization") || "").match(/^Bearer\\s+(.+)$/i);
  if (!match) throw new Response("Administrator sign-in is required.", { status: 401 });

  const accessToken = match[1].trim();
  const supabaseUrl = requiredEnv("SUPABASE_URL").replace(/\\/+$/, "");
  const key = publishableKey();

  const userResponse = await fetch(supabaseUrl + "/auth/v1/user", {
    headers: { "apikey": key, "Authorization": "Bearer " + accessToken }
  });
  if (!userResponse.ok) throw new Response("Your FOSCH session is invalid or expired. Please sign in again.", { status: 401 });

  const adminResponse = await fetch(supabaseUrl + "/rest/v1/rpc/is_fosch_admin", {
    method: "POST",
    headers: {
      "apikey": key,
      "Authorization": "Bearer " + accessToken,
      "Content-Type": "application/json",
      "Accept": "application/json"
    },
    body: "{}"
  });
  if (!adminResponse.ok) throw new Response("FOSCH could not verify administrator permissions.", { status: 403 });
  if (await adminResponse.json() !== true) throw new Response("Administrator access is required.", { status: 403 });

  return {
    supabaseUrl,
    publishableKey: key,
    accessToken,
    serviceKey: requiredEnv("SUPABASE_SERVICE_ROLE_KEY")
  };
}

function validatePhotoPaths(value: unknown, submissionId: string): string[] {
  if (!Array.isArray(value)) throw new Error("The submission photo-path list is invalid.");
  const expected = new RegExp("^submissions/" + submissionId +
    "/[1-5]-[0-9a-f]{16}\\.(?:jpeg|png|webp|svg)$", "i");
  const unique = new Set<string>();
  for (const path of value) {
    if (typeof path !== "string" || !expected.test(path)) {
      throw new Error("A photo path does not belong to this submission.");
    }
    if (unique.has(path)) throw new Error("The submission contains duplicate photo paths.");
    unique.add(path);
  }
  if (unique.size > 5) throw new Error("A submission cannot contain more than five photos.");
  return Array.from(unique);
}

function cleanPayload(value: unknown): Record<string, unknown> {
  const payload = value && typeof value === "object" && !Array.isArray(value)
    ? { ...(value as Record<string, unknown>) }
    : {};
  payload.photos = [];
  payload.photoPaths = [];
  payload.cloudPhotoPaths = [];
  payload.photo = "";
  return payload;
}

async function serviceRequest(
  auth: { supabaseUrl: string; serviceKey: string },
  path: string,
  init: RequestInit
): Promise<Response> {
  return fetch(auth.supabaseUrl + path, {
    ...init,
    headers: {
      "apikey": auth.serviceKey,
      "Authorization": "Bearer " + auth.serviceKey,
      "Content-Type": "application/json",
      "Accept": "application/json",
      ...(init.headers || {})
    }
  });
}

Deno.serve(async (request: Request): Promise<Response> => {
  if (request.method === "OPTIONS") return new Response("ok", { status: 200, headers: corsHeaders });
  if (request.method !== "POST") return jsonResponse(405, { error: "Method not allowed." });

  try {
    const auth = await authorizeAdmin(request);
    const body = await request.json();
    const submissionId = String(body && body.submission_id || "").toLowerCase();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(submissionId)) {
      return jsonResponse(400, { error: "A valid submission UUID is required." });
    }

    const query = "/rest/v1/product_submissions?id=eq." + encodeURIComponent(submissionId) +
      "&select=id,status,photo_paths,payload&limit=1";
    const readResponse = await serviceRequest(auth, query, { method: "GET" });
    if (!readResponse.ok) {
      console.error("FOSCH could not read the approved submission for photo cleanup:", readResponse.status);
      return jsonResponse(502, { error: "The submission could not be loaded for photo cleanup." });
    }
    const rows = await readResponse.json();
    const submission = Array.isArray(rows) ? rows[0] : null;
    if (!submission) return jsonResponse(404, { error: "The submission was not found." });
    if (String(submission.status || "").toLowerCase() !== "approved") {
      return jsonResponse(409, { error: "Private submission photos can only be deleted after approval." });
    }

    const photoPaths = validatePhotoPaths(submission.photo_paths || [], submissionId);
    if (photoPaths.length) {
      const deleteResponse = await serviceRequest(auth, "/storage/v1/object/fosch-submission-photos", {
        method: "DELETE",
        body: JSON.stringify({ prefixes: photoPaths })
      });
      if (!deleteResponse.ok) {
        const detail = (await deleteResponse.text()).slice(0, 200);
        console.error("FOSCH private photo deletion failed:", deleteResponse.status, detail);
        return jsonResponse(502, { error: "The private evidence photos could not be deleted from Storage." });
      }
    }

    const updateResponse = await serviceRequest(auth,
      "/rest/v1/product_submissions?id=eq." + encodeURIComponent(submissionId) + "&status=eq.approved",
      {
        method: "PATCH",
        headers: { "Prefer": "return=minimal" },
        body: JSON.stringify({
          photo_paths: [],
          payload: cleanPayload(submission.payload)
        })
      }
    );
    if (!updateResponse.ok) {
      const detail = (await updateResponse.text()).slice(0, 200);
      console.error("FOSCH private photo references could not be cleared:", updateResponse.status, detail);
      return jsonResponse(502, { error: "The photo files were deleted, but their database references could not be cleared. Retry cleanup." });
    }

    // Record cleanup using the existing admin-only audit RPC; a logging failure does not undo deletion.
    try {
      await fetch(auth.supabaseUrl + "/rest/v1/rpc/admin_log_event", {
        method: "POST",
        headers: {
          "apikey": auth.publishableKey,
          "Authorization": "Bearer " + auth.accessToken,
          "Content-Type": "application/json",
          "Accept": "application/json"
        },
        body: JSON.stringify({
          p_action: "submission_private_photos_purged",
          p_details: { submission_id: submissionId, deleted_photo_count: photoPaths.length }
        })
      });
    } catch (auditError) {
      console.warn("FOSCH photo cleanup audit event could not be recorded:", auditError);
    }

    return jsonResponse(200, { ok: true, deleted_photo_count: photoPaths.length });
  } catch (error) {
    if (error instanceof Response) return jsonResponse(error.status, { error: await error.text() });
    console.error("FOSCH submission photo cleanup failed:", error);
    return jsonResponse(500, { error: error instanceof Error ? error.message : "Photo cleanup failed." });
  }
});
