(ns socialworkops.store
  "SSoT for the ISIC-889 non-residential social-work COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates non-residential community social work: counseling,
  referrals, welfare/benefits-application assistance. It never touches
  child or adult protective-custody/removal decisions, involuntary
  commitment, legal representation decisions, or clinical diagnosis --
  see `socialworkops.governor`'s `scope-exclusion-violations`, a HARD,
  permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `clients` directory keyed by `:client-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified client record must exist before ANY proposal
  for that client may ever commit or escalate -- `socialworkops.governor`'s
  `client-unverified-violations` re-derives this from the client's own
  `:registered?`/`:verified?` fields, never from proposal self-report,
  the SAME 'ground truth, not self-report' discipline every sibling
  actor's own governor uses.

  The ledger stays append-only: which client a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (client [s client-id] "Registered client record, or nil.
    Client map: {:client-id .. :name .. :registered? bool :verified? bool}.")
  (all-clients [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-clients [s clients] "replace/seed the client directory (map client-id->client)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained client directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:clients
   {"client-1" {:client-id "client-1" :name "Alice Johnson (DOB 1975)"
                 :registered? true :verified? true}
    "client-2" {:client-id "client-2" :name "Bob Chen (DOB 1980)"
                 :registered? true :verified? true}
    "client-3" {:client-id "client-3" :name "Carol Smith (DOB 1985, in intake)"
                 :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (all-clients [_] (sort-by :client-id (vals (:clients @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-clients [s clients] (when (seq clients) (swap! a assoc :clients clients)) s))

(defn seed-db
  "A MemStore seeded with the demo client directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `clients` map (client-id string ->
  client map) -- the primary test/dev entry point. `clients` may be empty
  (an unregistered-everywhere store)."
  [clients]
  (->MemStore (atom {:clients (or clients {}) :ledger [] :coordination-log []})))
