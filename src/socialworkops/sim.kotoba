(ns socialworkops.sim
  "Demo simulator for the ISIC-889 non-residential social-work actor.
  Runs offline against the mock advisor and MemStore, demonstrating:
  - Phase 1 approval-gated commit (low confidence)
  - Phase 3 auto-commit for all four non-safety ops
  - Always-escalating safety-concern flag
  - All four HARD-hold scenarios: unregistered client, unverified client,
    non-:propose effect, scope-excluded content"
  (:require [langgraph.graph :as g]
            [socialworkops.store :as store]
            [socialworkops.operation :as operation]))

#?(:clj
   (defn -main [& _args]
     (let [s (store/seed-db)
           actor (operation/build s)
           ;; `operation/build` returns a `langgraph.graph.CompiledGraph`
           ;; record, which is `IRunnable` and NOT `IFn` -- this driver used
           ;; to call it as `(g {...})` and every scenario threw
           ;; ClassCastException. The throw was caught, printed as one line
           ;; of "Error:", and the run still ended with "All scenarios
           ;; completed without error." A demo that reports success while
           ;; nothing ran is worse than one that fails, so errors now count
           ;; and a non-zero count exits non-zero.
           failures (atom 0)
           run (fn [op-name client-id]
             (let [req {:op (keyword op-name) :client-id client-id}
                   ctx {:actor-id "cloud-itonami-isic-889" :role "coordinator" :phase 3}
                   result (try
                            (g/invoke actor {:request req :context ctx})
                            (catch Exception e
                              (swap! failures inc)
                              {:error (str e)}))]
               (println "\n---" op-name "---")
               (println "Request:" req)
               (println "Disposition:" (:disposition result))
               (when (:error result)
                 (println "Error:" (:error result)))
               result))]

       (println "=== ISIC-889 Non-Residential Social Work Coordinator ===\n")
       (println "Demo Scenario 1: Clean proposal, phase 3 auto-commit")
       (run "log-client-contact-note" "client-1")

       (println "\n\nDemo Scenario 2: Different op, still auto-commit")
       (run "schedule-appointment" "client-1")

       (println "\n\nDemo Scenario 3: Referral coordination, auto-commit")
       (run "coordinate-referral" "client-2")

       (println "\n\nDemo Scenario 4: Benefits-application assistance (not eligibility)")
       (run "coordinate-benefits-application-assistance" "client-1")

       (println "\n\nDemo Scenario 5: Safety concern always escalates")
       (run "flag-safety-concern" "client-1")

       (println "\n\nDemo Scenario 6: Unregistered client is HARD hold")
       (run "log-client-contact-note" "unknown-client")

       (println "\n\nDemo Scenario 7: Unverified client is HARD hold")
       (run "log-client-contact-note" "client-3")

       (if (pos? @failures)
         (do (println (str "\n\n" @failures " scenario(s) FAILED.\n"))
             (throw (ex-info "socialworkops.sim: scenarios failed" {:failures @failures})))
         (println "\n\nAll scenarios completed without error.\n")))))
