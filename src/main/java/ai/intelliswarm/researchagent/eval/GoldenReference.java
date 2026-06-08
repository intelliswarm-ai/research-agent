package ai.intelliswarm.researchagent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps hypothesis domain keywords to landmark trial/guideline PMIDs.
 *
 * <p>When the hypothesis matches a known domain but none of the landmark PMIDs
 * appear in the report, an INFO[golden-reference-miss] issue is appended to the
 * scorer's issues list (no score penalty — surfaced for manual review only).
 *
 * <p>Coverage (as of May 2026):
 * <ul>
 *   <li>Alzheimer / amyloid</li>
 *   <li>Exercise + depression (Blumenthal 1999, Schuch 2016, Kvam 2016)</li>
 *   <li>Iron + CKD (PIVOTAL trial / Macdougall 2019, Besarab 1998)</li>
 *   <li>Sarcopenia + resistance training (Fiatarone 1994, Bhasin 2001)</li>
 *   <li>NAFLD + diet (EASL guidelines 2016, Vilar-Gomez 2015)</li>
 *   <li>Biologic + DMARD / rheumatoid arthritis (Smolen 2016 ACR/EULAR, Singh 2015)</li>
 *   <li>GLP-1 CVOT trials (LEADER/liraglutide 2016, SUSTAIN-6/semaglutide 2017, EXSCEL/exenatide 2017)</li>
 *   <li>DPP-4 CVOT trials (TECOS/sitagliptin 2015, SAVOR-TIMI 53/saxagliptin 2013, EXAMINE/alogliptin 2013)</li>
 *   <li>Statin + elderly / ASCVD (PROSPER 2002, TNT 2005)</li>
 * </ul>
 */
public final class GoldenReference {

    private GoldenReference() {}

    /**
     * Domain descriptor for a known hypothesis area: a set of trigger keywords
     * (all must appear in lower-cased hypothesis+report for the domain to activate)
     * and a list of landmark PMIDs that should be cited if the domain matches.
     */
    public record Domain(String name, List<String> triggerKeywords, List<String> landmarkPmids) {}

    /** All registered golden-reference domains. */
    public static final List<Domain> DOMAINS = List.of(

        // ── Alzheimer / amyloid ───────────────────────────────────────────────
        new Domain(
            "alzheimer-amyloid",
            List.of("alzheimer", "amyloid"),
            List.of(
                "30146124",  // Alzheimer Aducanumab (EMERGE) NEJM 2021 — commonly cited
                "25600295",  // Bateman — DIAN trial
                "27820943"   // Cummings Alzheimer's disease landmark review
            )
        ),

        // ── Exercise + depression ─────────────────────────────────────────────
        new Domain(
            "exercise-depression",
            List.of("exercise", "depression"),
            List.of(
                "10580442",  // Blumenthal 1999 JAMA — aerobic exercise vs sertraline in MDD
                "27026477",  // Schuch 2016 — exercise as a treatment for depression (meta-analysis)
                "27067635"   // Kvam 2016 — exercise vs CBT and medication for depression
            )
        ),

        // ── Iron supplementation + chronic kidney disease ─────────────────────
        new Domain(
            "iron-ckd",
            List.of("iron", "ckd"),
            List.of(
                "30365463",  // Macdougall 2019 NEJM — PIVOTAL trial (IV iron in dialysis)
                "9413549"    // Besarab 1998 NEJM — normal hematocrit in hemodialysis
            )
        ),
        // alternate trigger set using "chronic kidney disease" spelled out
        new Domain(
            "iron-chronic-kidney-disease",
            List.of("iron", "chronic kidney disease"),
            List.of(
                "30365463",
                "9413549"
            )
        ),

        // ── Sarcopenia + resistance training ─────────────────────────────────
        new Domain(
            "sarcopenia-resistance",
            List.of("sarcopenia", "resistance"),
            List.of(
                "8304959",   // Fiatarone 1994 NEJM — resistance training in elderly nursing-home residents
                "11502588"   // Bhasin 2001 NEJM — testosterone + resistance exercise
            )
        ),
        // alternate trigger: muscle + aging + resistance
        new Domain(
            "muscle-aging-resistance",
            List.of("muscle", "aging", "resistance"),
            List.of(
                "8304959",
                "11502588"
            )
        ),

        // ── NAFLD + dietary intervention ──────────────────────────────────────
        new Domain(
            "nafld-diet",
            List.of("nafld", "diet"),
            List.of(
                "27207981",  // EASL Clinical Practice Guidelines 2016 — NAFLD management
                "25931232"   // Vilar-Gomez 2015 Gastroenterology — lifestyle intervention in NAFLD
            )
        ),
        new Domain(
            "nonalcoholic-fatty-liver-diet",
            List.of("nonalcoholic fatty liver", "diet"),
            List.of(
                "27207981",
                "25931232"
            )
        ),

        // ── Biologic / DMARD in rheumatoid arthritis ──────────────────────────
        new Domain(
            "biologic-dmard-ra",
            List.of("biologic", "dmard"),
            List.of(
                "26977309",  // Smolen 2016 — EULAR RA management recommendations
                "25780926"   // Singh 2015 ACR — 2015 ACR guideline for RA pharmacotherapy
            )
        ),
        new Domain(
            "rheumatoid-arthritis-biologic",
            List.of("rheumatoid arthritis", "biologic"),
            List.of(
                "26977309",
                "25780926"
            )
        ),

        // ── Dupilumab + atopic dermatitis ─────────────────────────────────────
        new Domain(
            "dupilumab-atopic-dermatitis",
            List.of("dupilumab", "atopic"),
            List.of(
                "27690741",  // Simpson 2016 NEJM — dupilumab monotherapy in moderate-to-severe AD
                "27264345"   // Blauvelt 2017 Lancet — dupilumab vs placebo in adults with AD
            )
        ),

        // ── Cancer-associated thrombosis / VTE ────────────────────────────────
        new Domain(
            "cancer-associated-thrombosis",
            List.of("cancer", "thrombosis"),
            List.of(
                "29231094",  // Young 2018 (SELECT-D) — rivaroxaban vs dalteparin in cancer-associated VTE
                "31145625",  // Raskob 2018 (HOKUSAI-VTE Cancer) — edoxaban vs dalteparin
                "32223112",  // Agnelli 2020 (Caravaggio) — apixaban vs dalteparin
                "32223113"   // McBane 2020 (ADAM VTE) — apixaban vs dalteparin in cancer
            )
        ),
        new Domain(
            "cancer-vte",
            List.of("vte", "cancer"),
            List.of(
                "29231094",
                "31145625",
                "32223112",
                "32223113"
            )
        ),
        new Domain(
            "cancer-lmwh",
            List.of("cancer", "lmwh"),
            List.of(
                "29231094",
                "31145625",
                "32223112",
                "32223113"
            )
        ),
        new Domain(
            "malignancy-anticoagulation",
            List.of("malignancy", "anticoagul"),
            List.of(
                "29231094",
                "31145625",
                "32223112",
                "32223113"
            )
        ),

        // ── GLP-1 receptor agonists + cardiovascular outcomes ─────────────────
        new Domain(
            "glp1-cvot",
            List.of("glp-1", "cardiovascular"),
            List.of(
                "27295427",  // Marso 2016 NEJM — LEADER trial (liraglutide CVOT)
                "28860877",  // Marso 2016 NEJM — SUSTAIN-6 trial (semaglutide CVOT)
                "29242195"   // Holman 2017 NEJM — EXSCEL trial (exenatide CVOT)
            )
        ),
        new Domain(
            "liraglutide-cardiovascular",
            List.of("liraglutide", "cardiovascular"),
            List.of(
                "27295427",
                "28860877",
                "29242195"
            )
        ),

        // ── DPP-4 inhibitors + cardiovascular outcomes ────────────────────────
        new Domain(
            "dpp4-cvot",
            List.of("dpp-4", "cardiovascular"),
            List.of(
                "26186977",  // Green 2015 NEJM — TECOS trial (sitagliptin CVOT)
                "25765696",  // Scirica 2013 NEJM — SAVOR-TIMI 53 trial (saxagliptin CVOT)
                "25399272"   // White 2013 NEJM — EXAMINE trial (alogliptin CVOT)
            )
        ),
        new Domain(
            "sitagliptin-cardiovascular",
            List.of("sitagliptin", "cardiovascular"),
            List.of(
                "26186977",
                "25765696",
                "25399272"
            )
        ),

        // ── Statin + elderly / ASCVD ──────────────────────────────────────────
        new Domain(
            "statin-elderly-ascvd",
            List.of("statin", "elderly"),
            List.of(
                "12457784",  // Shepherd 2002 Lancet — PROSPER trial (pravastatin in elderly)
                "15007110"   // LaRosa 2005 NEJM — TNT trial (atorvastatin high-intensity)
            )
        ),
        new Domain(
            "pravastatin-elderly",
            List.of("pravastatin", "elderly"),
            List.of(
                "12457784",
                "15007110"
            )
        ),

        // ── Cognitive training + Alzheimer / MCI ──────────────────────────────
        new Domain(
            "cognitive-training-alzheimer",
            List.of("cognitive training", "alzheimer"),
            List.of(
                "25307527",  // Kelly et al 2014 — cognitive training for MCI: systematic review
                "27446051",  // Hill et al 2017 — computerized cognitive training meta-analysis
                "25948636"   // Lampit et al 2014 PLOS Medicine — computerized cognitive training RCT meta-analysis
            )
        ),
        new Domain(
            "cognitive-training-mci",
            List.of("cognitive training", "mild cognitive impairment"),
            List.of(
                "25307527",
                "27446051",
                "25948636"
            )
        ),
        new Domain(
            "cognitive-intervention-dementia",
            List.of("cognitive", "dementia"),
            List.of(
                "25307527",
                "32614296"   // Livingston et al 2020 Lancet Commission — dementia prevention
            )
        ),

        // ── Physical activity + Alzheimer / cognitive decline ─────────────────
        new Domain(
            "physical-activity-alzheimer",
            List.of("physical activity", "alzheimer"),
            List.of(
                "32614296",  // Livingston 2020 Lancet Commission on dementia prevention
                "25030568",  // Norton et al 2014 Lancet Neurology — modifiable risk factors
                "28052061"   // Northey et al 2018 BJSM — exercise intervention for cognition meta-analysis
            )
        ),
        new Domain(
            "exercise-cognitive-decline",
            List.of("exercise", "cognitive decline"),
            List.of(
                "28052061",
                "25030568"
            )
        ),

        // ── Visceral fat + insulin resistance ────────────────────────────────
        new Domain(
            "visceral-fat-insulin-resistance",
            List.of("visceral fat", "insulin resistance"),
            List.of(
                "15105165",  // Carey et al 2004 — abdominal fat and insulin resistance
                "11136951",  // Kelley et al 2000 — visceral fat, insulin resistance
                "16723586"   // Bergman et al 2006 — visceral adiposity and T2DM risk
            )
        ),
        new Domain(
            "ultra-processed-food-metabolic",
            List.of("ultra-processed", "metabolic"),
            List.of(
                "29684407",  // Monteiro et al 2019 — NOVA classification and ultra-processed foods
                "31309673"   // Hall et al 2019 CMAJ — ultra-processed food RCT
            )
        ),

        // ── SGLT2 inhibitors + heart failure ──────────────────────────────────
        new Domain(
            "sglt2-heart-failure",
            List.of("sglt2", "heart failure"),
            List.of(
                "32865373",  // McMurray 2019 NEJM — DAPA-HF trial (dapagliflozin in HFrEF)
                "32865371",  // Packer 2020 NEJM — EMPEROR-Reduced (empagliflozin in HFrEF)
                "31535984"   // Zinman 2015 NEJM — EMPA-REG OUTCOME (empagliflozin CVOT)
            )
        ),
        new Domain(
            "dapagliflozin-heart-failure",
            List.of("dapagliflozin", "heart failure"),
            List.of(
                "32865373",
                "32865371"
            )
        ),

        // ── Metformin + type 2 diabetes ───────────────────────────────────────
        new Domain(
            "metformin-type2-diabetes",
            List.of("metformin", "type 2 diabetes"),
            List.of(
                "9742977",   // UKPDS 1998 Lancet — intensive glucose control with metformin
                "23594956"   // Inzucchi 2012 ADA/EASD — metformin position statement
            )
        ),

        // ── Omega-3 + cardiovascular outcomes ────────────────────────────────
        new Domain(
            "omega3-cardiovascular",
            List.of("omega-3", "cardiovascular"),
            List.of(
                "30415628",  // Bhatt 2019 NEJM — REDUCE-IT trial (icosapentaenoic acid)
                "30705874",  // ORIGIN trial — omega-3 in dysglycemia
                "19066812"   // GISSI-HF — omega-3 in heart failure
            )
        )
    );

    /**
     * Checks the hypothesis + report text for golden-reference misses.
     *
     * @param hypothesis  lower-cased hypothesis string
     * @param reportLower lower-cased full report text
     * @return list of INFO[golden-reference-miss] messages, empty if nothing missed
     */
    public static List<String> checkMisses(String hypothesis, String reportLower) {
        List<String> infos = new ArrayList<>();
        String combined = (hypothesis == null ? "" : hypothesis) + " " + reportLower;

        for (Domain domain : DOMAINS) {
            // Only activate if ALL trigger keywords appear in the combined text
            boolean allMatch = domain.triggerKeywords().stream()
                    .allMatch(kw -> combined.contains(kw.toLowerCase()));
            if (!allMatch) continue;

            // Check which landmark PMIDs appear in the report
            List<String> missing = domain.landmarkPmids().stream()
                    .filter(pmid -> !reportLower.contains(pmid))
                    .toList();

            if (!missing.isEmpty()) {
                infos.add("INFO[golden-reference-miss][" + domain.name() + "]: hypothesis matches domain '"
                        + domain.name() + "' but " + missing.size() + "/" + domain.landmarkPmids().size()
                        + " landmark PMID(s) are absent from the report: " + missing
                        + ". No score penalty — surfaced for manual review. "
                        + "Registered domains: " + DOMAINS.stream().map(Domain::name).toList() + ".");
            }
        }
        return infos;
    }

    /** Landmark-trial recall summary across all matched domains. */
    public record Recall(boolean domainMatched, int present, int total,
                         List<String> missing, List<String> matchedDomains) {}

    /**
     * Computes how many landmark PMIDs the report actually cites for every matched domain.
     * Used by the scorer's substance check: a hypothesis that matches a known domain but cites
     * NONE of the field-defining trials has missed the most important evidence.
     *
     * @param hypothesis  lower-cased hypothesis string
     * @param reportLower lower-cased full report text
     */
    public static Recall recall(String hypothesis, String reportLower) {
        String combined = (hypothesis == null ? "" : hypothesis) + " " + reportLower;
        int present = 0;
        int total = 0;
        List<String> missing = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (Domain domain : DOMAINS) {
            boolean allMatch = domain.triggerKeywords().stream()
                    .allMatch(kw -> combined.contains(kw.toLowerCase()));
            if (!allMatch) continue;
            matched.add(domain.name());
            for (String pmid : domain.landmarkPmids()) {
                total++;
                if (reportLower.contains(pmid)) present++;
                else missing.add(pmid);
            }
        }
        return new Recall(!matched.isEmpty(), present, total, missing, matched);
    }

    /**
     * Returns true when the hypothesis matches at least one registered domain.
     * Useful for logging DEFECT[no-golden-reference] when no domain activates.
     */
    public static boolean hasCoverage(String hypothesisLower, String reportLower) {
        String combined = hypothesisLower + " " + reportLower;
        return DOMAINS.stream().anyMatch(domain ->
                domain.triggerKeywords().stream().allMatch(kw -> combined.contains(kw.toLowerCase())));
    }
}
