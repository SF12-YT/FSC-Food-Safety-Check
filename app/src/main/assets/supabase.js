/* FOSCH Supabase connection: the publishable key is public by design.
 * Privileged access is enforced by Postgres RLS and admin RPCs, never by this key.
 */
(function () {
  "use strict";

  const SUPABASE_URL = "https://mqgvagftvuybietazbdc.supabase.co";
  const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_eQN_daectBMn-nbjDEcWsg_mZDoO-95";
  const SESSION_KEY = "fosch-supabase-session";
  const BUCKET = "fosch-submission-photos";
  let session = null;
  let requestCounter = 0;
  const pending = Object.create(null);

  try {
    const cached = JSON.parse(localStorage.getItem(SESSION_KEY) || "null");
    if (cached && cached.access_token && cached.refresh_token) session = cached;
  } catch (ignored) {}

  function saveSession(value) {
    session = value || null;
    try {
      if (session) localStorage.setItem(SESSION_KEY, JSON.stringify(session));
      else localStorage.removeItem(SESSION_KEY);
    } catch (ignored) {}
  }

  function uuid() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") return window.crypto.randomUUID();
    const bytes = new Uint8Array(16);
    if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(bytes);
    else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, function (b) { return b.toString(16).padStart(2, "0"); }).join("");
    return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" + hex.slice(16, 20) + "-" + hex.slice(20);
  }

  function createStatusToken() {
    const bytes = new Uint8Array(32);
    if (window.crypto && typeof window.crypto.getRandomValues === "function") {
      window.crypto.getRandomValues(bytes);
    } else {
      const fallback = (uuid() + uuid()).replace(/-/g, "");
      for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(fallback.slice(i * 2, i * 2 + 2), 16) || 0;
    }
    return Array.from(bytes, function (value) { return value.toString(16).padStart(2, "0"); }).join("");
  }

  function decodeJwt(token) {
    try {
      const part = String(token || "").split(".")[1];
      if (!part) return {};
      const base64 = part.replace(/-/g, "+").replace(/_/g, "/");
      return JSON.parse(atob(base64 + "===".slice((base64.length + 3) % 4)));
    } catch (ignored) { return {}; }
  }

  function isAdmin() {
    if (!session || !session.access_token) return false;
    const claims = decodeJwt(session.access_token);
    const appMeta = (session.user && session.user.app_metadata) || claims.app_metadata || {};
    return appMeta.role === "admin";
  }

  function nativeRequest(method, path, headers, body, base64Body) {
    return new Promise(function (resolve, reject) {
      if (!window.FOSCHBridge || typeof window.FOSCHBridge.supabaseRequest !== "function") {
        reject(new Error("Supabase networking is available in the installed FOSCH Android app."));
        return;
      }
      const id = "sb-" + (++requestCounter) + "-" + Date.now().toString(36);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        window.FOSCHBridge.supabaseRequest(id, method, path, JSON.stringify(headers || {}), body == null ? "" : String(body), !!base64Body);
      } catch (error) {
        delete pending[id];
        reject(error);
      }
    });
  }

  window.onFOSCHSupabaseResponse = function (requestId, statusCode, responseBody) {
    const entry = pending[String(requestId || "")];
    if (!entry) return;
    delete pending[String(requestId || "")];
    entry.resolve({ status: Number(statusCode || 0), body: String(responseBody || "") });
  };

  function parseResponse(response) {
    if (!response.body) return null;
    try { return JSON.parse(response.body); }
    catch (ignored) { return { message: response.body }; }
  }

  async function rawRequest(method, path, body, options) {
    const opts = options || {};
    const headers = {
      apikey: SUPABASE_PUBLISHABLE_KEY,
      Accept: "application/json"
    };
    if (opts.accessToken) headers.Authorization = "Bearer " + opts.accessToken;
    else if (opts.usePublicKeyBearer !== false) headers.Authorization = "Bearer " + SUPABASE_PUBLISHABLE_KEY;
    if (opts.contentType) headers["Content-Type"] = opts.contentType;
    else if (body !== undefined && body !== null && !opts.base64Body) headers["Content-Type"] = "application/json";
    if (opts.prefer) headers.Prefer = opts.prefer;
    if (opts.upsert !== undefined) headers["x-upsert"] = opts.upsert ? "true" : "false";

    const response = await nativeRequest(method, path, headers,
      body === undefined || body === null ? "" : body,
      !!opts.base64Body);
    const parsed = parseResponse(response);
    if (response.status < 200 || response.status >= 300) {
      const message = parsed && (parsed.msg || parsed.message || parsed.error_description || parsed.error) ||
        ("Supabase returned HTTP " + response.status);
      const error = new Error(String(message));
      error.status = response.status;
      error.details = parsed;
      throw error;
    }
    return parsed;
  }

  async function refreshSession() {
    if (!session || !session.refresh_token) return null;
    const refreshed = await rawRequest("POST", "/auth/v1/token?grant_type=refresh_token",
      JSON.stringify({ refresh_token: session.refresh_token }), { usePublicKeyBearer: true });
    if (refreshed && refreshed.access_token) {
      if (!refreshed.expires_at && refreshed.expires_in) refreshed.expires_at = Math.floor(Date.now() / 1000) + refreshed.expires_in;
      saveSession(refreshed);
      return refreshed;
    }
    saveSession(null);
    return null;
  }

  async function api(method, path, body, options) {
    const opts = options || {};
    if (!opts.publicOnly && session && session.access_token) {
      const expiry = Number(session.expires_at || 0);
      if (expiry && expiry < Math.floor(Date.now() / 1000) + 45) {
        try { await refreshSession(); } catch (ignored) { saveSession(null); }
      }
    }
    const token = !opts.publicOnly && session && session.access_token ? session.access_token : null;
    try {
      return await rawRequest(method, path, body, Object.assign({}, opts, {
        accessToken: token,
        usePublicKeyBearer: !token
      }));
    } catch (error) {
      if (!opts.publicOnly && error.status === 401 && session && session.refresh_token && !opts.didRetry) {
        await refreshSession();
        return api(method, path, body, Object.assign({}, opts, { didRetry: true }));
      }
      throw error;
    }
  }

  function persistLocalRecord(record, storageKey) {
    try {
      const list = JSON.parse(localStorage.getItem(storageKey) || "[]");
      if (!Array.isArray(list)) return;
      const index = list.findIndex(function (entry) { return String(entry.id || "") === String(record.id || ""); });
      if (index >= 0) list[index] = record;
      localStorage.setItem(storageKey, JSON.stringify(list));
    } catch (ignored) {}
  }

  function dataUrlParts(value) {
    const match = String(value || "").match(/^data:image\/(jpeg|jpg|png|webp);base64,([A-Za-z0-9+/=]+)$/i);
    if (!match) throw new Error("A product photo is not a supported JPEG, PNG or WebP image.");
    const ext = match[1].toLowerCase() === "jpg" ? "jpeg" : match[1].toLowerCase();
    const mime = ext === "jpeg" ? "image/jpeg" : "image/" + ext;
    return { mime: mime, extension: ext, base64: match[2] };
  }

  async function uploadSubmissionPhotos(submissionId, photos) {
    const paths = [];
    const source = Array.isArray(photos) ? photos : [];
    for (let i = 0; i < source.length && i < 5; i++) {
      const image = String(source[i] || "");
      if (!image.startsWith("data:image/")) continue;
      const parsed = dataUrlParts(image);
      const path = "submissions/" + submissionId + "/" + (i + 1) + "." + parsed.extension;
      try {
        await api("POST", "/storage/v1/object/" + BUCKET + "/" + path, parsed.base64, {
          contentType: parsed.mime,
          base64Body: true,
          upsert: false
        });
      } catch (error) {
        // Retrying an already-uploaded immutable file is safe; Storage returns 409.
        if (error.status !== 409) throw error;
      }
      paths.push(path);
    }
    return paths;
  }

  async function queueSubmission(record, kind) {
    if (!record || !record.id) return { ok: false, queued: true, message: "The local record has no ID." };
    if (record.cloudSyncStatus === "synced" && record.cloudSubmissionId) return { ok: true, id: record.cloudSubmissionId };
    const storageKey = kind === "product" ? "fosch-contrib" : "fosch-corrections";
    const submissionType = kind === "product" ? "product" :
      (kind === "product_correction" ? "product_correction" : "correction");
    const submissionId = record.cloudSubmissionId || uuid();
    record.cloudSubmissionId = submissionId;
    record.cloudStatusToken = record.cloudStatusToken || createStatusToken();
    record.cloudReviewStatus = record.cloudReviewStatus || "pending";
    record.cloudSyncStatus = "syncing";
    record.cloudSyncError = "";
    persistLocalRecord(record, storageKey);
    try {
      const photos = Array.isArray(record.photos) ? record.photos : (record.photo ? [record.photo] : []);
      const photoPaths = await uploadSubmissionPhotos(submissionId, photos);
      const payload = JSON.parse(JSON.stringify(record));
      delete payload.photo;
      delete payload.cloudStatusToken;
      payload.photos = photoPaths;
      payload.photoPaths = photoPaths;
      const product = payload.suggestedProduct || payload;
      const body = {
        id: submissionId,
        client_record_id: String(record.id),
        submission_type: submissionType,
        brand: product.brand || record.brand || null,
        product_name: product.name || record.name || record.target || null,
        barcode: product.barcode || record.barcode || null,
        package_size: product.sizeLabel || product.size || record.sizeLabel || null,
        country_code: String(product.country || record.country || "").toUpperCase().slice(0, 2) || null,
        ingredients: product.ingredients || record.ingredients || null,
        target: record.target || null,
        message: record.message || null,
        source: record.source || null,
        payload: payload,
        photo_paths: photoPaths,
        status_token: record.cloudStatusToken,
        status: "pending",
        submitted_by: null,
        submitted_at: record.date || new Date().toISOString()
      };
      await api("POST",
        "/rest/v1/product_submissions?on_conflict=submission_type%2Cclient_record_id",
        JSON.stringify(body),
        { prefer: "resolution=ignore-duplicates,return=minimal" });
      record.cloudSyncStatus = "synced";
      record.cloudSyncError = "";
      persistLocalRecord(record, storageKey);
      return { ok: true, id: submissionId };
    } catch (error) {
      record.cloudSyncStatus = "queued";
      record.cloudSyncError = String(error && error.message || error).slice(0, 240);
      persistLocalRecord(record, storageKey);
      return { ok: false, queued: true, message: record.cloudSyncError };
    }
  }

  async function syncPending() {
    const jobs = [];
    [["fosch-contrib", "product"], ["fosch-corrections", "correction"]].forEach(function (spec) {
      try {
        const rows = JSON.parse(localStorage.getItem(spec[0]) || "[]");
        if (!Array.isArray(rows)) return;
        rows.forEach(function (record) {
          if (record && record.cloudSyncStatus !== "synced") {
            const type = record.kind === "product-correction" ? "product_correction" : spec[1];
            jobs.push(queueSubmission(record, type));
          }
        });
      } catch (ignored) {}
    });
    return Promise.all(jobs);
  }

  async function signIn(email, password) {
    const data = await rawRequest("POST", "/auth/v1/token?grant_type=password",
      JSON.stringify({ email: String(email || "").trim(), password: String(password || "") }),
      { usePublicKeyBearer: true });
    if (!data || !data.access_token || !data.refresh_token) throw new Error("Supabase did not return an authenticated session.");
    if (!data.expires_at && data.expires_in) data.expires_at = Math.floor(Date.now() / 1000) + data.expires_in;
    saveSession(data);
    if (!isAdmin()) {
      try { await rawRequest("POST", "/auth/v1/logout", "", { accessToken: session.access_token }); } catch (ignored) {}
      saveSession(null);
      throw new Error("This account is not assigned the FOSCH administrator role. Ask the project owner to grant it in Supabase.");
    }
    return data.user || null;
  }

  async function signOut() {
    try {
      if (session && session.access_token) await api("POST", "/auth/v1/logout", "");
    } finally {
      saveSession(null);
    }
  }

  async function listSubmissions() {
    const rows = await api("GET", "/rest/v1/product_submissions?select=*&order=submitted_at.desc&limit=500");
    return Array.isArray(rows) ? rows : [];
  }

  async function syncSubmissionStatuses() {
    const storageKey = "fosch-contrib";
    let records;
    try {
      records = JSON.parse(localStorage.getItem(storageKey) || "[]");
    } catch (ignored) { return 0; }
    if (!Array.isArray(records)) return 0;
    // cloudSubmissionId is also a high-entropy UUID. It permits status retrieval
    // for rows created before receipt-token support, but only when the database
    // has no token hash. Newer rows still require the private 256-bit receipt.
    const eligible = records.filter(function (record) {
      return record && record.id && record.cloudSubmissionId && record.cloudSyncStatus === "synced";
    });
    if (!eligible.length) return 0;
    let changed = 0;
    for (let offset = 0; offset < eligible.length; offset += 60) {
      const batch = eligible.slice(offset, offset + 60);
      const lookups = batch.map(function (record) {
        const lookup = {
          client_record_id: String(record.id),
          submission_type: "product",
          submission_id: String(record.cloudSubmissionId)
        };
        if (/^[0-9a-fA-F]{64}$/.test(String(record.cloudStatusToken || ""))) {
          lookup.status_token = String(record.cloudStatusToken);
        }
        return lookup;
      });
      const rows = await api("POST", "/rest/v1/rpc/get_fosch_submission_statuses",
        JSON.stringify({ p_lookups: lookups }));
      if (!Array.isArray(rows)) continue;
      rows.forEach(function (row) {
        const record = batch.find(function (entry) { return String(entry.id) === String(row.client_record_id); });
        if (!record || !["pending", "approved", "rejected"].includes(String(row.status || ""))) return;
        const reason = String(row.rejection_reason || "");
        const reviewedAt = String(row.reviewed_at || row.updated_at || "");
        if (record.cloudReviewStatus !== row.status ||
            String(record.cloudReviewRejectionReason || "") !== reason ||
            String(record.cloudReviewAt || "") !== reviewedAt) {
          record.cloudReviewStatus = row.status;
          record.cloudReviewRejectionReason = reason;
          record.cloudReviewAt = reviewedAt;
          persistLocalRecord(record, storageKey);
          changed++;
        }
      });
    }
    return changed;
  }
  async function listAuditLog() {
    const rows = await api("GET", "/rest/v1/admin_audit_log?select=*&order=created_at.desc&limit=500");
    return Array.isArray(rows) ? rows : [];
  }

  async function adminUpdateSubmission(id, changes, reason) {
    return api("POST", "/rest/v1/rpc/admin_update_product_submission", JSON.stringify({
      p_submission_id: id,
      p_changes: changes || {},
      p_reason: String(reason || "")
    }));
  }

  async function adminDeleteSubmission(id, reason) {
    return api("POST", "/rest/v1/rpc/admin_delete_product_submission", JSON.stringify({
      p_submission_id: id,
      p_reason: String(reason || "")
    }));
  }

  async function logAdminEvent(action, details) {
    return api("POST", "/rest/v1/rpc/admin_log_event", JSON.stringify({
      p_action: String(action || ""),
      p_details: details || {}
    }));
  }

  window.FOSCHCloud = {
    isConfigured: function () { return true; },
    isAdmin: isAdmin,
    signIn: signIn,
    signOut: signOut,
    queueSubmission: queueSubmission,
    syncPending: syncPending,
    syncSubmissionStatuses: syncSubmissionStatuses,
    listSubmissions: listSubmissions,
    listAuditLog: listAuditLog,
    adminUpdateSubmission: adminUpdateSubmission,
    adminDeleteSubmission: adminDeleteSubmission,
    logAdminEvent: logAdminEvent
  };

  if (window.foschInternetOnline === true) syncPending();
  if (window.foschAppForegroundSyncPending || !document.hidden) {
    window.foschAppForegroundSyncPending = false;
    if (typeof window.onFOSCHAppForeground === "function") window.onFOSCHAppForeground();
  }
})();