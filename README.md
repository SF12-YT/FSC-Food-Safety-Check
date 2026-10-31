# FOSCH v0.10.13 — contribution, correction and analysis fixes

FOSCH is an independent, open-source Android prototype for browsing E-number references and looking up retail product barcodes. It is not a regulator-approved or medically authoritative food-safety database.

## v0.10.13 changes

- Placed the country-of-origin percentage in a compact field beside the Produced in / Made in country field.
- Nested Sugar beneath Carbohydrate using the same indented nutrient styling as the fat subcategories.
- Fixed nutrition information being discarded when the contribution form was saved after using the separate nutrition page. Saved values, including % DI per Serving, now carry through into product and correction drafts; an explicit removal remains respected.
- Correction draft cards now use the product name as the heading and show the barcode separately. Legacy drafts with the old combined barcode/title format remain readable.
- Correction drafts now preserve the newly entered brand and product name instead of re-canonicalising the correction back to the original catalogue spelling. The saved card clearly displays the proposed brand and notes the previous brand when changed.
- The water-only ingredient prompt is suppressed once a typical analysis has been saved.
- Improved analytical interval parsing for hyphen, en dash, em dash and “to” separators. pH intervals crossing the 6.5 reference boundary now explain which end falls below/inside the indicative range rather than giving only a generic uncertainty message. pH summaries no longer repeat the unit.

## v0.10.12 changes

- Rebranded the Android application and UI to **FOSCH** throughout, including the native JavaScript bridge, bundled data globals, local storage namespaces, legal pages and launcher label.
- Changed the Android namespace and application ID to `com.fosch.org` and incremented the Android version to `0.10.12` (`versionCode 23`). This package ID is intentionally different from the earlier prototype ID; Android treats it as a separate application and does not automatically migrate the old app's private data.
- Updated the concealed admin navigation sequence so its first ten taps are on **Food Safety Check (FOSCH)** inside Settings → About FOSCH. The nine final taps on the home-screen app logo are handled without making the logo focusable or applying a tap highlight.
- Added an optional country-of-origin percentage to product contributions. The percentage is stored and shown as entered, validated to 0–100, and is never inferred or estimated by the app.
- Expanded the separate optional nutrition page with trans, polyunsaturated and monounsaturated fat; nested fat subcategories; dietary fibre; cholesterol; and gluten nested beneath protein. Added a manually entered **% DI per Serving** column alongside per-serving and per-100 g values. FOSCH does not calculate missing %DI values.

## Updates in 0.9.0 (previous release)

- Removed global connectivity status from the user interface. A connection warning is shown only in context when an online-only action is attempted and cannot proceed.
- Added a first-run country selection with 195 country options. The choice is stored locally and reused for product lookup and contribution drafts; ordinary product screens no longer offer a market selector.
- Made System default theme read Android's current night-mode configuration and respond to UI-mode changes, with `matchMedia` as a fallback. The saved preference is applied in the HTML head before first paint and mirrored to native preferences so the native window and WebView use the selected appearance at startup.
- Dietary icons are now conservative and ingredient-specific: an icon is shown only when FOSCH has an explicit positive compatibility entry for that requirement. Unknown compatibility does not get a positive icon. Individual icons are shown independently for each requirement.
- Moved “Think we got it wrong?” to successful product lookup results only. It now opens a product correction draft rather than appearing on E-number reference pages.
- Made product evidence capture camera-only (no gallery/file chooser), compressed photos for local drafts, and connected scanner results back to the page. Contribution and correction drafts now support up to five captured photos; each preview is a 1:1 square thumbnail, while the stored image retains its original proportions.
- Added scanner flashlight controls and scanner rotation behaviour that locks orientation when Android Auto-rotate is disabled and follows sensor rotation when it is enabled.
- Kept SQLite product cache records compact and keyed by barcode and saved country code.
- Bundled 548 unique E-number codes/subentries, including historical entries and records with limited details. Wikipedia previews are shown only for entries with a linked article page; missing article links are omitted. Added an expandable source panel with official additive lookup/regulatory resources across major markets and international sources (EU, Australia/New Zealand, US, Canada, UK, Singapore, Japan, India, Thailand, China, South Africa, Brazil, Codex and JECFA). The panel clearly says that it is a source directory, not automatic per-additive verification.

## v0.10.0 changes

- Increased contrast for text-entry fields while preserving the flat, borderless control style; dropdowns have a subtle visible border, and unchecked boxes are slightly lighter/bounded while selected boxes remain solid without a tick.
- Saved product drafts are clickable and open a separate detail view. Users can edit a saved draft or look up its saved barcode without scanning the package again.
- Removed user-facing connectivity status, offline-index readiness and saved product-market labels. Internet warnings appear within the affected operation only, such as source-link opening, article previews and online product lookup.
- Added a first source-backed research dataset with official FAO/WHO JECFA assessment pages and relevant regulator references for selected colour additives and common additives (E100, E101, E102, E104, E110, E120, E122, E123, E124, E127, E129, E132, E133, E140, E141, E142, E143, E150a/c/d, E160a/b/d/e, E162, E163, E200, E202, E210, E211, E220, E300, E301, E310, E320, E322, E330, E331, E406, E407, E410, E412, E414, E415, E420, E440, E441, E471, E500, E501, E551, E621, E635, E950, E951, E955 and E960).
- Expanded the Colors-category swatches and names for E100–E199 catalogued entries, including both E170 subentries. Swatches are illustrative only; concentration, formulation, processing and pH can change the final shade. E170(ii) is described as colorless in solution and therefore has no colored swatch.
- Country/regulatory summaries do not claim a specific additive is permitted based only on the country’s source link. They explicitly say when an additive-by-additive legal check has not been completed.

## Research coverage and safety limits

The in-app researched summaries cover 57 selected additives and are deliberately additive-specific, with direct JECFA or regulator references. They are not yet a complete research review of all 548 bundled codes/subentries. JECFA ADIs are shown with the assessor and evaluation year where verified. ADI “not specified,” “no ADI allocated,” and “withdrawn” are distinguished; none is displayed as permission for unlimited intake. ADIs are long-term risk-assessment benchmarks and are not serving-size limits or a personal safety target. Several remaining catalogue entries still state that individual toxicology/allergen/ADI research is incomplete; the research repository links are discovery tools, not evidence that those entries have already been reviewed.

For v0.10.1, the Potential allergic reactions section is shown only when FOSCH contains an additive-specific or source-specific allergy claim with a direct, relevant citation. Unsupported generic disclaimers, negative allergy statements, and nonspecific intolerance/sensitivity descriptions are not displayed as allergy information. Current retained examples include documented carmine/cochineal and annatto reactions, source-dependent soy lecithin, rare reported reactions to guar or locust bean gum, and source-dependent alpha-gal concerns for carrageenan or mammalian gelatin. These are conditional, uncommon or evidence-limited where stated; they are not diagnoses or guarantees that an individual will react. Regulatory permissions and warning labels remain country-, formulation-, food-category- and use-level-specific.

## Important limitations

- The bundled index contains 548 entries assembled from Wikipedia’s E-number table plus specified current EU subentries. The list includes historical identifiers and sparse entries; this is a broad, source-backed index rather than a guarantee that every historical national or industry code is covered. Detailed regulatory status still needs to be checked against current official sources.
- Detailed external article previews and online product lookups require internet access. FOSCH does not show a global connectivity or offline-index readiness indicator; online failures are reported only within the affected action.
- The product lookup and online product-name suggestion source is Open Food Facts, a community-contributed database. FOSCH checks country tags and does not silently substitute another market's formulation. Matching country tags do not independently verify the ingredient list. Name suggestions are only suggestions; users must confirm and review product/variant details.
- Product contributions and correction reports are stored locally as unverified drafts. No FOSCH server, shared submission queue, admin review workflow or account service is connected yet.
- Account creation is not required by this prototype. No GPS or precise-location permission is requested.

## Product lookup and contributions

The local SQLite cache is keyed by barcode and country code. Online records are cached only when the product has a name, ingredient list and a country tag matching the saved market. The contribution form requires brand name, product name, package size as printed, and ingredient text; barcode capture is optional. It stores the market code internally for lookup matching, plus optional notes, structured typical-analysis data when supplied, and up to five compressed camera-captured product/label photos. The country name is not shown on product pages or draft cards. Photo thumbnails are square, but the actual source images are not cropped to square. Drafts are not published or transmitted.

## Build

GitHub Actions runs on Ubuntu 24.04 with JDK 17 and Gradle 9.6.0, using Android Gradle Plugin 9.4.0 and compile SDK 36. Run the `Build FOSCH Android APK` workflow manually or push to `main`. The build artifact is named `FOSCH-stable-signed-APK` when the stable signing secrets are configured; otherwise it is `FOSCH-debug-APK` and may not install over an APK from a prior runner.

The app uses internet only when the user opens external sources/articles or requests an online product lookup. It does not show a global connection or offline-index status indicator; a connectivity warning is scoped to the attempted online action. Barcode scanning and the bundled E-number index remain available offline.

## Catalogue attribution

The offline E-number index is derived in part from the public Wikipedia article [E number](https://en.wikipedia.org/wiki/E_number) (`e_numbers.tsv`) and augmented with current EU subentries from the European Commission Union list and the Belgian Federal Public Service public additive list (`e_number_official_subentries.tsv`). FOSCH links directly only entries for which the source table provides an article link. Wikipedia content is maintained independently from FOSCH.


## Regulatory-source limitations

The reference pages now provide an expandable list of official regulatory/scientific databases for several major regions, plus Codex and FAO/WHO JECFA. The links are a starting point for current safety, permitted-use and dietary-source research. They do **not** mean that every E-number has been cross-checked against every country, food category and limit. Those additive-by-additive jurisdictional checks require a separately maintained, source-cited dataset. Dietary labels such as vegan, halal and kosher can depend on production method, source material and certification; FOSCH must not infer them solely from an E-number.


## Stable APK updates (important)

GitHub-hosted runners do not preserve Android's automatically generated debug signing key between runs. An APK produced by `assembleDebug` on a fresh runner can therefore fail to update an APK from an earlier run with a package/signature conflict.

For repeatable in-place updates, configure these four **GitHub Actions repository secrets** using a keystore that you generate and keep under your control: `FOSCH_KEYSTORE_BASE64`, `FOSCH_KEYSTORE_PASSWORD`, `FOSCH_KEY_ALIAS`, and `FOSCH_KEY_PASSWORD`. The workflow then builds the `release` APK with that stable key. Do not commit the keystore or passwords to the repository. A typical one-time generation command on a computer with Java installed is `keytool -genkeypair -v -keystore fosch-release.jks -alias fosch-key -keyalg RSA -keysize 2048 -validity 10000`. Keep the keystore and passwords private. Base64-encode the `.jks` file to populate `FOSCH_KEYSTORE_BASE64` (Linux: `base64 -w 0 fosch-release.jks`; macOS: `base64 < fosch-release.jks | tr -d '\n'`). Set the alias to `fosch-key` if you use that example. If those secrets are absent, the workflow builds a debug APK as a development fallback and reports it as `FOSCH-debug-APK`.

The first stable-key APK may not update an already-installed APK that was signed by an earlier runner's temporary debug key. Android cannot update across different signing certificates, so that older build may need to be uninstalled once; this removes its local app data. After installing the stable-key build, future APKs signed with the same key can update in place. Back up any local drafts you need before uninstalling.

## v0.9.0 changes (previous release)

- Soft, slightly lighter field/surface fills with no outlines or borders; checkbox selection is solid with no checkmark.
- Extra spacing between E-number badges and additive names.
- Additive detail pages show the `Color` row only in the E100–E199 Colors category.
- Product ingredient lists are comma-separated and link recognized E-number / INS / bare-code tokens to the matching additive detail page; linked tokens display using `INS` notation.
- Bottom navigation is hidden and guarded until a country/market is selected.
- Home Back handling clears stale navigation history, shows the double-press toast on the first valid press, and exits on a second press within two seconds.
- Startup theme preference is read from native Android storage before the WebView's first paint; the native window has a non-white startup background to reduce the flash.


### Additional source review (2026-10-27)
- The research bundle now includes further JECFA-backed summaries for sorbic acid (E200), benzoic acid (E210), ascorbic acid (E300), carrageenan (E407), xanthan gum (E415), pectins (E440), acesulfame K (E950), aspartame (E951), sucralose (E955) and steviol glycosides (E960).
- The United States E127 record is date-stamped: FDA revoked FD&C Red No. 3 food authorization with a food-use effective date of 2027-01-15; as checked on 2026-10-27, the date is future. Regulatory timing is not a clinical toxicology outcome.
- These curated records remain a subset of the catalogue. Other entries continue to show an explicit lack of verified additive-specific data instead of invented use, allergy or intake claims. National status still requires additive-, food-category-, maximum-level- and date-specific verification.


## v0.10.1 changes

- Removed the blanket allergy paragraph from additive details. The section now appears only when a specific reaction/group has relevant source evidence attached to that entry.
- Removed generic nut-allergy disclaimers and unsupported “no known allergy” statements from the researched allergy dataset. No allergy section is shown when no qualifying evidence is supplied.
- Added source-linked, cautious summaries for documented carmine/cochineal and annatto reactions; soy-derived lecithin in people with soy allergy; rare gum-specific reactions involving locust bean gum (E410) and guar gum (E412); and source-dependent alpha-gal considerations for carrageenan (E407) and mammalian gelatin (E441).
- Kept sulfite sensitivity, general intolerance and nonspecific symptom reports out of the allergy section.
- Bumped Android version to 0.10.1 (versionCode 12).


## v0.10.3 changes (previous release)

- Added brand-name autocomplete after two characters, reusing known brand spellings by normalized exact match.
- Added product-name suggestions with spelling/word-variation matching (including common US/UK spellings such as flavor/flavour). Approximate matches are suggestions; exact matches reuse a stable market/brand/product key. Same barcode + size + product identity drafts are not duplicated; different size/barcode variants remain separately recorded under one product key.
- Added a free-text package-size field stored and displayed as written (for example `500 mL`, `16 oz`, `1 lb`, or `6 × 100 g`). FOSCH does not normalize, convert or round the declared size.
- Added a contextual prompt when `Water` is the only ingredient, with an optional separate typical/mineral-analysis field. Such values remain unverified and are not treated as ingredients.
- Added ingredient-name linking for case-insensitive additive names (including `Citric acid` → E330) as well as E-number / INS code notation and punctuation variations such as `E-330`.
- Added lazily loaded, separate per-market product-index seed files for all 195 country options. The files are empty scaffolds until a reviewed FOSCH database is populated. Local product indexes and caches are scoped to the selected market.
- Expanded the SQLite barcode cache to retain brand and original package-size text, with a market + update-time index. Existing cache databases migrate without dropping prior data.
- Bumped Android version to `0.10.3` (versionCode 14).

### Limitations

FOSCH still has no shared product database, account system or connected admin review service. Product-name and brand suggestions cover only locally available records (saved local drafts, the current market's local index, and cached barcode lookups). Market-specific asset files are empty scaffolds, not a populated FOSCH product catalogue. Online product-name suggestions use Open Food Facts when running in the installed Android app; saved/local matches remain available when online lookup fails. A full Gradle/Android build and device test must be confirmed by GitHub Actions or a local Android build environment.


## v0.10.5 changes

- Product-name suggestions no longer require a barcode scan. Local-market matches appear immediately; after two characters and a short debounce, the Android app also searches Open Food Facts for suggestions tagged to the selected country. Online results are capped, time-limited, and filtered again against country tags before display. Network failures appear in the autocomplete only; users can still submit manually.
- Brand and package size are required contribution fields. Brand autocomplete starts after two characters. Product-name matching tolerates common spelling variants and shorter descriptions, but approximate matches remain suggestions rather than automatic merges.
- Ingredient-vagueness notices (for example, an unspecified vegetable oil source) are shown on Product Lookup only, not in the contribution form. FOSCH preserves the exact label text and does not guess the source.
- “Think we got it wrong?” opens an editable, prefilled product-correction form. Corrections are saved as local correction drafts rather than duplicate product contributions and do not change a shared record.
- Typical/mineral analysis opens a separate page with individual checkboxes for the results actually available, including pH, mineral values, microbiological results, testing date, laboratory, source and notes. pH stays in pH units, chemical/mineral readings are recorded in mg/L, and microbiological results are recorded as reported. Values remain unverified local draft information.
- Recent Contributions / Saved Drafts now opens a dedicated page instead of listing all drafts on the main contribution page.
- Android version bumped to `0.10.5` (versionCode 16).

### Name-search implementation note

Open Food Facts' legacy `cgi/search.pl` full-text route is used for this prototype's autocomplete because it supports text queries and country filters ([API overview](https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/), [search route](https://openfoodfacts.github.io/documentation/docs/Product-Opener/v2/search/get-search/)). Requests are debounced and bounded by timeouts; results are filtered by country tags again on-device and shown only as suggestions. The shared FOSCH catalogue, contribution server and admin-review workflow are not yet implemented. The 195 market-index assets remain empty scaffolds.


## v0.10.6 changes

- Typical-analysis readings use a text keyboard and allow numeric values, decimal separators, comparison signs (`<`, `>`, `≤`, `≥`), approximation marks (`≈`, `~`) and ranges (for example `6-8`). Other characters are filtered while typing.
- Added an indicative, source-linked water-quality summary for reported pH, iron, chloride, sulfate, fluoride, nitrate and selected microbiological results. It distinguishes aesthetic reference points from health-based comparisons, uses Australian Drinking Water Guidelines as the initial reference set, warns when market-specific thresholds are not validated, and explicitly states that this is not a potable-water certification.
- Renamed the old `Sulfur` typical-analysis field to `Sulfate`, while migrating old saved `sulfur` data into the new field on read.
- Reworked the bottom taskbar to place Scan first, then Home, E-numbers, Contribute and Settings. Updated icon weight, used a solid Settings icon, and refined the Scan-circle positioning.
- Made Home buttons and bottom-tab Home navigation route directly to the Home page instead of traversing page history.
- Package-size duplicate matching now ignores spaces and letter case (for example, `5L` and `5 L` compare equally) while the original declared quantity remains stored and displayed without conversion.
- Added name normalisation for punctuation and spacing differences in additive names, such as `Riboflavin 5 Phosphate` and `Riboflavin-5-Phosphate`, while preserving the label text shown in ingredient lists.
- Added a post-submit confirmation popup for ingredient wording that may be an abbreviated additive name. Multiple candidates can be selected; otherwise, the user can keep the wording as printed. The prompt is not shown while typing.
- Added searchable category and favourites filters plus persistent per-device additive favourites.
- Android version bumped to `0.10.6` (versionCode 17).

### Water-quality assessment limits

The summary is an educational screening aid only. Its selected Australian guideline reference points do not constitute a local compliance assessment outside Australia, and incomplete results cannot establish that water is safe to drink. Confirm all interpretations against the actual laboratory method, reporting units, sample volume, current local guidelines and water authority advice.


## v0.10.7 changes

- Added optional `Produced in / Made in` origin field to product contribution drafts, preserving what is printed on the label rather than inferring origin from the market selection.
- Added optional nutrition information fields for servings, serving size, energy, protein, total fat, saturated fat, carbohydrate, sugars and sodium. Each nutrient can be recorded both per serving and per 100 g. User-entered values and units are kept as printed; FOSCH does not convert, estimate or fill missing nutrition entries.
- Preserved production-origin and nutrition data in local product drafts and market indexes, with display on saved-draft details and when those local records are surfaced in Product Lookup.
- Enlarged the Scan taskbar button and barcode icon slightly.
- Category/favourites filters on Home now remain hidden until the search field is tapped/focused, and close when tapping elsewhere.
- Made dark-mode background pure black (#000000) with neutral dark-grey surfaces and updated Android startup/status/navigation surfaces to avoid a blue-tinted flash. The light theme is unchanged.
- Bumped Android version to `0.10.7` (`versionCode 18`).

### Data-entry note

Origin and nutrition fields are optional because availability varies by package and product. Nutrition values are captured as label text, and values displayed in Product Lookup are community/source-provided rather than independently verified by FOSCH.


## v0.10.8 changes

- Moved optional nutrition entry to its own page, opened only by an Add/Edit Nutrition Information button in the contribution form. Existing nutrition details remain attached to the product/draft.
- Enlarged the Scan taskbar control and let it float above the taskbar edge.
- Matched the E-number chip to the chemical-name result background and the favourite star control to the app background.
- Added chemical-name-first Wikipedia discovery. FOSCH searches Wikipedia by the additive's canonical chemical name, prefers a relevant Uses / Applications / Food preservative section, shows a short (maximum 25-word) preview under Uses, and caches article/section previews locally for 30 days.
- Added EFSA Journal on Wiley Online Library as a research source and refreshed selected additive summaries against newer EFSA opinions: curcumin (E100), carmines (E120), sorbates (E200/E202), sulfites (E220), lecithins (E322), carrageenan (E407), glutamates (E621), and sucralose (E955).
- Updated Android version to `0.10.8` (`versionCode 19`).

### Research scope and qualifications

The research catalogue contains 57 curated additive profiles, not a complete current legal and toxicological review of every catalogue code or every country. This release refreshes selected profiles with additive-specific EFSA Journal sources; remaining entries still require individual verification. ADIs are distinguished by evaluator and date. An ADI is a chronic exposure benchmark, not a per-serving allowance or recommended target. Regulatory permissions remain country-, food-category-, and condition-specific. EFSA's scientific opinion is not itself a national authorisation decision. Wikipedia is used only as an auxiliary short Uses-section preview; it does not substantiate toxicology, ADI or regulatory claims.

Primary research sites include `efsa.onlinelibrary.wiley.com` (EFSA Journal), `efsa.europa.eu`, the European Commission / EUR-Lex, FAO/WHO JECFA, FSANZ, FDA and relevant national food regulators.


EFSA state-of-play reference used in this research pass: as of 10 September 2026, the European Commission reported 315 pre-2009 additives due for re-evaluation, 137 EFSA opinions covering 245 individual additives, and 70 additives still to be re-evaluated. This is the EU re-evaluation programme status, not a count of global approvals.


## v0.10.9 changes

- Moved nutrition information to its own optional page, opened only when the contributor presses Add/Edit Nutrition Information; nutrition is no longer presented as an inline contribution section.
- Enlarged the floating Scan taskbar control to 72 px and kept it protruding above the taskbar.
- Matched the E-number code chip to the chemical-name result background and the favourite star control to the app background.
- Strengthened Wikipedia discovery by searching the canonical chemical name first, also trying available chemical-name aliases, alternative names and any indexed article title; a static E-number link is only a fallback. The preview chooses a relevant Uses / Applications / Food preservative section when one exists and displays a short extract under Uses.
- Added additive-specific EFSA Journal Wiley sources and refreshed E102 (Tartrazine), E110 (Sunset Yellow FCF), E322 (lecithins) and E950 (acesulfame K) from primary scientific opinions. Clarified EFSA/JECFA ADI differences, current-use exposure conclusions, impurity recommendations and country-specific limitations.
- Added or refreshed EFSA Journal Wiley search access as a standard research source.
- Updated Android version to `0.10.9` (`versionCode 20`).

### Research scope and qualifications

The curated research file contains 57 selected additive profiles, not a complete scientific and regulatory reassessment of all bundled E-number codes or all countries. This release specifically refreshes four additional/targeted profiles and adds source links; it does not make unresearched profiles complete. ADIs remain assessor- and date-specific. An EFSA opinion informs EU risk management but is not itself the final authorisation decision; current permitted food categories and maximum levels must be checked against the current EU Union List and the selected country's own law. Wikipedia previews are an auxiliary uses reference only and are not used as toxicology, ADI or regulatory evidence.

## Licensing and third-party notices

Original FOSCH source code is licensed under the MIT License (see `LICENSE`). The licence does not automatically cover the FOSCH project name or logo, third-party datasets, Wikipedia article text, Open Food Facts product data, product images, or other third-party marks and materials. See `THIRD_PARTY_NOTICES.md` and the in-app Legal page for attribution notes.


## FOSCH Android application ID

The FOSCH package/application ID is `com.fosch.org`. Because this differs from the earlier FSC prototype ID (`com.fsc.foodsafetycheck`), Android installs it as a separate application; existing FSC installations cannot be updated in place and their app-private data is not automatically transferred.
