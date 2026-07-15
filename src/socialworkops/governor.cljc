(ns socialworkops.governor
  "SocialWorkGovernor -- the independent compliance layer that earns
  the SocialWorkAdvisor the right to commit. The advisor has no notion
  of whether a client is actually registered and verified, whether
  its own proposed `:effect` secretly claims a direct actuation instead
  of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (client-contact logging, appointment scheduling, referral coordination,
  benefits-application assistance tracking). It NEVER performs
  or authorizes:
    - child protective-custody or removal decisions
    - adult protective-custody or removal decisions
    - involuntary commitment decisions
    - legal representation decisions
    - clinical diagnosis or assessment
    - benefits eligibility determinations or award/denial decisions
    - safety-authority overrides (investigation, enforcement, compliance)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Client unverified      -- the target client record must
                                 exist AND be independently
                                 confirmed `:registered?`/
                                 `:verified?` in the store before
                                 ANY proposal for it may commit or
                                 even escalate. Never trusts a
                                 proposal's own claim about the
                                 client -- re-derived from the
                                 client's own store record, the same
                                 'ground truth, not self-report'
                                 discipline every sibling actor's
                                 governor uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST
                                   be `:propose`. Any other effect
                                   value is, by construction, a
                                   claim to directly actuate/commit
                                   outside governance -- HARD block,
                                   not merely low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                   whose op, rationale, summary,
                                   citations or value touches
                                   protective-custody/removal/
                                   involuntary-commitment/legal/
                                   clinical/benefits-eligibility/
                                   safety-authority territory is a
                                   HARD, PERMANENT block -- this
                                   actor's charter excludes that
                                   territory structurally, not as a
                                   rollout milestone. Evaluated
                                   UNCONDITIONALLY on every
                                   proposal. An op outside the
                                   closed five-op allowlist is the
                                   SAME failure mode (an advisor
                                   proposing something it was never
                                   authorized to propose) and is
                                   folded into this same check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-safety-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `socialworkops.phase` independently agrees: `:flag-safety-concern` is
  never a member of any phase's `:auto` set either -- two layers, not
  one."
  (:require [clojure.string :as str]
            [socialworkops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-client-contact-note :schedule-appointment :coordinate-referral
    :coordinate-benefits-application-assistance :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- protective custody/removal,
  involuntary commitment, legal representation, clinical diagnosis,
  benefits eligibility/award/denial, or safety-authority enforcement.
  Scanned across the proposal's op/summary/rationale/cites/value,
  never trusting the advisor's own framing of its intent."
  ["protective custody" "protective-custody" "親権喪失" "custody removal" "custody-removal"
   "adult protective" "adult-protective" "成人後見" "removal decision" "removal-decision"
   "involuntary commitment" "involuntary-commitment" "措置入院" "psychiatric commitment"
   "legal representation" "legal-representation" "弁護士" "attorney" "lawyer advice"
   "clinical diagnosis" "clinical-diagnosis" "臨床診断" "psychiatric assessment" "assessment"
   "benefits eligibility" "benefits-eligibility" "給付適格性" "award decision" "award-decision"
   "denial decision" "denial-decision" "給付拒否" "benefit denial" "benefit-denial"
   "safety authority" "safety-authority" "安全当局" "investigat" "enforcement" "違反" "compliance" "suspension"])

;; ----------------------------- checks -----------------------------

(defn- client-unverified-violations
  "The target client must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:client-id` claim without a store lookup."
  [{:keys [client-id]} st]
  (let [c (store/client st client-id)]
    (when-not (and c (:registered? c) (:verified? c))
      [{:rule :client-unverified
        :detail (str client-id " は未登録または未検証のクライアント -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches protective/legal/clinical/benefits/
  safety-authority territory, regardless of confidence or how clean
  every other check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "親権喪失/成人後見/措置入院/弁護士/臨床診断/給付適格性/安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a SocialWorkAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [client-id (or (:client-id proposal) (:client-id request))
        hard (into []
                   (concat (client-unverified-violations {:client-id client-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :client-id (:client-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
