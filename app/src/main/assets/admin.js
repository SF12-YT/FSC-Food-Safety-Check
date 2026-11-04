(function () {
  "use strict";

  let DEFAULT_EMAIL = "Administrator";
  const AUDIT_KEY = "fosch-admin-audit";
  const LOGO_KEY = "fosch-brand-logos";
  let authenticated = false;
  let activeTab = "submissions";
  let editingRef = null;
  let pendingMedia = null;
  let auditLog = [];
  let remoteAuditLog = [];
  const NUTRITION_FIELDS = [
    {key:"energy",label:"Energy"},{key:"protein",label:"Protein"},{key:"gluten",label:"Gluten"},
    {key:"fatTotal",label:"Fat total"},{key:"saturated",label:"Saturated"},{key:"trans",label:"Trans"},
    {key:"polyunsaturated",label:"Polyunsaturated"},{key:"monounsaturated",label:"Monounsaturated"},
    {key:"carbohydrate",label:"Carbohydrate"},{key:"sugars",label:"Sugar"},{key:"lactose",label:"Lactose"},
    {key:"galactose",label:"Galactose"},{key:"dietaryFibre",label:"Dietary fibre"},
    {key:"cholesterol",label:"Cholesterol"},{key:"sodium",label:"Sodium"},{key:"calcium",label:"Calcium"},{key:"potassium",label:"Potassium"},
    {key:"liveCultures",label:"Live cultures",noDI:true}
  ];
  function nutritionProduct(record){return record&&record.suggestedProduct&&typeof record.suggestedProduct==="object"?record.suggestedProduct:(record||{});}
  function normaliseAdminNutrition(value){
    const n=value&&typeof value==="object"&&!Array.isArray(value)?value:{};
    return {servings:String(n.servings==null?"":n.servings),servingSize:String(n.servingSize==null?"":n.servingSize),
      perServing:n.perServing&&typeof n.perServing==="object"?n.perServing:{},
      dailyIntakePerServing:n.dailyIntakePerServing&&typeof n.dailyIntakePerServing==="object"?n.dailyIntakePerServing:{},
      per100g:n.per100g&&typeof n.per100g==="object"?n.per100g:{}};
  }
  function nutritionMarkup(record){
    const product=nutritionProduct(record);
    const n=normaliseAdminNutrition(product.nutritionInfo||product.nutrition||(record&&(record.nutritionInfo||record.nutrition)));
    const populated=NUTRITION_FIELDS.filter(f=>String(n.perServing[f.key]||"").trim()||String(n.dailyIntakePerServing[f.key]||"").trim()||String(n.per100g[f.key]||"").trim());
    if(!n.servings&&!n.servingSize&&!populated.length)return "";
    const rows=populated.map(f=>"<div class=\"admin-nutrition-row\"><b>"+esc(f.label)+"</b><span>"+esc(n.perServing[f.key]||"—")+"</span><span>"+(f.noDI?"Not applicable":esc(n.dailyIntakePerServing[f.key]||"—"))+"</span><span>"+esc(n.per100g[f.key]||"—")+"</span></div>").join("");
    return "<details class=\"admin-nutrition\"><summary>Nutrition information ("+populated.length+" nutrients)</summary>"+
      (n.servings?"<small>Servings: "+esc(n.servings)+"</small>":"")+(n.servingSize?"<small>Serving size: "+esc(n.servingSize)+"</small>":"")+
      (rows?"<div class=\"admin-nutrition-grid\"><b>Nutrient</b><b>Per serving</b><b>% DI</b><b>Per 100 g</b>"+rows+"</div>":"")+"</details>";
  }
  function nutritionEditorMarkup(product){
    const n=normaliseAdminNutrition(product&&(product.nutritionInfo||product.nutrition));
    const rows=NUTRITION_FIELDS.map(f=>"<div class=\"admin-nutrition-edit-row\"><b>"+esc(f.label)+"</b>"+
      "<input class=\"field\" id=\"adminNutritionServing_"+f.key+"\" aria-label=\""+esc(f.label)+" per serving\" value=\""+esc(n.perServing[f.key]||"")+"\" placeholder=\"Per serving\">"+
      "<input class=\"field\" id=\"adminNutritionDI_"+f.key+"\" aria-label=\""+esc(f.label)+" daily intake\" value=\""+esc(n.dailyIntakePerServing[f.key]||"")+"\" placeholder=\""+(f.noDI?"Not applicable":"% DI")+"\" "+(f.noDI?"disabled":"")+">"+
      "<input class=\"field\" id=\"adminNutrition100g_"+f.key+"\" aria-label=\""+esc(f.label)+" per 100 g\" value=\""+esc(n.per100g[f.key]||"")+"\" placeholder=\"Per 100 g\"></div>").join("");
    return "<details class=\"admin-nutrition admin-nutrition-editor\"><summary>Edit nutrition information</summary>"+
      "<label for=\"adminNutritionServings\">Servings</label><input class=\"field\" id=\"adminNutritionServings\" value=\""+esc(n.servings)+"\">"+
      "<label for=\"adminNutritionServingSize\">Serving size</label><input class=\"field\" id=\"adminNutritionServingSize\" value=\""+esc(n.servingSize)+"\">"+
      "<div class=\"admin-nutrition-edit-grid\"><b>Nutrient</b><b>Per serving</b><b>% DI</b><b>Per 100 g</b>"+rows+"</div>"+
      "<small>Values are saved in the submission JSON payload. Keep the printed units; FOSCH does not calculate missing values.</small></details>";
  }
  function readAdminNutritionFromForm(){
    const val=id=>{const el=document.getElementById(id);return el?String(el.value||"").trim():"";};
    const n={servings:val("adminNutritionServings"),servingSize:val("adminNutritionServingSize"),perServing:{},dailyIntakePerServing:{},per100g:{}};
    NUTRITION_FIELDS.forEach(f=>{n.perServing[f.key]=val("adminNutritionServing_"+f.key);n.dailyIntakePerServing[f.key]=f.noDI?"":val("adminNutritionDI_"+f.key);n.per100g[f.key]=val("adminNutrition100g_"+f.key);});
    const has=!!(n.servings||n.servingSize||NUTRITION_FIELDS.some(f=>n.perServing[f.key]||n.dailyIntakePerServing[f.key]||n.per100g[f.key]));
    return has?n:null;
  }
  try {
    const stored = JSON.parse(localStorage.getItem(AUDIT_KEY) || "[]");
    if (Array.isArray(stored)) auditLog = stored;
  } catch (ignored) {}

  function urlArg(value) { return encodeURIComponent(String(value || "")).replace(/\'/g, "%27"); }
  function safeId(value) {
    return String(value || "").replace(/[^a-zA-Z0-9_-]/g, "");
  }
  function requireAdmin() {
    if (authenticated) return true;
    page = "adminLogin";
    render();
    return false;
  }
  function ensureRecordIds() {
    let changed = false;
    [contributions, correctionReports].forEach(function (records) {
      if (!Array.isArray(records)) return;
      records.forEach(function (record) {
        if (!record.id) {
          record.id = makeDraftId();
          changed = true;
        }
      });
    });
    if (changed) persistRecords();
  }
  function persistRecords() {
    try {
      localStorage.setItem("fosch-contrib", JSON.stringify(contributions.filter(function (record) { return !record._foschRemote; })));
      localStorage.setItem("fosch-corrections", JSON.stringify(correctionReports.filter(function (record) { return !record._foschRemote; })));
      return true;
    } catch (error) {
      alert("FOSCH could not save these admin changes on this device. Check available storage and try again.");
      return false;
    }
  }
  function writeAudit(action, kind, id, label, reason, details) {
    auditLog.unshift({
      id: makeDraftId(),
      at: new Date().toISOString(),
      actor: DEFAULT_EMAIL,
      action: String(action || ""),
      recordType: String(kind || ""),
      recordId: String(id || ""),
      recordLabel: String(label || ""),
      reason: String(reason || ""),
      details: String(details || "")
    });
    auditLog = auditLog.slice(0, 500);
    try { localStorage.setItem(AUDIT_KEY, JSON.stringify(auditLog)); }
    catch (error) { alert("The admin action was applied, but the audit log could not be saved. Free some device storage."); }
    if (window.FOSCHCloud && window.FOSCHCloud.isAdmin()) {
      window.FOSCHCloud.logAdminEvent(String(action || "Admin action"), {
        recordType: String(kind || ""), recordId: String(id || ""), recordLabel: String(label || ""),
        reason: String(reason || ""), details: String(details || "")
      }).catch(function (error) { console.warn("FOSCH audit sync failed:", error); });
    }
  }
  function getItems() {
    ensureRecordIds();
    const items = [];
    function stillNeedsReview(record) {
      if (!record) return false;
      if (record._foschRemote) return String(record.status || "").toLowerCase() === "pending-cloud";
      if (record.cloudSyncStatus === "synced" && record.cloudSubmissionId) return false;
      const status = String(record.status || "").toLowerCase();
      if (status.indexOf("rejected") === 0) return false;
      // Older builds could mark a device-only draft approved locally. Keep it
      // visible until it is uploaded; that local flag is not a shared approval.
      return status.indexOf("approved") !== 0 || status === "approved-local";
    }
    (Array.isArray(contributions) ? contributions : []).forEach(function (record) {
      if (stillNeedsReview(record)) items.push({ kind: "product", id: String(record.id), record: record });
    });
    (Array.isArray(correctionReports) ? correctionReports : []).forEach(function (record) {
      if (stillNeedsReview(record)) items.push({ kind: "correction", id: String(record.id), record: record });
    });
    return items.sort(function (a, b) {
      return String(b.record.date || "").localeCompare(String(a.record.date || ""));
    });
  }

  async function publishLegacyAdminMedia(rows){
    if(!window.FOSCHCloud||!window.FOSCHCloud.isAdmin())return;
    const registry=brandLogoRegistry();let changed=false;
    for(const key of Object.keys(registry)){
      const entry=registry[key];if(!entry||!String(entry.dataUrl||"").startsWith("data:image/")||entry.publicUrl)continue;
      try{const url=await window.FOSCHCloud.uploadPublicMedia(window.FOSCHCloud.brandLogoPath(entry.brand||key),entry.dataUrl);registry[key]=Object.assign({},entry,{publicUrl:url,updatedAt:new Date().toISOString()});changed=true}
      catch(error){console.warn("FOSCH could not publish an older brand logo yet:",error)}
    }
    if(changed){try{localStorage.setItem(LOGO_KEY,JSON.stringify(registry))}catch(ignored){}}
    for(const row of (Array.isArray(rows)?rows:[])){
      if(!row||row.status!=="approved"||!row.id||!row.payload||typeof row.payload!=="object")continue;
      const payload=row.payload,isCorrection=payload.suggestedProduct&&typeof payload.suggestedProduct==="object";
      const product=isCorrection?payload.suggestedProduct:payload,photos=recordPhotos(product);
      if(!photos.some(photo=>photo.startsWith("data:image/")))continue;
      const published=[];
      try{
        for(const photo of photos){const url=photo.startsWith("data:image/")?await window.FOSCHCloud.uploadProductPhoto(photo,row.country_code||product.country||payload.country,row.barcode||product.barcode||payload.barcode):photo;if(url&&!published.includes(url)&&published.length<5)published.push(url)}
        const nextProduct=Object.assign({},product,{officialPhoto:published[0]||"",officialPhotos:published});
        const nextPayload=isCorrection?Object.assign({},payload,{suggestedProduct:nextProduct}):Object.assign({},payload,nextProduct);
        await window.FOSCHCloud.adminUpdateSubmission(row.id,{payload:nextPayload,audit_action:"official_product_media_published",audit_details:{media_count:published.length,migration:"public_product_media"}},"Published previously uploaded official product images for cross-device display.");
        row.payload=nextPayload;
      }catch(error){console.warn("FOSCH could not migrate an older official product image yet:",error)}
    }
  }

  let remoteRefreshPromise = null;
  let lastAdminForegroundRefreshAt = 0;
  async function refreshRemoteData() {
    if (!window.FOSCHCloud || !window.FOSCHCloud.isAdmin()) return false;
    if (remoteRefreshPromise) return remoteRefreshPromise;
    const operation = (async function () {
      const response = await Promise.all([
        window.FOSCHCloud.listSubmissions(),
        window.FOSCHCloud.listAuditLog()
      ]);
      await publishLegacyAdminMedia(response[0] || []);
      // Build the replacement snapshot before changing visible state. A failed request
      // therefore leaves the last successful admin list intact.
      const remoteProducts = [];
      const remoteCorrections = [];
      (response[0] || []).forEach(function (row) {
        const payload = row.payload && typeof row.payload === "object" ? row.payload : {};
        const record = Object.assign({}, payload, {
          id: "cloud-" + String(row.id),
          _foschRemote: true,
          _remoteId: String(row.id),
          _remotePayload: payload,
          date: row.submitted_at || payload.date || "",
          status: row.status === "approved" ? "approved-cloud" : (row.status === "rejected" ? "rejected-cloud" : "pending-cloud"),
          rejectionReason: row.rejection_reason || "",
          adminNote: row.admin_note || "",
          barcode: row.barcode || payload.barcode || "",
          brand: row.brand || payload.brand || "",
          name: row.product_name || payload.name || payload.target || "",
          sizeLabel: row.package_size || payload.sizeLabel || "",
          ingredients: row.ingredients || payload.ingredients || "",
          country: row.country_code || payload.country || "",
          photos: Array.isArray(row.photo_paths) ? row.photo_paths : []
        });
        if (row.submission_type === "product") remoteProducts.push(record);
        else remoteCorrections.push(record);
      });
      const nextAudit = (response[1] || []).map(function (entry) {
        return {
          id: "remote-" + entry.id,
          at: entry.created_at,
          actor: entry.actor_email || "Administrator",
          action: entry.action || "Admin action",
          recordLabel: entry.client_record_id || "",
          reason: entry.reason || "",
          details: entry.details ? JSON.stringify(entry.details) : ""
        };
      });
      contributions = (Array.isArray(contributions) ? contributions : []).filter(function (record) { return !record._foschRemote; }).concat(remoteProducts);
      correctionReports = (Array.isArray(correctionReports) ? correctionReports : []).filter(function (record) { return !record._foschRemote; }).concat(remoteCorrections);
      remoteAuditLog = nextAudit;
      return true;
    })();
    remoteRefreshPromise = operation;
    try {
      return await operation;
    } finally {
      if (remoteRefreshPromise === operation) remoteRefreshPromise = null;
    }
  }
  async function refreshDashboard() {
    if (!requireAdmin()) return false;
    try {
      const refreshed = await refreshRemoteData();
      if (refreshed && authenticated && page === "adminDashboard") render();
      return !!refreshed;
    } catch (error) {
      alert("The administrator list could not be refreshed. The last loaded list has been kept. " + String(error && error.message || error));
      return false;
    }
  }
  async function onAppForeground() {
    if (!authenticated || !window.FOSCHCloud || !window.FOSCHCloud.isAdmin() || page !== "adminDashboard") return false;
    const now = Date.now();
    if (now - lastAdminForegroundRefreshAt < 1800) return false;
    lastAdminForegroundRefreshAt = now;
    try {
      const refreshed = await refreshRemoteData();
      if (refreshed && authenticated && page === "adminDashboard") render();
      return !!refreshed;
    } catch (error) {
      // Keep the current snapshot on transient failures. The next foreground visit retries.
      console.warn("FOSCH admin dashboard refresh failed:", error);
      return false;
    }
  }

  function findItem(kind, id) {
    const records = kind === "product" ? contributions : correctionReports;
    if (!Array.isArray(records)) return null;
    const requested = String(id == null ? "" : id);
    let index = records.findIndex(function (record) {
      if (!record) return false;
      return String(record.id == null ? "" : record.id) === requested ||
        String(record._remoteId == null ? "" : record._remoteId) === requested ||
        (record._foschRemote && "cloud-" + String(record._remoteId || "") === requested);
    });

    // Compatibility for cards rendered by older builds, which removed punctuation
    // from IDs before placing them in inline action handlers. Only use this fallback
    // when it identifies exactly one record; never guess between collisions.
    if (index < 0) {
      const wanted = safeId(requested);
      const matches = [];
      records.forEach(function (record, candidateIndex) {
        if (!record) return;
        const ids = [record.id, record._remoteId,
          record._foschRemote ? "cloud-" + String(record._remoteId || "") : ""];
        if (ids.some(function (value) { return value != null && safeId(value) === wanted; })) {
          matches.push(candidateIndex);
        }
      });
      if (matches.length === 1) index = matches[0];
    }
    return index < 0 ? null : { kind: kind, id: String(records[index].id || requested), record: records[index], index: index, records: records };
  }
  function itemLabel(item) {
    if (item.kind === "correction") return correctionDraftProductName(item.record) || "Product correction";
    return String(item.record.name || "Unnamed product");
  }
  function itemBrand(item) {
    const p = item.record.suggestedProduct || item.record;
    return String(p.brand || item.record.brand || "").trim();
  }
  function isApproved(record) {
    return String(record.status || "").toLowerCase().indexOf("approved") >= 0;
  }
  function isRejected(record) {
    return String(record && record.status || "").toLowerCase().indexOf("rejected") === 0;
  }
  function removeFromLocalMarketIndex(record) {
    const product = record && (record.suggestedProduct || record);
    if (!product || !product.country || typeof marketProductStorageKey !== "function") return;
    try {
      const code = String(product.country || "").toUpperCase();
      const key = marketProductStorageKey(code);
      const stored = JSON.parse(localStorage.getItem(key) || "[]");
      if (!Array.isArray(stored)) return;
      const productKey = product.productKey || stableProductKey(code, product.brand || "", product.name || "");
      const barcode = String(product.barcode || record.barcode || "");
      const size = normalisePackageSizeKey(product.sizeLabel || product.size || "");
      const kept = stored.filter(function (item) {
        return !(String(item.productKey || "") === productKey &&
          String(item.barcode || "") === barcode &&
          normalisePackageSizeKey(item.sizeLabel || item.size || "") === size);
      });
      localStorage.setItem(key, JSON.stringify(kept));
    } catch (error) {
      alert("The rejection was saved, but FOSCH could not remove the matching local product-index entry.");
    }
  }
  function brandLogoRegistry() {
    try {
      const value = JSON.parse(localStorage.getItem(LOGO_KEY) || "{}");
      return value && typeof value === "object" && !Array.isArray(value) ? value : {};
    } catch (ignored) { return {}; }
  }
  function logoFor(brand) {
    const candidates = String(brand || "").split(/[,;|/]+/).map(function (value) {
      return value.trim();
    }).filter(Boolean);
    if (!candidates.length) return null;
    const registry = brandLogoRegistry();
    for (const candidate of candidates) {
      const key = normaliseBrandKey(candidate);
      if (!key) continue;
      const entry = registry[key];
      if (entry && String(entry.dataUrl || "").startsWith("data:image/")) return entry;
    }
    return null;
  }
  function isPublicProductMediaUrl(value){return typeof value==="string"&&/^https:\/\/mqgvagftvuybietazbdc\.supabase\.co\/storage\/v1\/object\/public\/fosch-public-product-media\//.test(value)}
  function recordPhotos(record){
    if(!record)return[];
    const stored=Array.isArray(record.officialPhotos)?record.officialPhotos:[];
    const latest=String(record.officialPhoto||"");
    const ordered=latest?[latest].concat(stored.filter(v=>v!==latest)):stored;
    const seen=[];
    ordered.forEach(function(value){if(typeof value==="string"&&(value.startsWith("data:image/")||isPublicProductMediaUrl(value))&&seen.indexOf(value)<0&&seen.length<5)seen.push(value)});
    return seen;
  }
  function renderProductMedia(product) {
    if(!product)return"";
    const brands=String(product.brand||product.brands||"").split(/[,;|/]+/).map(v=>v.trim()).filter(Boolean);
    for(const brand of brands){
      const logo=logoFor(brand);
      const publicUrl=window.FOSCHCloud&&window.FOSCHCloud.publicBrandLogoUrl?window.FOSCHCloud.publicBrandLogoUrl(brand):"";
      const src=publicUrl||(logo&&logo.dataUrl)||"";
      if(src){const label=String((logo&&logo.brand)||brand).trim();return "<div class=\"product-media-row\"><img class=\"product-brand-logo\" loading=\"lazy\" src=\""+esc(src)+"\" alt=\""+esc(label)+" brand logo\" onerror=\"this.style.display='none'\"></div>"}
    }
    return "";
  }
  function renderOfficialProductPhotos(product) {
    if (!product) return "";
    const photos = recordPhotos(product).slice(0, 5);
    if (!photos.length) return "";
    return "<section class=\"official-product-gallery\"><b>Official product images</b><div class=\"official-product-gallery-grid\">" +
      photos.map(function (photo, index) {
        return "<img class=\"product-official-photo\" loading=\"lazy\" decoding=\"async\" src=\"" + esc(photo) + "\" alt=\"Official product image " + (index + 1) + "\">";
      }).join("") + "</div></section>";
  }
  function imagePreview(record) {
    const official = recordPhotos(record);
    const evidence = Array.isArray(record.photos) ? record.photos : [];
    const photo = official[0] || evidence.find(function (v) { return typeof v === "string" && v.startsWith("data:image/"); }) || record.photo || "";
    return String(photo).startsWith("data:image/") ? "<img class=\"admin-submission-image\" src=\"" + esc(photo) + "\" alt=\"Submission photo\">" : "";
  }
  function submissionCard(item) {
    const record = item.record;
    const title = itemLabel(item);
    const brand = itemBrand(item);
    const rejected = isRejected(record);
    const status = !record._foschRemote
      ? "Local draft · not shared"
      : (rejected ? "Rejected" : (isApproved(record) ? "Approved" : "Pending review"));
    const id = urlArg(item.id);
    const type = item.kind;
    const brandCall = brand ? "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.captureBrandLogo(decodeURIComponent('" + urlArg(brand) + "'))\">Capture brand logo</button><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.uploadBrandLogo(decodeURIComponent('" + urlArg(brand) + "'))\">Upload brand logo</button>" : "";
    const rejectionDetails = rejected
      ? "<div class=\"warning\"><b>Rejection reason:</b> " + esc(record.rejectionReason || "No reason supplied") +
        (record._foschRemote ? "<small>Stored with the shared submission record.</small>" :
        "<small>Retained on this device until " + esc(record.rejectionExpiresAt ? new Date(record.rejectionExpiresAt).toLocaleString() : "seven-day expiry") + ".</small>") + "</div>"
      : "";
    const rejectButton = rejected || !record._foschRemote ? "" : "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.reject('" + type + "',decodeURIComponent('" + id + "'))\">Reject</button>";
    const localSyncButton = !record._foschRemote ? "<button type=\"button\" class=\"primary\" onclick=\"FOSCHAdmin.retrySync('" + type + "',decodeURIComponent('" + id + "'))\">Retry shared upload</button>" : "";
    const localSyncMessage = !record._foschRemote ? "<div class=\"warning\"><b>Local draft — not yet in Supabase.</b><small>" + esc(record.cloudSyncError || (record.cloudSyncStatus === "syncing" ? "Upload in progress." : "This device copy cannot be approved as a shared submission.")) + "</small></div>" : "";
    const approveButton = rejected || !record._foschRemote ? "" : "<button type=\"button\" class=\"primary\" onclick=\"FOSCHAdmin.approve('" + type + "',decodeURIComponent('" + id + "'))\">" + (isApproved(record) ? "Re-approve" : "Approve") + "</button>";
    // Official product media actions belong on both local drafts and shared
    // submissions. Previously these were accidentally hidden for every remote row.
    const localMediaButtons =
      "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.captureOfficialPhoto('" + type + "',decodeURIComponent('" + id + "'))\">Capture official photo</button>" +
      "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.uploadOfficialPhoto('" + type + "',decodeURIComponent('" + id + "'))\">Upload image from device</button>";
    return "<article class=\"admin-submission-card\">" +
      "<div class=\"admin-submission-top\"><div><div class=\"admin-submission-title\">" + esc(title) + "</div>" +
      (brand ? "<div class=\"admin-submission-meta\">" + esc(brand) + "</div>" : "") +
      "<div class=\"admin-submission-meta\">" + esc(type === "product" ? "Product contribution" : "Correction report") + " · " +
      esc(record.barcode || (record.suggestedProduct && record.suggestedProduct.barcode) || "No barcode") + "</div>" +
      "<div class=\"admin-submission-meta\">" + esc(record.date ? new Date(record.date).toLocaleString() : "Date not recorded") + "</div></div>" +
      "<span class=\"admin-status " + (isApproved(record) ? "approved" : "") + "\">" + esc(status) + "</span></div>" +
      imagePreview(record) + rejectionDetails + localSyncMessage + nutritionMarkup(record) +
      "<div class=\"admin-submission-meta\">" + esc(record.ingredients || (record.suggestedProduct && record.suggestedProduct.ingredients) || record.message || "No ingredient details supplied") + "</div>" +
      "<div class=\"admin-action-grid\">" + approveButton +
      "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.edit('" + type + "',decodeURIComponent('" + id + "'))\">Edit submission</button>" +
      localMediaButtons + brandCall + localSyncButton + rejectButton +
      "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.remove('" + type + "',decodeURIComponent('" + id + "'))\">Delete</button>" +
      "</div></article>";
  }

  function brandCardsMarkup() {
    const seen = Object.create(null);
    getItems().forEach(function (item) {
      const brand = itemBrand(item);
      const key = normaliseBrandKey(brand);
      if (key && !seen[key]) seen[key] = brand;
    });
    const brands = Object.keys(seen).map(function (key) { return { key: key, name: seen[key] }; });
    if (!brands.length) return "<p class=\"muted\">Brand names will appear here when product submissions are available.</p>";
    return brands.map(function (item) {
      const logo = logoFor(item.name);
      return "<article class=\"admin-brand-card\"><div class=\"admin-brand-row\">" +
        (logo ? "<img class=\"admin-brand-logo\" src=\"" + esc(logo.dataUrl) + "\" alt=\"" + esc(item.name) + " logo\">" : "<div class=\"admin-brand-logo\" aria-hidden=\"true\">＋</div>") +
        "<div class=\"admin-brand-name\">" + esc(item.name) + "<div class=\"admin-submission-meta\">" + (logo ? "Published for all users" : "No logo added") + "</div></div>" +
        "</div><div class=\"admin-action-grid\"><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.captureBrandLogo(decodeURIComponent('" + urlArg(item.name) + "'))\">Capture " + (logo ? "replacement logo" : "logo") + "</button><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.uploadBrandLogo(decodeURIComponent('" + urlArg(item.name) + "'))\">Upload from device</button></div></article>";
    }).join("");
  }
  function auditMarkup() {
    const entries = remoteAuditLog.concat(auditLog).sort(function (a, b) {
      return String(b.at || "").localeCompare(String(a.at || ""));
    });
    if (!entries.length) return "<section class=\"panel empty\"><h3>No admin activity yet</h3><p class=\"muted\">Sign-ins, approvals, edits and deletions will be recorded here.</p></section>";
    return entries.map(function (entry) {
      return "<article class=\"admin-audit-row\"><b>" + esc(entry.action) +
        (entry.recordLabel ? " · " + esc(entry.recordLabel) : "") + "</b>" +
        "<small>" + esc(entry.at ? new Date(entry.at).toLocaleString() : "Unknown time") + " · " + esc(entry.actor || DEFAULT_EMAIL) + "</small>" +
        (entry.reason ? "<small>Reason: " + esc(entry.reason) + "</small>" : "") +
        (entry.details ? "<small>" + esc(entry.details) + "</small>" : "") +
        "</article>";
    }).join("");
  }
  function dashboardMarkup() {
    if (!authenticated) return "<section class=\"panel\"><p>Admin session expired. Please sign in again.</p></section>";
    const items = getItems();
    const sharedItems = items.filter(function (item) { return !!item.record._foschRemote; });
    const localItems = items.filter(function (item) { return !item.record._foschRemote; });
    const submissions = sharedItems.length
      ? sharedItems.map(submissionCard).join("")
      : "<section class=\"panel empty\"><h3>No shared submissions awaiting review</h3><p class=\"muted\">Only submissions confirmed in Supabase appear in this queue.</p></section>";
    const localDrafts = localItems.length
      ? "<h2>Local drafts — not in Supabase</h2><p class=\"muted\">These records are stored on this device only until their upload succeeds. They cannot be approved or rejected as shared submissions.</p>" + localItems.map(submissionCard).join("")
      : "";
    const brandMarkup = "<h2>Brand logos</h2><p class=\"muted\">Administrators can capture a logo with the in-app camera or select an image from this device. Administrator brand logos are published through shared media storage and are visible to all users.</p>" + brandCardsMarkup();
    const content = activeTab === "activity" ? auditMarkup() :
      "<p class=\"muted\">" + sharedItems.length + " shared submission" + (sharedItems.length === 1 ? "" : "s") +
      " awaiting review · " + localItems.length + " local draft" + (localItems.length === 1 ? "" : "s") + " awaiting upload.</p>" +
      submissions + localDrafts + brandMarkup;
    return "<div class=\"admin-head\"><div><h1>Admin dashboard</h1><div class=\"admin-submission-meta\">Signed in as " + esc(DEFAULT_EMAIL) + "</div></div><div class=\"admin-head-actions\"><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.refreshDashboard()\">Refresh</button><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.logout()\">Sign out</button></div></div>" +
      "<div class=\"admin-tabs\"><button type=\"button\" class=\"" + (activeTab === "submissions" ? "primary" : "secondary") + "\" onclick=\"FOSCHAdmin.setTab('submissions')\">Submissions</button>" +
      "<button type=\"button\" class=\"" + (activeTab === "activity" ? "primary" : "secondary") + "\" onclick=\"FOSCHAdmin.setTab('activity')\">Activity log (" + (remoteAuditLog.length + auditLog.length) + ")</button></div>" +
      content +
      "<div class=\"warning\">Shared product submissions and moderation actions are stored in Supabase. Unsent drafts remain on this device until a network connection is available. Product evidence remains private; official product images and brand logos use a separate public-read bucket.</div>";
  }

  function editMarkup() {
    if (!authenticated || !editingRef) return "<section class=\"panel\"><p>No submission is selected.</p><button class=\"secondary\" onclick=\"FOSCHAdmin.backToDashboard()\">Back</button></section>";
    const item = findItem(editingRef.kind, editingRef.id);
    if (!item) return "<section class=\"panel\"><p>This submission no longer exists.</p><button class=\"secondary\" onclick=\"FOSCHAdmin.backToDashboard()\">Back</button></section>";
    const record = item.record;
    const structured = item.kind === "product" || !!record.suggestedProduct;
    const product = structured ? (record.suggestedProduct || record) : record;
    const fields = structured
      ? "<label for=\"adminEditBrand\">Brand</label><input id=\"adminEditBrand\" class=\"field\" value=\"" + esc(product.brand || "") + "\">" +
        "<label for=\"adminEditName\">Product name</label><input id=\"adminEditName\" class=\"field\" value=\"" + esc(product.name || "") + "\" required>" +
        "<label for=\"adminEditBarcode\">Barcode</label><input id=\"adminEditBarcode\" class=\"field\" value=\"" + esc(product.barcode || record.barcode || "") + "\">" +
        "<label for=\"adminEditSize\">Package size</label><input id=\"adminEditSize\" class=\"field\" value=\"" + esc(product.sizeLabel || product.size || "") + "\">" +
        "<label for=\"adminEditIngredients\">Ingredients</label><textarea id=\"adminEditIngredients\" class=\"field\" rows=\"5\">" + esc(product.ingredients || "") + "</textarea>" +
        "<label for=\"adminEditOrigin\">Produced in / Made in</label><input id=\"adminEditOrigin\" class=\"field\" value=\"" + esc(product.producedIn || "") + "\">" +
        "<label for=\"adminEditOriginPercentage\">Country-of-origin percentage</label><input id=\"adminEditOriginPercentage\" class=\"field\" value=\"" + esc(product.producedInPercentage == null ? "" : product.producedInPercentage) + "\">" +
        nutritionEditorMarkup(product)
      : "<label for=\"adminEditTarget\">Correction target</label><input id=\"adminEditTarget\" class=\"field\" value=\"" + esc(record.target || "") + "\">" +
        "<label for=\"adminEditMessage\">Correction details</label><textarea id=\"adminEditMessage\" class=\"field\" rows=\"5\">" + esc(record.message || "") + "</textarea>" +
        "<label for=\"adminEditSource\">Source</label><input id=\"adminEditSource\" class=\"field\" value=\"" + esc(record.source || "") + "\">";
    return "<button class=\"back\" type=\"button\" onclick=\"FOSCHAdmin.backToDashboard()\">‹ Admin dashboard</button><h1>Edit submission</h1><p class=\"muted\">" + esc(itemLabel(item)) + "</p>" +
      "<section class=\"panel\"><form class=\"admin-edit-form\" onsubmit=\"FOSCHAdmin.saveEdit(event)\">" + fields +
      "<label for=\"adminEditReason\">Reason for editing (required)</label><textarea id=\"adminEditReason\" class=\"field\" rows=\"3\" minlength=\"10\" required placeholder=\"Explain why these changes are needed\"></textarea>" +
      "<p class=\"admin-reason-note\">A reason of at least 10 characters is required and will be saved in the local audit log.</p>" +
      "<button class=\"primary\" type=\"submit\" style=\"width:100%;margin-top:14px\">Save changes</button><button type=\"button\" class=\"secondary\" style=\"width:100%;margin-top:8px\" onclick=\"FOSCHAdmin.backToDashboard()\">Cancel</button></form></section>";
  }
  function validReason(reason) {
    const value = String(reason || "").trim();
    return value.length >= 10 && /[a-z0-9]/i.test(value);
  }
  function askReason(action) {
    const reason = prompt("Enter the reason for " + action + " (at least 10 characters).");
    if (reason === null) return null;
    if (!validReason(reason)) {
      alert("Please provide a meaningful reason of at least 10 characters.");
      return null;
    }
    return reason.trim();
  }
  async function signIn(event) {
    if (event && event.preventDefault) event.preventDefault();
    const email = String((document.getElementById("adminEmail") || {}).value || "").trim().toLowerCase();
    const password = String((document.getElementById("adminPass") || {}).value || "");
    const status = document.getElementById("adminLoginStatus");
    if (!window.FOSCHCloud) {
      if (status) status.textContent = "The authentication service is unavailable. Reload FOSCH and try again.";
      return;
    }
    if (status) status.textContent = "Signing in securely…";
    try {
      const user = await window.FOSCHCloud.signIn(email, password);
      authenticated = true;
      DEFAULT_EMAIL = String((user && user.email) || email || "Administrator");
      activeTab = "submissions";
      await refreshRemoteData();
      writeAudit("Admin sign-in", "", "", "", "", "Authenticated through Supabase Auth.");
      page = "adminDashboard";
      pageHistory = [];
      suppressNextHistoryPush = true;
      render();
    } catch (error) {
      authenticated = false;
      if (status) status.textContent = String(error && error.message || "Sign-in failed. Check the email and password.");
    }
  }
  function setTab(tab) {
    if (!requireAdmin()) return;
    activeTab = tab === "activity" ? "activity" : "submissions";
    page = "adminDashboard";
    render();
  }
  async function retrySync(kind, id) {
    if (!requireAdmin()) return;
    const item = findItem(kind, id);
    if (!item) { alert("This local draft could not be found."); return; }
    if (item.record._foschRemote) { alert("This submission is already shared with Supabase."); return; }
    if (!window.FOSCHCloud) { alert("The Supabase connection is unavailable. The draft remains on this device."); return; }
    try {
      const submissionKind = kind === "product" ? "product" :
        (item.record.kind === "product-correction" ? "product_correction" : "correction");
      const result = await window.FOSCHCloud.queueSubmission(item.record, submissionKind);
      if (!result || !result.ok) {
        alert("The draft is still only on this device. Supabase did not confirm the upload: " +
          String(result && result.message || item.record.cloudSyncError || "Unknown upload error"));
      } else {
        await refreshRemoteData();
        alert("The submission has been uploaded to Supabase and is now in the shared review queue.");
      }
      page = "adminDashboard";
      render();
    } catch (error) {
      alert("The draft remains on this device. Shared upload failed: " + String(error && error.message || error));
      page = "adminDashboard";
      render();
    }
  }

  function approve(kind, id) {
    if (!requireAdmin()) return;
    const item = findItem(kind, id);
    if (!item) { alert("This submission could not be found."); return; }
    if (!item.record._foschRemote) {
      alert("This is a local device draft, not a shared Supabase submission. Retry the shared upload first; only successfully uploaded submissions can be approved.");
      return;
    }
    if (isRejected(item.record)) { alert("Rejected submissions cannot be approved."); return; }
    const note = prompt("Reason or review note for this approval (at least 10 characters):", "Approved after review");
    if (note === null) return;
    if (item.record._foschRemote) {
      if (!validReason(note)) { alert("Please provide a meaningful review reason of at least 10 characters."); return; }
      window.FOSCHCloud.adminUpdateSubmission(item.record._remoteId, {
        status: "approved", admin_note: String(note).trim(), rejection_reason: "",
        audit_action: "submission_approved"
      }, String(note).trim()).then(async function () {
        await refreshRemoteData(); page = "adminDashboard"; render();
      }).catch(function (error) { alert("The shared submission could not be approved: " + String(error.message || error)); });
      return;
    }
    item.record.status = "approved-local";
    item.record.adminReviewedAt = new Date().toISOString();
    item.record.adminReviewedBy = DEFAULT_EMAIL;
    if (!persistRecords()) return;
    if (kind === "product") {
      saveMarketBrand(item.record.brand || "", item.record.country || marketCountry);
      saveMarketProduct(item.record);
    } else if (item.record.suggestedProduct) {
      const product = item.record.suggestedProduct;
      saveMarketBrand(product.brand || "", product.country || item.record.country || marketCountry);
      saveMarketProduct(Object.assign({}, product, { country: product.country || item.record.country || marketCountry }));
    }
    writeAudit("Submission approved", kind, id, itemLabel(item), String(note || "").trim(), "Marked approved on this device only.");
    page = "adminDashboard";
    render();
  }
  function reject(kind, id) {
    if (!requireAdmin()) return;
    const item = findItem(kind, id);
    if (!item) { alert("This submission could not be found."); return; }
    if (!item.record._foschRemote) {
      alert("This is a local device draft, not a shared Supabase submission. Retry the shared upload before rejecting it.");
      return;
    }
    if (isRejected(item.record)) { alert("This submission has already been rejected."); return; }
    const reason = askReason("rejecting this submission");
    if (reason === null) return;
    if (item.record._foschRemote) {
      window.FOSCHCloud.adminUpdateSubmission(item.record._remoteId, {
        status: "rejected", rejection_reason: reason, audit_action: "submission_rejected"
      }, reason).then(async function () {
        await refreshRemoteData(); page = "adminDashboard"; render();
      }).catch(function (error) { alert("The shared submission could not be rejected: " + String(error.message || error)); });
      return;
    }
    const previous = JSON.parse(JSON.stringify(item.record));
    const wasApproved = isApproved(item.record);
    const rejectedAt = new Date();
    const expiresAt = new Date(rejectedAt.getTime() + 7 * 24 * 60 * 60 * 1000);
    item.record.status = "rejected-local";
    item.record.rejectionReason = reason;
    item.record.rejectedAt = rejectedAt.toISOString();
    item.record.rejectionExpiresAt = expiresAt.toISOString();
    item.record.rejectedBy = DEFAULT_EMAIL;
    item.record.rejectionNotification = { title: "Submission rejected", message: reason, createdAt: rejectedAt.toISOString() };
    if (!persistRecords()) {
      item.records[item.index] = previous;
      return;
    }
    if (kind === "product" && wasApproved) removeFromLocalMarketIndex(item.record);
    writeAudit("Submission rejected", kind, id, itemLabel(item), reason,
      "Marked read-only on this device until " + expiresAt.toISOString() + ".");
    page = "adminDashboard";
    render();
  }
  function remove(kind, id) {
    if (!requireAdmin()) return;
    const item = findItem(kind, id);
    if (!item) { alert("This submission could not be found."); return; }
    const reason = askReason("deleting this submission");
    if (reason === null) return;
    if (!confirm(item.record._foschRemote
      ? "Remove this shared submission from the pending queue? It will be marked Rejected for the submitter, and the reason will be retained in the audit log."
      : "Delete \"" + itemLabel(item) + "\" from this device? This cannot be undone.")) return;
    if (item.record._foschRemote) {
      window.FOSCHCloud.adminDeleteSubmission(item.record._remoteId, reason).then(async function () {
        await refreshRemoteData(); page = "adminDashboard"; render();
      }).catch(function (error) { alert("The shared submission could not be deleted: " + String(error.message || error)); });
      return;
    }
    item.records.splice(item.index, 1);
    if (!persistRecords()) return;
    writeAudit("Submission deleted", kind, id, itemLabel(item), reason, "Removed from this device's local submission list.");
    page = "adminDashboard";
    render();
  }

  async function edit(kind, id) {
    if (!requireAdmin()) return;
    let item = findItem(kind, id);
    if (!item) {
      try { await refreshRemoteData(); } catch (error) { console.warn("FOSCH refreshed the admin list after a missed selection:", error); }
      item = findItem(kind, id);
    }
    if (!item) { alert("This submission could not be found. Refresh the administrator page and try again."); return; }
    editingRef = { kind: kind, id: String(item.id) };
    page = "adminEditSubmission";
    render();
  }
  function backToDashboard() {
    if (!requireAdmin()) return;
    editingRef = null;
    page = "adminDashboard";
    render();
  }
  function saveEdit(event) {
    if (event && event.preventDefault) event.preventDefault();
    if (!requireAdmin() || !editingRef) return;
    const item = findItem(editingRef.kind, editingRef.id);
    if (!item) { alert("This submission could not be found."); backToDashboard(); return; }
    const reason = String((document.getElementById("adminEditReason") || {}).value || "").trim();
    if (!validReason(reason)) { alert("Please provide a meaningful reason of at least 10 characters."); return; }
    const record = item.record;
    const structured = item.kind === "product" || !!record.suggestedProduct;
    if (structured) {
      const product = record.suggestedProduct || record;
      product.brand = String((document.getElementById("adminEditBrand") || {}).value || "").trim();
      product.name = String((document.getElementById("adminEditName") || {}).value || "").trim();
      product.barcode = String((document.getElementById("adminEditBarcode") || {}).value || "").trim();
      product.sizeLabel = String((document.getElementById("adminEditSize") || {}).value || "").trim();
      product.ingredients = String((document.getElementById("adminEditIngredients") || {}).value || "").trim();
      product.producedIn = String((document.getElementById("adminEditOrigin") || {}).value || "").trim();
      product.producedInPercentage = String((document.getElementById("adminEditOriginPercentage") || {}).value || "").trim();
      product.nutritionInfo = readAdminNutritionFromForm();
      if (item.kind === "correction") record.suggestedProduct = product;
      Object.assign(record, { brand: product.brand, name: product.name, barcode: product.barcode, sizeLabel: product.sizeLabel, ingredients: product.ingredients });
      if (item.kind !== "correction") {
        record.producedIn = product.producedIn;
        record.producedInPercentage = product.producedInPercentage;
        record.nutritionInfo = product.nutritionInfo;
      }
    } else {
      record.target = String((document.getElementById("adminEditTarget") || {}).value || "").trim();
      record.message = String((document.getElementById("adminEditMessage") || {}).value || "").trim();
      record.source = String((document.getElementById("adminEditSource") || {}).value || "").trim();
    }
    record.adminEditedAt = new Date().toISOString();
    record.adminEditedBy = DEFAULT_EMAIL;
    if (record._foschRemote) {
      const payload = Object.assign({}, record._remotePayload || {});
      const changes = {};
      if (structured) {
        const product = record.suggestedProduct || record;
        Object.assign(changes, {
          brand: record.brand, product_name: record.name, barcode: record.barcode,
          package_size: record.sizeLabel, ingredients: record.ingredients
        });
        if (item.kind === "correction" && payload.suggestedProduct) {
          payload.suggestedProduct = Object.assign({}, payload.suggestedProduct, {
            brand: product.brand, name: product.name, barcode: product.barcode,
            sizeLabel: product.sizeLabel, ingredients: product.ingredients, producedIn: product.producedIn,
            producedInPercentage: product.producedInPercentage, nutritionInfo: product.nutritionInfo
          });
        } else {
          Object.assign(payload, {
            brand: product.brand, name: product.name, barcode: product.barcode,
            sizeLabel: product.sizeLabel, ingredients: product.ingredients, producedIn: product.producedIn,
            producedInPercentage: product.producedInPercentage, nutritionInfo: product.nutritionInfo
          });
        }
      } else {
        Object.assign(changes, { target: record.target, message: record.message, source: record.source });
        Object.assign(payload, { target: record.target, message: record.message, source: record.source });
      }
      changes.payload = payload;
      changes.audit_action = "submission_edited";
      window.FOSCHCloud.adminUpdateSubmission(record._remoteId, changes, reason).then(async function () {
        await refreshRemoteData();
        editingRef = null; page = "adminDashboard"; render();
      }).catch(function (error) {
        alert("The shared submission could not be saved: " + String(error.message || error));
      });
      return;
    }
    if (!persistRecords()) return;
    if (item.kind === "product" && isApproved(record)) saveMarketProduct(record);
    if (item.kind === "correction" && isApproved(record) && record.suggestedProduct) saveMarketProduct(Object.assign({}, record.suggestedProduct, { country: record.suggestedProduct.country || record.country || marketCountry }));
    writeAudit("Submission edited", item.kind, item.id, itemLabel(item), reason, "Edited fields were saved locally.");
    editingRef = null;
    page = "adminDashboard";
    render();
  }

  function captureOfficialPhoto(kind, id) {
    if (!requireAdmin()) return;
    if (!findItem(kind, id)) { alert("This submission could not be found."); return; }
    const note = prompt("Optional note for adding an official product photo:", "Official product image captured by an administrator.");
    if (note === null) return;
    pendingMedia = { type: "productPhoto", kind: kind, id: String(id), note: String(note || "").trim(), source: "camera" };
    if (window.FOSCHBridge && window.FOSCHBridge.captureProductPhoto) window.FOSCHBridge.captureProductPhoto();
    else { pendingMedia = null; alert("The in-app camera is not available in this build."); }
  }
  function uploadOfficialPhoto(kind, id) {
    if (!requireAdmin()) return;
    if (!findItem(kind, id)) { alert("This submission could not be found."); return; }
    const note = prompt("Optional note for uploading an official product photo:", "Official product image supplied by an administrator.");
    if (note === null) return;
    pendingMedia = { type: "productPhoto", kind: kind, id: String(id), note: String(note || "").trim(), source: "device" };
    if (window.FOSCHBridge && window.FOSCHBridge.chooseAdminImage) window.FOSCHBridge.chooseAdminImage();
    else { pendingMedia = null; alert("Device image selection is not available in this build."); }
  }
  function captureBrandLogo(brand) {
    if (!requireAdmin()) return;
    const name = String(brand || "").trim();
    if (!name) return;
    const note = prompt("Optional note for adding or replacing this brand logo:", "Official brand logo captured by an administrator.");
    if (note === null) return;
    pendingMedia = { type: "brandLogo", brand: name, note: String(note || "").trim(), source: "camera" };
    if (window.FOSCHBridge && window.FOSCHBridge.captureProductPhoto) window.FOSCHBridge.captureProductPhoto();
    else { pendingMedia = null; alert("The in-app camera is not available in this build."); }
  }
  function uploadBrandLogo(brand) {
    if (!requireAdmin()) return;
    const name = String(brand || "").trim();
    if (!name) return;
    const note = prompt("Optional note for adding or replacing this brand logo:", "Official brand logo supplied by an administrator.");
    if (note === null) return;
    pendingMedia = { type: "brandLogo", brand: name, note: String(note || "").trim(), source: "device" };
    if (window.FOSCHBridge && window.FOSCHBridge.chooseAdminImage) window.FOSCHBridge.chooseAdminImage();
    else { pendingMedia = null; alert("Device image selection is not available in this build."); }
  }
  function isCapturingMedia() { return !!pendingMedia; }
  function onPhotoCaptured(dataUrl) {
    if (!pendingMedia) return false;
    const target = pendingMedia;
    pendingMedia = null;
    if (!String(dataUrl || "").startsWith("data:image/")) {
      alert("The camera did not return a valid image.");
      return true;
    }
    if (target.type === "productPhoto") {
      const item = findItem(target.kind, target.id);
      if (!item) { alert("The submission no longer exists."); return true; }
      const record = item.record;
      const product = target.kind === "correction" && record.suggestedProduct ? record.suggestedProduct : record;
      const latestPhoto = String(dataUrl);
      const previousPhotos = Array.isArray(product.officialPhotos) ? product.officialPhotos : [];
      const nextPhotos = [latestPhoto].concat(previousPhotos.filter(function (photo) {
        return typeof photo === "string" && (photo.startsWith("data:image/") || isPublicProductMediaUrl(photo)) && photo !== latestPhoto;
      })).slice(0, 5);
      const previousOfficialPhoto = product.officialPhoto;
      const previousOfficialPhotos = product.officialPhotos;
      product.officialPhotos = nextPhotos;
      product.officialPhoto = latestPhoto;
      if (target.kind === "correction" && record.suggestedProduct) record.suggestedProduct = product;

      if(record._foschRemote){
        (async function(){
          try{
            const country=product.country||record.country||marketCountry,barcode=product.barcode||record.barcode||"",published=[];
            for(const photo of nextPhotos){const url=photo.startsWith("data:image/")?await window.FOSCHCloud.uploadProductPhoto(photo,country,barcode):photo;if(url&&!published.includes(url)&&published.length<5)published.push(url)}
            const remotePayload=Object.assign({},record._remotePayload||{});
            const remoteProduct=target.kind==="correction"&&remotePayload.suggestedProduct?Object.assign({},remotePayload.suggestedProduct):remotePayload;
            remoteProduct.officialPhoto=published[0]||"";remoteProduct.officialPhotos=published;
            if(target.kind==="correction"&&remotePayload.suggestedProduct)remotePayload.suggestedProduct=remoteProduct;
            else{remotePayload.officialPhoto=published[0]||"";remotePayload.officialPhotos=published}
            const reason=String(target.note||"Administrator added an official product image after review.");
            await window.FOSCHCloud.adminUpdateSubmission(record._remoteId,{payload:remotePayload,audit_action:"official_product_photo_added",audit_details:{source:target.source==="device"?"device_upload":"camera_capture",public_media:true}},reason);
            if(isApproved(record)&&typeof window.FOSCHApplyCloudApproval==="function"){
              const cacheRecord=Object.assign({},remotePayload,{status:"approved-cloud",country:country,barcode:barcode});
              try{window.FOSCHApplyCloudApproval(cacheRecord)}catch(cacheError){console.warn("FOSCH local media cache failed:",cacheError)}
            }
            await refreshRemoteData();page="adminDashboard";render();alert("Official product image published for FOSCH users.");
          }catch(error){
            product.officialPhoto=previousOfficialPhoto||"";product.officialPhotos=previousOfficialPhotos||[];
            if(target.kind==="correction"&&record.suggestedProduct)record.suggestedProduct=product;
            alert("The image could not be published. Check that the latest Supabase public-media SQL migration has been run. "+String(error&&error.message||error));
            page="adminDashboard";render();
          }
        })();
        return true;
      }

      if (!persistRecords()) return true;
      if (target.kind === "product" && isApproved(record)) saveMarketProduct(record);
      if (target.kind === "correction" && isApproved(record) && record.suggestedProduct) saveMarketProduct(Object.assign({}, product, { country: product.country || record.country || marketCountry }));
      writeAudit("Official product photo added", target.kind, target.id, itemLabel(item), target.note, (target.source === "device" ? "Selected from device storage" : "Captured with FOSCH's in-app camera") + " and stored locally.");
    } else if (target.type === "brandLogo") {
      (async function(){
        try{
          const publicUrl=await window.FOSCHCloud.uploadPublicMedia(window.FOSCHCloud.brandLogoPath(target.brand),String(dataUrl));
          const registry=brandLogoRegistry();
          registry[normaliseBrandKey(target.brand)]={brand:target.brand,dataUrl:String(dataUrl),publicUrl:publicUrl,updatedAt:new Date().toISOString()};
          localStorage.setItem(LOGO_KEY,JSON.stringify(registry));
          writeAudit("Brand logo published","brand",normaliseBrandKey(target.brand),target.brand,target.note,(target.source==="device"?"Selected from device storage":"Captured with FOSCH's in-app camera")+" and published for all users.");
          page="adminDashboard";render();
        }catch(error){
          alert("The brand logo could not be published. Check that the latest Supabase public-media SQL migration has been run. "+String(error&&error.message||error));
          page="adminDashboard";render();
        }
      })();
    }
    page = "adminDashboard";
    render();
    return true;
  }
  function onPhotoFailed() {
    if (!pendingMedia) return false;
    pendingMedia = null;
    alert("No admin image was saved. You can try again.");
    if (authenticated) { page = "adminDashboard"; render(); }
    return true;
  }
  async function logout() {
    if (!authenticated) return;
    writeAudit("Admin sign-out", "", "", "", "", "Administrator session ended.");
    authenticated = false;
    editingRef = null;
    pendingMedia = null;
    try { await window.FOSCHCloud.signOut(); } catch (error) { console.warn("FOSCH sign-out warning:", error); }
    remoteAuditLog = [];
    contributions = contributions.filter(function (record) { return !record._foschRemote; });
    correctionReports = correctionReports.filter(function (record) { return !record._foschRemote; });
    page = "adminLogin";
    pageHistory = [];
    suppressNextHistoryPush = true;
    render();
  }

  window.FOSCHAdmin = {
    isAuthenticated: function () { return authenticated && !!window.FOSCHCloud && window.FOSCHCloud.isAdmin(); },
    isCapturingMedia: isCapturingMedia,
    attemptSignIn: signIn,
    dashboardMarkup: dashboardMarkup,
    onAppForeground: onAppForeground,
    refreshDashboard: refreshDashboard,
    editMarkup: editMarkup,
    renderProductMedia: renderProductMedia,
    renderOfficialProductPhotos: renderOfficialProductPhotos,
    setTab: setTab,
    approve: approve,
    retrySync: retrySync,
    reject: reject,
    remove: remove,
    edit: edit,
    saveEdit: saveEdit,
    backToDashboard: backToDashboard,
    captureOfficialPhoto: captureOfficialPhoto,
    uploadOfficialPhoto: uploadOfficialPhoto,
    captureBrandLogo: captureBrandLogo,
    uploadBrandLogo: uploadBrandLogo,
    onPhotoCaptured: onPhotoCaptured,
    onPhotoFailed: onPhotoFailed,
    logout: logout
  };
})();