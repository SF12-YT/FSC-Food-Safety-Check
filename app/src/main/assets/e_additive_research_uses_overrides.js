(function () {
  "use strict";

  const updates = {
    "E101a": {
      "use": "Older E-number references associate E101a with riboflavin-5′-phosphate and a yellow-to-orange-yellow colouring role. JECFA confirms the closely related sodium phosphate form as a food colour under INS 101(ii). That JECFA record supports the colour function of the sodium form; it does not establish E101a as a separate current INS identity.",
      "sources": [
        {
          "label": "FAO/WHO JECFA — Riboflavin 5′-phosphate sodium (INS 101(ii))",
          "url": "https://apps.who.int/food-additives-contaminants-jecfa-database/Home/Chemical/4093",
          "note": "Identifies the sodium phosphate form as INS 101(ii) and gives its functional class as colour. Used as corroboration of the phosphate-riboflavin colour function, not as proof that legacy E101a is a separate current identity."
        }
      ]
    },
    "E106": {
      "use": "Riboflavin-5′-phosphate sodium has a yellow-to-orange-yellow colouring function. JECFA lists the sodium phosphate form as INS 101(ii) and classifies it as a colour; E106 is retained here as a legacy catalogue label rather than treated as a separate current INS identity.",
      "sources": [
        {
          "label": "FAO/WHO JECFA — Riboflavin 5′-phosphate sodium (INS 101(ii))",
          "url": "https://apps.who.int/food-additives-contaminants-jecfa-database/Home/Chemical/4093",
          "note": "JECFA identifies the sodium salt and assigns the functional class colour."
        }
      ]
    },
    "E111": {
      "use": "Orange GGN was used historically as a synthetic orange food colour. This is a historical purpose description: an EU directive listed E111 among colouring matters deleted from the permitted list from 1 January 1977, with marketing of foods containing those colours prohibited from 1 January 1978.",
      "sources": [
        {
          "label": "EUR-Lex — Council Directive 76/399/EEC",
          "url": "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=celex%3A31976L0399",
          "note": "Official EU legislation names E111 Orange GGN as a colouring matter and records its removal from the permitted list."
        }
      ]
    },
    "E126": {
      "use": "Ponceau 6R was used historically as a synthetic red food colour. This is a historical purpose description: an EU directive listed E126 among colouring matters deleted from the permitted list from 1 January 1977, with marketing of foods containing those colours prohibited from 1 January 1978.",
      "sources": [
        {
          "label": "EUR-Lex — Council Directive 76/399/EEC",
          "url": "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=celex%3A31976L0399",
          "note": "Official EU legislation names E126 Ponceau 6R as a colouring matter and records its removal from the permitted list."
        }
      ]
    },
    "E128": {
      "use": "Red 2G was a synthetic red food colour historically used in certain processed-meat products. The EU suspension regulation specifically identifies breakfast sausages and burger meat as the foods in which it had previously been permitted. This records its historical colouring purpose, not a current-use recommendation.",
      "sources": [
        {
          "label": "EUR-Lex — Commission Regulation (EC) No 884/2007",
          "url": "https://eur-lex.europa.eu/eli/reg/2007/884/oj",
          "note": "Official EU text identifies the former food-colouring role of Red 2G in breakfast sausages and burger meat and records suspension of its use."
        }
      ]
    },
    "E161i": {
      "use": "The documented application for citranaxanthin in the cited reference is as a pigment in feed for laying hens. This is an animal-feed use, not a colouring agent added directly to human food.",
      "sources": [
        {
          "label": "UK legislation — The Feeding Stuffs Regulations 1995",
          "url": "https://www.legislation.gov.uk/uksi/1995/1412/made",
          "note": "The colourant schedule lists E161i citranaxanthin for laying hens; this supports an animal-feed application, not direct food use."
        }
      ]
    },
    "E161j": {
      "use": "The documented application for astaxanthin in the cited reference is as a pigment in feed for salmon and trout. This is an animal-feed use that affects animal pigmentation, not a colouring agent added directly to human food.",
      "sources": [
        {
          "label": "UK legislation — The Feeding Stuffs Regulations 1995",
          "url": "https://www.legislation.gov.uk/uksi/1995/1412/made",
          "note": "The colourant schedule lists E161j astaxanthin for salmon and trout feed; this supports an animal-feed application, not direct food use."
        }
      ]
    },
    "E265": {
      "use": "Dehydroacetic acid functions as a preservative. A specific food-technology example in US food-additive regulations is its preservative role for cut or peeled squash. This describes the technological purpose and a documented example, not a claim of universal use or authorisation.",
      "sources": [
        {
          "label": "US Code of Federal Regulations — 21 CFR §172.130, Dehydroacetic acid",
          "url": "https://www.govinfo.gov/content/pkg/CFR-2025-title21-vol3/xhtml/CFR-2025-title21-vol3-sec172-130.html",
          "note": "The provision describes dehydroacetic acid and/or its sodium salt as a preservative for cut or peeled squash."
        }
      ]
    },
    "E266": {
      "use": "Sodium dehydroacetate functions as a preservative. A specific food-technology example in US food-additive regulations is its preservative role for cut or peeled squash. This describes the technological purpose and a documented example, not a claim of universal use or authorisation.",
      "sources": [
        {
          "label": "US Code of Federal Regulations — 21 CFR §172.130, Dehydroacetic acid",
          "url": "https://www.govinfo.gov/content/pkg/CFR-2025-title21-vol3/xhtml/CFR-2025-title21-vol3-sec172-130.html",
          "note": "The provision explicitly includes the sodium salt and describes the preservative role for cut or peeled squash."
        }
      ]
    },
    "E306": {
      "use": "Tocopherol-rich extract functions as an antioxidant. It helps slow oxidation in foods, particularly fat-containing products, thereby helping protect flavour and quality during storage. Codex identifies mixed tocopherols concentrate (INS 306) as an antioxidant.",
      "sources": [
        {
          "label": "FAO/Codex — Tocopherols (INS 306)",
          "url": "https://www.fao.org/4/y0474s/y0474s6x.htm",
          "note": "Codex identifies mixed tocopherols concentrate (INS 306) and assigns the function antioxidant."
        }
      ]
    },
    "E387": {
      "use": "Oxystearin is used as an antifoaming agent, crystallisation inhibitor, release agent and sequestrant. Its documented food-technology roles include helping control crystallisation in vegetable oils and acting as a release agent in oils and shortenings.",
      "sources": [
        {
          "label": "FAO/Codex — Oxystearin (INS 387)",
          "url": "https://www.fao.org/4/Y0474S/y0474s4z.htm",
          "note": "Codex lists the four functional roles: antifoaming agent, crystallisation inhibitor, release agent and sequestrant."
        }
      ]
    },
    "E408": {
      "use": "Baker’s yeast glycan can function as an emulsifier, stabiliser or thickener, and texturiser. These effects help maintain the consistency, structure and texture of food.",
      "sources": [
        {
          "label": "US FDA — Substances Added to Food: Baker’s yeast glycan",
          "url": "https://hfpappexternal.fda.gov/scripts/fdcc/index.cfm?id=BAKERYEASTGLYCAN&set=FoodSubstances",
          "note": "FDA’s technical-effect listing identifies emulsifier/emulsifier salt, stabiliser or thickener, and texturiser."
        }
      ]
    },
    "E441": {
      "use": "Gelatin is primarily used as a gelling agent, forming a gel that gives foods structure and texture. JECFA also lists stabilising, emulsifying and crystallisation-inhibiting functions.",
      "sources": [
        {
          "label": "FAO/WHO JECFA — Edible gelatin monograph",
          "url": "https://www.fao.org/fileadmin/user_upload/jecfa_additives/docs/Monograph1/Additive-171.pdf",
          "note": "JECFA lists gelatin’s functional uses as stabiliser, gelling agent, emulsifying agent and crystallisation inhibitor."
        }
      ]
    },
    "E487": {
      "use": "Sodium lauryl sulfate functions as an emulsifier and surface-active agent. These properties help ingredients such as fats and water-based components mix or remain dispersed in certain food preparations.",
      "sources": [
        {
          "label": "US FDA — Substances Added to Food: Sodium lauryl sulfate",
          "url": "https://hfpappexternal.fda.gov/scripts/fdcc/index.cfm?id=SODIUMLAURYLSULFATE&set=FoodSubstances",
          "note": "FDA’s technical-effect inventory identifies emulsifier and surface-active functions."
        }
      ]
    },
    "E560": {
      "use": "A direct food-additive function for potassium silicate could not be substantiated. The USDA review of silicon compounds authorised or referenced for food-related uses lists potassium silicate as a corrosion preventative in water, not as a food anticaking agent. Anticaking functions of other silicate salts should not be transferred to this compound.",
      "sources": [
        {
          "label": "USDA — Silicon Compounds Authorized for Use in Foods",
          "url": "https://www.ams.usda.gov/sites/default/files/media/Potassium%20silicate.pdf",
          "note": "The table distinguishes anticaking uses of other silicates from potassium silicate, which it lists as a corrosion preventative in water."
        }
      ]
    },
    "E1430": {
      "use": "Distarch glycerol is a modified starch used as a stabiliser or thickener. It helps build viscosity and maintain the structure and consistency of food.",
      "sources": [
        {
          "label": "US FDA — Substances Added to Food: Distarch glycerol",
          "url": "https://hfpappexternal.fda.gov/scripts/fdcc/index.cfm?id=STARCHFOODMODIFIEDDISTARCHGLYCEROL&set=FoodSubstances",
          "note": "FDA’s technical-effect listing identifies distarch glycerol as a stabiliser or thickener."
        }
      ]
    },
    "E1441": {
      "use": "Hydroxypropyl distarch glycerol is a modified starch used as a stabiliser or thickener. It helps maintain viscosity, body and structure in food systems.",
      "sources": [
        {
          "label": "US FDA — Substances Added to Food: Hydroxypropyl distarch glycerol",
          "url": "https://hfpappexternal.fda.gov/scripts/fdcc/index.cfm?id=STARCHFOODMODIFIEDHYDROXYPROPYLDISTARCHGLYCEROL&set=foodsubstances",
          "note": "FDA’s technical-effect listing identifies hydroxypropyl distarch glycerol as a stabiliser or thickener."
        }
      ]
    },
    "E1502": {
      "use": "1,3-Butanediol (1,3-butylene glycol) is used as a solvent for natural and synthetic flavouring substances. Its technological role is to act as a carrier solvent in flavour preparations rather than to provide the flavour itself.",
      "sources": [
        {
          "label": "US Code of Federal Regulations — 21 CFR §173.220, 1,3-Butylene glycol",
          "url": "https://www.govinfo.gov/content/pkg/CFR-2025-title21-vol3/xhtml/CFR-2025-title21-vol3-sec173-220.html",
          "note": "The provision identifies 1,3-butylene glycol as a solvent for natural and synthetic flavouring substances."
        }
      ]
    },
    "E1504": {
      "use": "Ethyl acetate is used as a flavouring agent and as a carrier solvent in flavour preparations. JECFA lists both functional roles for the substance.",
      "sources": [
        {
          "label": "FAO/WHO JECFA — Ethyl acetate",
          "url": "https://apps.who.int/food-additives-contaminants-jecfa-database/Home/Chemical/3075",
          "note": "JECFA lists ethyl acetate under the functional classes flavouring agent and carrier solvent."
        }
      ]
    }
  };

  const data = window.FOSCH_RESEARCH_DATA;
  if (!data) {
    window.FOSCH_RESEARCH_USES_AUDIT = {
      reviewedAt: "2026-11-26",
      status: "failed",
      reason: "FOSCH_RESEARCH_DATA was unavailable when the Uses override loaded."
    };
    return;
  }

  const updatedCodes = [];
  for (const [code, patch] of Object.entries(updates)) {
    const record = data[code];
    if (!record || typeof record !== "object") continue;

    record.use = patch.use;
    record.researchedAt = "2026-11-26";
    record.researchStatus = "Uses description rechecked against the cited authoritative or primary reference; technological purpose is described separately from legal authorisation.";

    const sources = Array.isArray(record.sources) ? record.sources : [];
    const knownUrls = new Set(sources.map(source => source && source.url).filter(Boolean));
    for (const source of patch.sources) {
      if (source.url && !knownUrls.has(source.url)) {
        sources.push(source);
        knownUrls.add(source.url);
      }
    }
    record.sources = sources;
    updatedCodes.push(code);
  }

  window.FOSCH_RESEARCH_USES_AUDIT = {
    reviewedAt: "2026-11-26",
    updatedCount: updatedCodes.length,
    updatedCodes: updatedCodes
  };
})();
