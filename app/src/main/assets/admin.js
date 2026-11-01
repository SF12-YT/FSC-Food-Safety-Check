(function () {
  "use strict";

  const DEFAULT_EMAIL = "admin@admin.org";
  const DEFAULT_PASSWORD = "admin";
  const AUDIT_KEY = "fosch-admin-audit";
  const LOGO_KEY = "fosch-brand-logos";
  let authenticated = false;
  let activeTab = "submissions";
  let editingRef = null;
  let pendingMedia = null;
  let auditLog = [];
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
      localStorage.setItem("fosch-contrib", JSON.stringify(contributions));
      localStorage.setItem("fosch-corrections", JSON.stringify(correctionReports));
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
  }
  function getItems() {
    ensureRecordIds();
    const items = [];
    (Array.isArray(contributions) ? contributions : []).forEach(function (record) {
      items.push({ kind: "product", id: String(record.id), record: record });
    });
    (Array.isArray(correctionReports) ? correctionReports : []).forEach(function (record) {
      items.push({ kind: "correction", id: String(record.id), record: record });
    });
    return items.sort(function (a, b) {
      return String(b.record.date || "").localeCompare(String(a.record.date || ""));
    });
  }
  function findItem(kind, id) {
    const records = kind === "product" ? contributions : correctionReports;
    if (!Array.isArray(records)) return null;
    const index = records.findIndex(function (record) { return String(record.id || "") === String(id || ""); });
    return index < 0 ? null : { kind: kind, id: String(id), record: records[index], index: index, records: records };
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
  function brandLogoRegistry() {
    try {
      const value = JSON.parse(localStorage.getItem(LOGO_KEY) || "{}");
      return value && typeof value === "object" && !Array.isArray(value) ? value : {};
    } catch (ignored) { return {}; }
  }
  function logoFor(brand) {
    const key = normaliseBrandKey(brand);
    if (!key) return null;
    const entry = brandLogoRegistry()[key];
    return entry && String(entry.dataUrl || "").startsWith("data:image/") ? entry : null;
  }
  function recordPhotos(record) {
    if (!record) return [];
    const stored = Array.isArray(record.officialPhotos) ? record.officialPhotos : [];
    const latest = String(record.officialPhoto || "");
    const ordered = latest.startsWith("data:image/")
      ? [latest].concat(stored.filter(function (v) { return v !== latest; }))
      : stored;
    const seen = [];
    ordered.forEach(function (value) {
      if (typeof value === "string" && value.startsWith("data:image/") && seen.indexOf(value) < 0 && seen.length < 5) seen.push(value);
    });
    return seen;
  }
  function renderProductMedia(product) {
    if (!product) return "";
    const brand = String(product.brand || "").trim();
    const logo = logoFor(brand);
    const photos = recordPhotos(product);
    let html = "";
    if (logo) html += "<div class=\"product-media-row\"><img class=\"product-brand-logo\" src=\"" + esc(logo.dataUrl) + "\" alt=\"" + esc(brand) + " brand logo\"><b>" + esc(brand) + "</b></div>";
    photos.slice(0, 5).forEach(function (photo, index) {
      html += "<img class=\"product-official-photo\" src=\"" + esc(photo) + "\" alt=\"Official product photo " + (index + 1) + "\">";
    });
    return html;
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
    const status = isApproved(record) ? "Approved locally" : String(record.status || "Pending review").replace(/-/g, " ");
    const id = safeId(item.id);
    const type = item.kind;
    const brandCall = brand ? "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.captureBrandLogo(decodeURIComponent('" + urlArg(brand) + "'))\">Capture brand logo</button><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.uploadBrandLogo(decodeURIComponent('" + urlArg(brand) + "'))\">Upload brand logo</button>" : "";
    return "<article class=\"admin-submission-card\">" +
      "<div class=\"admin-submission-top\"><div><div class=\"admin-submission-title\">" + esc(title) + "</div>" +
      (brand ? "<div class=\"admin-submission-meta\">" + esc(brand) + "</div>" : "") +
      "<div class=\"admin-submission-meta\">" + esc(type === "product" ? "Product contribution" : "Correction report") + " · " + esc(record.barcode || (record.suggestedProduct && record.suggestedProduct.barcode) || "No barcode") + "</div>" +
      "<div class=\"admin-submission-meta\">" + esc(record.date ? new Date(record.date).toLocaleString() : "Date not recorded") + "</div></div>" +
      "<span class=\"admin-status " + (isApproved(record) ? "approved" : "") + "\">" + esc(status) + "</span></div>" +
      imagePreview(record) +
      "<div class=\"admin-submission-meta\">" + esc(record.ingredients || (record.suggestedProduct && record.suggestedProduct.ingredients) || record.message || "No ingredient details supplied") + "</div>" +
      "<div class=\"admin-action-grid\">" +
      "<button type=\"button\" class=\"primary\" onclick=\"FOSCHAdmin.approve('" + type + "','" + id + "')\">" + (isApproved(record) ? "Re-approve" : "Approve") + "</button>" +
      "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.edit('" + type + "','" + id + "')\">Edit submission</button>" +
      "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.captureOfficialPhoto('" + type + "','" + id + "')\">Capture official photo</button>" +
      "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.uploadOfficialPhoto('" + type + "','" + id + "')\">Upload image from device</button>" +
      brandCall +
      "<button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.remove('" + type + "','" + id + "')\">Delete</button>" +
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
        "<div class=\"admin-brand-name\">" + esc(item.name) + "<div class=\"admin-submission-meta\">" + (logo ? "Logo saved on this device" : "No logo added") + "</div></div>" +
        "</div><div class=\"admin-action-grid\"><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.captureBrandLogo(decodeURIComponent('" + urlArg(item.name) + "'))\">Capture " + (logo ? "replacement logo" : "logo") + "</button><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.uploadBrandLogo(decodeURIComponent('" + urlArg(item.name) + "'))\">Upload from device</button></div></article>";
    }).join("");
  }
  function auditMarkup() {
    if (!auditLog.length) return "<section class=\"panel empty\"><h3>No admin activity yet</h3><p class=\"muted\">Sign-ins, approvals, edits, deletions and media changes will be recorded here.</p></section>";
    return auditLog.map(function (entry) {
      return "<article class=\"admin-audit-row\"><b>" + esc(entry.action) + (entry.recordLabel ? " · " + esc(entry.recordLabel) : "") + "</b>" +
        "<small>" + esc(entry.at ? new Date(entry.at).toLocaleString() : "Unknown time") + " · " + esc(entry.actor || DEFAULT_EMAIL) + "</small>" +
        (entry.reason ? "<small>Reason: " + esc(entry.reason) + "</small>" : "") +
        (entry.details ? "<small>" + esc(entry.details) + "</small>" : "") +
        "</article>";
    }).join("");
  }
  function dashboardMarkup() {
    if (!authenticated) return "<section class=\"panel\"><p>Admin session expired. Please sign in again.</p></section>";
    const items = getItems();
    const submissions = items.length ? items.map(submissionCard).join("") : "<section class=\"panel empty\"><h3>No local submissions</h3><p class=\"muted\">Product contributions and correction drafts saved on this device will appear here.</p></section>";
    const brandMarkup = "<h2>Brand logos</h2><p class=\"muted\">Administrators can capture a logo with the in-app camera or select an image from this device. Images are stored locally and shown beside matching brand names on this device.</p>" + brandCardsMarkup();
    const content = activeTab === "activity" ? auditMarkup() : "<p class=\"muted\">" + items.length + " local record" + (items.length === 1 ? "" : "s") + " available for review.</p>" + submissions + brandMarkup;
    return "<div class=\"admin-head\"><div><h1>Admin dashboard</h1><div class=\"admin-submission-meta\">Signed in as " + esc(DEFAULT_EMAIL) + "</div></div><button type=\"button\" class=\"secondary\" onclick=\"FOSCHAdmin.logout()\">Sign out</button></div>" +
      "<div class=\"admin-tabs\"><button type=\"button\" class=\"" + (activeTab === "submissions" ? "primary" : "secondary") + "\" onclick=\"FOSCHAdmin.setTab('submissions')\">Submissions</button><button type=\"button\" class=\"" + (activeTab === "activity" ? "primary" : "secondary") + "\" onclick=\"FOSCHAdmin.setTab('activity')\">Activity log (" + auditLog.length + ")</button></div>" +
      content +
      "<div class=\"warning\">Local prototype only: this queue is not shared with other devices. Approval updates this device's local product index only. Admin credentials are embedded in the public client and the local audit log can be modified by anyone with device access. Do not use this as production authentication or moderation infrastructure.</div>";
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
        "<label for=\"adminEditOrigin\">Produced in / Made in</label><input id=\"adminEditOrigin\" class=\"field\" value=\"" + esc(product.producedIn || "") + "\">"
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
  function signIn(event) {
    if (event && event.preventDefault) event.preventDefault();
    const email = String((document.getElementById("adminEmail") || {}).value || "").trim().toLowerCase();
    const password = String((document.getElementById("adminPass") || {}).value || "");
    const status = document.getElementById("adminLoginStatus");
    if (email === DEFAULT_EMAIL && password === DEFAULT_PASSWORD) {
      authenticated = true;
      activeTab = "submissions";
      writeAudit("Admin sign-in", "", "", "", "", "Temporary local prototype account.");
      page = "adminDashboard";
      pageHistory = [];
      suppressNextHistoryPush = true;
      render();
      return;
    }
    if (status) status.textContent = "The email address or password is incorrect.";
    writeAudit("Failed admin sign-in", "", "", "", "", "An unsuccessful local sign-in attempt was made.");
  }
  function setTab(tab) {
    if (!requireAdmin()) return;
    activeTab = tab === "activity" ? "activity" : "submissions";
    page = "adminDashboard";
    render();
  }
  function approve(kind, id) {
    if (!requireAdmin()) return;
    const item = findItem(kind, id);
    if (!item) { alert("This submission could not be found."); return; }
    const note = prompt("Optional review note for this approval:", "Approved after review");
    if (note === null) return;
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
    writeAudit("Submission approved", kind, id, itemLabel(item), String(note || "").trim(), "Marked approved on this device only; no shared record was changed.");
    page = "adminDashboard";
    render();
  }
  function remove(kind, id) {
    if (!requireAdmin()) return;
    const item = findItem(kind, id);
    if (!item) { alert("This submission could not be found."); return; }
    const reason = askReason("deleting this submission");
    if (reason === null) return;
    if (!confirm("Delete \"" + itemLabel(item) + "\" from this device? This cannot be undone.")) return;
    item.records.splice(item.index, 1);
    if (!persistRecords()) return;
    writeAudit("Submission deleted", kind, id, itemLabel(item), reason, "Removed from this device's local submission list.");
    page = "adminDashboard";
    render();
  }
  function edit(kind, id) {
    if (!requireAdmin()) return;
    if (!findItem(kind, id)) { alert("This submission could not be found."); return; }
    editingRef = { kind: kind, id: String(id) };
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
      if (item.kind === "correction") record.suggestedProduct = product;
      Object.assign(record, { brand: product.brand, name: product.name, barcode: product.barcode, sizeLabel: product.sizeLabel, ingredients: product.ingredients });
    } else {
      record.target = String((document.getElementById("adminEditTarget") || {}).value || "").trim();
      record.message = String((document.getElementById("adminEditMessage") || {}).value || "").trim();
      record.source = String((document.getElementById("adminEditSource") || {}).value || "").trim();
    }
    record.adminEditedAt = new Date().toISOString();
    record.adminEditedBy = DEFAULT_EMAIL;
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
      product.officialPhotos = [latestPhoto].concat(previousPhotos.filter(function (photo) {
        return typeof photo === "string" && photo.startsWith("data:image/") && photo !== latestPhoto;
      })).slice(0, 5);
      product.officialPhoto = latestPhoto;
      if (target.kind === "correction" && record.suggestedProduct) record.suggestedProduct = product;
      if (!persistRecords()) return true;
      if (target.kind === "product" && isApproved(record)) saveMarketProduct(record);
      if (target.kind === "correction" && isApproved(record) && record.suggestedProduct) saveMarketProduct(Object.assign({}, product, { country: product.country || record.country || marketCountry }));
      writeAudit("Official product photo added", target.kind, target.id, itemLabel(item), target.note, (target.source === "device" ? "Selected from device storage" : "Captured with FOSCH's in-app camera") + " and stored locally.");
    } else if (target.type === "brandLogo") {
      const registry = brandLogoRegistry();
      registry[normaliseBrandKey(target.brand)] = { brand: target.brand, dataUrl: String(dataUrl), updatedAt: new Date().toISOString() };
      try { localStorage.setItem(LOGO_KEY, JSON.stringify(registry)); }
      catch (error) { alert("The logo could not be stored. Free some device storage and try again."); return true; }
      writeAudit("Brand logo added or replaced", "brand", normaliseBrandKey(target.brand), target.brand, target.note, (target.source === "device" ? "Selected from device storage" : "Captured with FOSCH's in-app camera") + " and stored locally.");
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
  function logout() {
    if (!authenticated) return;
    writeAudit("Admin sign-out", "", "", "", "", "Administrator session ended.");
    authenticated = false;
    editingRef = null;
    pendingMedia = null;
    page = "adminLogin";
    pageHistory = [];
    suppressNextHistoryPush = true;
    render();
  }
  window.FOSCHAdmin = {
    isAuthenticated: function () { return authenticated; },
    isCapturingMedia: isCapturingMedia,
    attemptSignIn: signIn,
    dashboardMarkup: dashboardMarkup,
    editMarkup: editMarkup,
    renderProductMedia: renderProductMedia,
    setTab: setTab,
    approve: approve,
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