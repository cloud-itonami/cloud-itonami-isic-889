(ns socialworkops.advisor
  "SocialWorkAdvisor -- the LLM-seam intelligence layer that surfaces
  coordination proposals for the SocialWorkGovernor to censor.

  The advisor is:
  - SEALED: a proposal-only interface, returning EDN data, never actuating
  - MONITORED: every proposal is logged for audit
  - GOVERNED: subject to independent hard checks by SocialWorkGovernor
  - TESTED: a mock implementation allows offline integration tests; the
    real implementation (seam) is a langchain.model binding

  The mock advisor is deterministic and runs offline. The real advisor
  would dispatch to an LLM (via langchain-clj's model interface).")

(defprotocol Advisor
  (-advise [this store request]
    "Return a proposal EDN map: {:op kw :summary string :rationale string
     :cites [..] :effect :propose :value {...} :confidence [0..1]}.
     The effect is ALWAYS :propose (a claim to coordinate, never to actuate).
     Confidence is [0..1], used by governor as a soft escalation threshold."))

(defn- mock-advisor-impl
  "A deterministic, offline mock advisor for testing. Returns a proposal
  based on the request's op, with clean proposal shape and confidence > 0.8."
  [{:keys [op client-id]}]
  {:op op
   :client-id client-id
   :summary (str "Proposed " (name op) " for client " client-id)
   :rationale "routine social work coordination"
   :cites [client-id]
   :effect :propose
   :value {}
   :confidence 0.85})

(defrecord MockAdvisor []
  Advisor
  (-advise [_this _store request]
    (mock-advisor-impl request)))

(defn mock-advisor
  "A deterministic, offline mock advisor for testing."
  []
  (->MockAdvisor))

(defn trace
  "An audit trace of an advisor's inference: what went in, what came out."
  [request proposal]
  {:t :advisor-proposal
   :request (dissoc request :context)
   :proposal proposal
   :confidence (:confidence proposal 0.0)})
