(ns socialworkops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-889`: this
  repo had NO demo page and no generator at all. This namespace drives
  the REAL actor stack -- `socialworkops.operation` (a langgraph-clj
  StateGraph) -> `socialworkops.advisor` -> `socialworkops.governor` ->
  `socialworkops.phase` -> `socialworkops.store` -- and renders the
  console from what that stack actually returned. Nothing on the page
  is hand-typed: every row is read back out of the store's own ledger,
  its coordination log, its client directory, or the graph run results.

  Ran BEFORE this file existed and found the actor was DEAD:
  `socialworkops.operation` called `(g/compile ...)` but `langgraph.graph`
  names that fn `compile-graph`, so the namespace failed to load. No test
  requires `socialworkops.operation`, so nothing ever noticed -- the
  StateGraph this repo exists to be had never once been executed
  (`clojure -M:run` was broken too). Fixed in the same commit as this
  renderer; the fix is what makes the page possible.

  Three kinds of refusal appear on this page and are NEVER merged into
  one number, because they mean different things and a naive `count` of
  `:t :governor-hold` facts silently conflates the first two:

    HARD governor hold  `:t :governor-hold` with a NON-EMPTY
                        `:violations` -- the SocialWorkGovernor itself
                        refused. Permanent, un-overridable by any human
                        approval.
    Phase/rollout hold  `:t :governor-hold` with an EMPTY `:violations`
                        and a `:phase-reason`. The governor was fine;
                        `socialworkops.phase` refused because the op is
                        not yet enabled at that rollout phase. This is a
                        milestone, not a compliance judgement.
    Human refusal       `:t :approval-rejected` -- a human operator
                        looked at an escalation and said no.

  Determinism: no timestamps, no randomness, no wall-clock, fresh
  seeded store per run -- byte-identical across reruns. Verify with two
  runs into two scratch paths and `cmp`.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [socialworkops.advisor :as advisor]
            [socialworkops.governor :as governor]
            [socialworkops.operation :as operation]
            [socialworkops.phase :as phase]
            [socialworkops.store :as store]))

;; ----------------------------- run harness -----------------------------

(def ^:private actor-id
  "The actor identity this repo's own `socialworkops.sim` uses."
  "cloud-itonami-isic-889")

(def ^:private approver
  "The human who resumes an escalated run. This repo's store has a CLIENT
  directory and no operator directory, so an approver identity is supplied
  by the caller (here, and in production by whatever authenticates the
  operator) rather than read from seed data. Named by role, not by a
  fabricated person."
  "coordinator-on-call")

(def ^:private interrupt-gate
  "`socialworkops.operation`'s docstring promises `interrupt-before
  #{:request-approval}` human-in-the-loop, but `build` hard-codes its
  compile opts to `{:checkpointer ..}` and takes no `:interrupt-before`.
  langgraph merges run-opts OVER compile opts (`langgraph.graph/run*`),
  so the caller can still arm the gate -- without it the escalation path
  falls straight through `:request-approval` with `approval` nil and
  auto-rejects, which is safe but means no approval could ever be
  granted. Measured, not assumed."
  #{:request-approval})

(defrecord DriftingAdvisor [base]
  advisor/Advisor
  (-advise [_this st request]
    (let [p (advisor/-advise base st request)]
      (case (:drift request)
        ;; An advisor that has quietly stopped proposing and started
        ;; claiming to act. `socialworkops.governor`'s check 2.
        :direct-actuation
        (assoc p :effect :actuate
                 :summary (str "Actuate the referral directly with the receiving agency for client "
                               (:client-id request)))

        ;; An advisor that has drifted from coordination into a
        ;; permanently out-of-charter decision area. The op is still on
        ;; the allowlist -- only the CONTENT gives it away, which is
        ;; exactly what `scope-exclusion-violations` scans for. Check 3.
        :out-of-scope
        (assoc p :rationale (str "refer so the receiving clinic can record a clinical diagnosis for client "
                                 (:client-id request)))

        p))))

(defn- drifting-advisor
  "The repo's own mock advisor, wrapped so a scenario may ask it to drift.
  `socialworkops.advisor/Advisor` is a declared injection seam
  (`operation/build` `:advisor` opt, documented `mock | real LLM`); this
  is a third implementation of that seam, not a patch of the governor.
  The governor, the phase gate, the graph and the store are all the real
  ones."
  []
  (->DriftingAdvisor (advisor/mock-advisor)))

(defn- exec!
  "One coordination request = one supervised graph run."
  [actor thread-id phase request]
  (g/run* actor
          {:request request
           :context {:actor-id actor-id :role "coordinator" :phase phase}}
          {:thread-id thread-id :interrupt-before interrupt-gate}))

(defn- resume!
  "Resume an interrupted run with a human decision."
  [actor thread-id status]
  (g/run* actor
          {:approval {:status status :by approver}}
          {:thread-id thread-id :resume? true :interrupt-before interrupt-gate}))

(def ^:private scenarios
  "Every scenario this console renders, as data. `:phase` is the rollout
  phase the request runs at; `:approve` (when present) is the human
  decision used to resume the run if the actor interrupts for one.
  Client ids are exactly the three in `socialworkops.store/demo-data`:
  client-1 and client-2 are registered+verified, client-3 is registered
  but NOT yet verified (still in intake)."
  [;; --- phase-3 supervised auto-commit, governor clean ---
   {:id "s1" :phase 3 :request {:op :log-client-contact-note :client-id "client-1"}
    :note "routine contact note"}
   {:id "s2" :phase 3 :request {:op :schedule-appointment :client-id "client-2"}
    :note "counseling appointment"}
   {:id "s3" :phase 3 :request {:op :coordinate-referral :client-id "client-1"}
    :note "referral to an external service"}
   {:id "s4" :phase 3 :request {:op :coordinate-benefits-application-assistance :client-id "client-2"}
    :note "benefits APPLICATION assistance (never an eligibility decision)"}

   ;; --- escalations that reach a human ---
   {:id "s5" :phase 3 :request {:op :flag-safety-concern :client-id "client-1"}
    :approve :approved :note "safety concern -- never auto at any phase"}
   {:id "s6" :phase 3 :request {:op :flag-safety-concern :client-id "client-2"}
    :approve :rejected :note "safety concern the human declined to act on"}
   {:id "s7" :phase 2 :request {:op :log-client-contact-note :client-id "client-2"}
    :approve :approved :note "phase 2: enabled but not yet auto-eligible"}

   ;; --- HARD governor holds: the governor itself refuses ---
   {:id "s8" :phase 3 :request {:op :log-client-contact-note :client-id "client-3"}
    :note "client still in intake -- not independently verified"}
   {:id "s9" :phase 3 :request {:op :determine-benefits-eligibility :client-id "client-1"}
    :note "eligibility determination is outside the closed op allowlist"}
   {:id "s10" :phase 3 :request {:op :coordinate-referral :client-id "client-1" :drift :direct-actuation}
    :note "advisor claimed to actuate instead of propose"}
   {:id "s11" :phase 3 :request {:op :coordinate-referral :client-id "client-2" :drift :out-of-scope}
    :note "allowed op, but the content drifted into clinical diagnosis"}

   ;; --- phase/rollout gate holds: governor clean, phase not there yet ---
   {:id "s12" :phase 1 :request {:op :schedule-appointment :client-id "client-1"}
    :note "phase 1 only logs contact notes"}
   {:id "s13" :phase 2 :request {:op :flag-safety-concern :client-id "client-2"}
    :note "safety flagging not enabled until phase 3"}
   {:id "s14" :phase 0 :request {:op :coordinate-referral :client-id "client-1"}
    :note "phase 0 is read-only"}])

(defn run-demo!
  "Runs every scenario through ONE real actor bound to ONE freshly seeded
  store, and returns `{:db .. :runs [..]}`. `:runs` carries each
  scenario's final disposition and whether the actor actually interrupted
  for a human -- information the ledger does NOT retain (`:approval-requested`
  and `:approval-granted` facts live only in the run's `:audit` channel;
  only `:committed`, `:governor-hold` and `:approval-rejected` are
  appended to the store). Every value below is graph output."
  []
  (let [db (store/seed-db)
        actor (operation/build db {:advisor (drifting-advisor)})
        runs (mapv
              (fn [{:keys [id phase request approve] :as sc}]
                (let [r1 (exec! actor id phase request)
                      interrupted? (= :interrupted (:status r1))
                      r2 (when (and interrupted? approve)
                           (resume! actor id approve))
                      final (or r2 r1)]
                  (assoc sc
                         :interrupted? interrupted?
                         :approval (when (and interrupted? approve) approve)
                         :disposition (:disposition (:state final))
                         :audit (:audit (:state final)))))
              scenarios)]
    {:db db :runs runs}))

;; ----------------------------- fact classification -----------------------------

(defn- hard-hold?
  "The SocialWorkGovernor itself refused: a `:governor-hold` carrying at
  least one violation. A phase/rollout hold is written with the SAME
  `:t` but an EMPTY `:violations`, so `(= :governor-hold (:t f))` alone
  would over-count -- measured on this repo's own output."
  [f]
  (and (= :governor-hold (:t f)) (seq (:violations f))))

(defn- phase-hold?
  "The governor was clean; `socialworkops.phase` held the op because the
  rollout phase does not enable it yet. Empty `:violations`, a
  `:phase-reason`."
  [f]
  (and (= :governor-hold (:t f)) (empty? (:violations f)) (:phase-reason f)))

(defn- human-refusal? [f] (= :approval-rejected (:t f)))

(defn- approver-of
  "Approver attribution, DERIVED AT RENDER TIME rather than assumed.
  Scans a committed coordination record for any approver-ish key, at the
  top level and inside `:payload`/`:value`, so this page self-corrects if
  `socialworkops.operation` ever moves or drops it. Deliberately does NOT
  join records to `:approval-granted` audit facts on [op client-id]: that
  pair is NOT unique in this scenario set (client-2 has two
  `:log-client-contact-note` runs), and such a join would let a record
  inherit an unrelated run's approver."
  [record]
  (let [ks [:approved-by :approver :approved-by-id :by]]
    (some (fn [m] (some #(get m %) ks))
          [record (:payload record) (:value record)])))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- td [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- table [headers rows]
  (if (seq rows)
    (str "    <table>\n"
         "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
         "      <tbody>\n" (str/join "\n" rows) "\n      </tbody>\n"
         "    </table>\n")
    "    <p class=\"muted\">(none in this run)</p>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

;; --- client directory ---

(defn- client-row [{:keys [client-id name registered? verified?]}]
  (td (code client-id)
      (esc name)
      (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">not registered</span>")
      (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">not verified</span>")
      (if (and registered? verified?)
        "<span class=\"ok\">proposals may proceed</span>"
        "<span class=\"critical\">HARD block on every op</span>")))

;; --- derived gate contract ---

(defn- gate-row
  "Derived from `governor/allowed-ops`, `governor/always-escalate-ops`
  and `phase/phases` -- not a hand-written description of them."
  [op]
  (let [writes-at (sort (keep (fn [[p {:keys [writes]}]] (when (contains? writes op) p)) phase/phases))
        auto-at (sort (keep (fn [[p {:keys [auto]}]] (when (contains? auto op) p)) phase/phases))
        always? (contains? governor/always-escalate-ops op)]
    (td (code op)
        (if (seq writes-at) (esc (str/join ", " writes-at)) "<span class=\"muted\">never</span>")
        (if (seq auto-at)
          (str "<span class=\"ok\">" (esc (str/join ", " auto-at)) "</span>")
          "<span class=\"warn\">never &mdash; always a human</span>")
        (if always?
          "<span class=\"warn\">ALWAYS escalates &middot; governor AND phase agree, independently</span>"
          (str "<span class=\"muted\">escalates below confidence "
               (esc governor/confidence-floor) "</span>")))))

;; --- run manifest ---

(defn- disposition-cell [{:keys [disposition interrupted? approval]}]
  (case disposition
    :commit (if approval
              (str "<span class=\"ok\">committed after human approval</span>")
              "<span class=\"ok\">auto-committed</span>")
    :hold (cond
            (= :rejected approval) "<span class=\"critical\">held &middot; human declined</span>"
            interrupted? "<span class=\"critical\">held</span>"
            :else "<span class=\"critical\">held</span>")
    :escalate "<span class=\"warn\">awaiting a human (never resumed)</span>"
    (str "<span class=\"muted\">" (esc (kw disposition)) "</span>")))

(defn- run-row [{:keys [id phase request note] :as r}]
  (td (code id)
      (esc phase)
      (code (kw (:op request)))
      (code (:client-id request))
      (if (:interrupted? r) "<span class=\"warn\">yes</span>" "<span class=\"muted\">no</span>")
      (disposition-cell r)
      (esc note)))

;; --- committed records ---

(defn- record-row [record]
  (let [a (approver-of record)]
    (td (code (kw (:op record)))
        (code (:client-id record))
        (if a
          (str "<span class=\"ok\">" (esc a) "</span>")
          "<span class=\"muted\">auto &middot; no human in the loop</span>")
        (code (pr-str (:payload record))))))

;; --- holds ---

(defn- hard-hold-row [f]
  (td (code (kw (:op f)))
      (code (:client-id f))
      (str "<span class=\"critical\">" (esc (str/join ", " (map kw (:basis f)))) "</span>")
      (esc (str/join " / " (keep :detail (:violations f))))))

(defn- phase-hold-row [f]
  (td (code (kw (:op f)))
      (code (:client-id f))
      (esc (:phase f))
      (str "<span class=\"warn\">" (esc (kw (:phase-reason f))) "</span>")
      (if (seq (:violations f))
        (str "<span class=\"critical\">" (esc (count (:violations f))) "</span>")
        "<span class=\"muted\">0 &mdash; the governor was clean</span>")))

(defn- refusal-row [f]
  (td (code (kw (:op f)))
      (code (:client-id f))
      (str "<span class=\"critical\">" (esc (str/join ", " (map kw (:basis f)))) "</span>")))

;; --- ledger ---

(defn- ledger-row [f]
  (td (esc (kw (:t f)))
      (code (kw (:op f)))
      (code (:client-id f))
      (esc (kw (:disposition f)))
      (if (seq (:basis f)) (esc (str/join ", " (map kw (:basis f)))) "<span class=\"muted\">&mdash;</span>")))

(defn- approver-disclosure
  "Derived from what the store actually retained on this run, so the wording
  changes by itself if `socialworkops.operation` changes."
  [records runs]
  (let [approved-runs (filter #(= :approved (:approval %)) runs)
        with-approver (filter approver-of records)]
    (cond
      (empty? approved-runs)
      "<span class=\"muted\">No run in this scenario set was approved by a human, so there is nothing to attribute.</span>"

      (>= (count with-approver) (count approved-runs))
      (str "<span class=\"ok\">Retained on the record.</span> "
           (esc (count approved-runs))
           " run(s) were approved by a human and "
           (esc (count with-approver))
           " committed record(s) carry the approver, read directly off the record "
           "(no join on <code>[op client-id]</code> &mdash; that pair is not unique here). "
           "Note the ledger&rsquo;s own <code>:committed</code> fact does NOT carry the approver: "
           "the <code>:approval-granted</code> fact stays in the run&rsquo;s <code>:audit</code> "
           "channel and is never appended to the store, so the coordination record is the only "
           "durable place the approver survives.")

      :else
      (str "<span class=\"critical\">(audit only &mdash; not retained on record.)</span> "
           (esc (count approved-runs)) " run(s) were approved by a human but only "
           (esc (count with-approver))
           " committed record(s) carry an approver key. The attribution below is recovered from "
           "the run&rsquo;s audit trail, not from the SSoT; a record committed after human "
           "approval is indistinguishable from an auto-commit once the process exits."))))

(defn render
  "Renders the console from a completed `run-demo!` result. Every table
  is read back out of the real store or the real run results."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        records (vec (store/coordination-log db))
        clients (vec (store/all-clients db))
        hard-holds (filterv hard-hold? ledger)
        phase-holds (filterv phase-hold? ledger)
        refusals (filterv human-refusal? ledger)
        committed (filterv #(= :committed (:t %)) ledger)]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\"><meta name=\"theme-color\" content=\"#ffffff\">"
     "<title>cloud-itonami-isic-889 &middot; non-residential social work &mdash; Operator Console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Other social work activities without accommodation (ISIC 889) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; coordination only &middot; governor-gated</span>\n"
     "</header>\n"
     "<main>\n"
     "  <p class=\"subtitle\">Build-time output of <code>socialworkops.render-html</code> "
     "(<code>clojure -M:render-html</code>). Every row below was produced by running the real "
     "<code>socialworkops.operation</code> StateGraph &mdash; advisor &rarr; SocialWorkGovernor &rarr; "
     "phase gate &rarr; store &mdash; against the store&rsquo;s own seeded client directory. "
     "Nothing here is hand-written and nothing is a mock of the output.</p>\n"

     (section "Client directory (SSoT)"
              (str "The governor re-derives <code>:registered?</code>/<code>:verified?</code> from these "
                   "records on every single proposal and never trusts a proposal&rsquo;s own claim about "
                   "its client. <code>client-3</code> is still in intake &mdash; that is real seed data, "
                   "and it is why one HARD hold below exists.")
              (table ["Client" "Name" "Registration" "Verification" "Effect on proposals"]
                     (map client-row clients)))

     (section "Governance gate (derived from the code, not described)"
              (str "Read out of <code>governor/allowed-ops</code>, <code>governor/always-escalate-ops</code> "
                   "and <code>phase/phases</code> at render time. An op outside this closed allowlist is a "
                   "scope violation by construction.")
              (table ["Op" "Writable at phases" "Auto-commit at phases" "Escalation"]
                     (map gate-row (sort-by name governor/allowed-ops))))

     (section "Scenario runs (this build)"
              (str (esc (count runs)) " supervised graph runs, one coordination request each. "
                   "&ldquo;Paused for a human&rdquo; is the actor genuinely interrupting "
                   "(<code>interrupt-before #{:request-approval}</code>) and waiting to be resumed &mdash; "
                   "not a rendering flourish.")
              (table ["Run" "Phase" "Op" "Client" "Paused for a human" "Outcome" "What it exercises"]
                     (map run-row runs)))

     (section "HARD governor holds"
              (str (esc (count hard-holds)) " refusal(s) by the SocialWorkGovernor itself, across "
                   (esc (count (distinct (mapcat :basis hard-holds)))) " distinct rule(s). "
                   "These are permanent and un-overridable: no human approval can release them, and they "
                   "never reach a human at all. Every row carries at least one violation &mdash; that is "
                   "what separates this table from the next one.")
              (table ["Op" "Client" "Rule" "Governor&rsquo;s own detail"]
                     (map hard-hold-row hard-holds)))

     (section "Phase / rollout gate holds (NOT governor refusals)"
              (str (esc (count phase-holds)) " hold(s) where the governor was clean and the rollout phase "
                   "simply does not enable the op yet. The store writes these with the same "
                   "<code>:t :governor-hold</code> tag as the table above, but with an EMPTY "
                   "<code>:violations</code> vector &mdash; counting <code>:governor-hold</code> facts "
                   "would silently inflate the compliance number with rollout milestones.")
              (table ["Op" "Client" "Phase" "Reason" "Governor violations"]
                     (map phase-hold-row phase-holds)))

     (section "Human refusals"
              (str (esc (count refusals)) " escalation(s) a human operator looked at and declined. "
                   "Distinct again from both tables above: the governor cleared it and the phase allowed "
                   "it; a person said no.")
              (table ["Op" "Client" "Rule"] (map refusal-row refusals)))

     (section "Committed coordination records"
              (str (esc (count records)) " record(s) reached the SSoT. Approver attribution is derived at "
                   "render time by scanning each record for an approver key. "
                   (approver-disclosure records runs))
              (table ["Op" "Client" "Approved by" "Stored payload"]
                     (map record-row records)))

     (section "Audit ledger (this run)"
              (str "The append-only decision-fact log, verbatim: "
                   (esc (count ledger)) " fact(s) &mdash; "
                   (esc (count committed)) " committed, "
                   (esc (count hard-holds)) " HARD governor hold(s), "
                   (esc (count phase-holds)) " phase hold(s), "
                   (esc (count refusals)) " human refusal(s). "
                   "Escalation requests and grants are deliberately absent: "
                   "<code>socialworkops.operation</code> keeps <code>:approval-requested</code> and "
                   "<code>:approval-granted</code> in the run&rsquo;s <code>:audit</code> channel and never "
                   "appends them to the store.")
              (table ["Fact" "Op" "Client" "Disposition" "Basis"]
                     (map ledger-row ledger)))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>socialworkops.render-html</code> from the real actor. "
     "Permanently out of charter and HARD-blocked: protective custody or removal, involuntary "
     "commitment, legal representation, clinical diagnosis, benefits eligibility determination, "
     "safety-authority enforcement. AGPL-3.0-or-later.</p>\n"
     "</footer>\n</body>\n</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        hard-holds (filterv hard-hold? ledger)
        kinds (distinct (mapcat :basis hard-holds))]
    ;; Build-time invariant, not a convention: a console for a safeguarding
    ;; actor that shows no refusal is a console that has not demonstrated
    ;; the only thing that matters. Throw BEFORE writing, so a run that
    ;; lost its holds cannot leave a plausible-looking page behind.
    (when (empty? hard-holds)
      (throw (ex-info (str "Refusing to write " out
                           ": the run produced ZERO HARD governor holds. "
                           "A phase/rollout hold carries an empty :violations vector and does NOT count.")
                      {:ledger-facts (count ledger)
                       :phase-holds (count (filterv phase-hold? ledger))
                       :human-refusals (count (filterv human-refusal? ledger))})))
    (let [html (render result)]
      (spit out html)
      (println "wrote" out
               (str "(" (count html) " bytes, "
                    (count runs) " runs, "
                    (count ledger) " ledger facts, "
                    (count hard-holds) " HARD governor holds "
                    (pr-str (vec kinds)) ", "
                    (count (filterv phase-hold? ledger)) " phase holds, "
                    (count (filterv human-refusal? ledger)) " human refusals, "
                    (count (store/coordination-log db)) " committed records)")))))
