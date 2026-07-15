(ns socialworkops.governor-contract-test
  "Integration tests of the full governance stack: advisor -> governor
  -> phase gate. Tests end-to-end scenarios rather than isolated unit checks."
  (:require [clojure.test :refer [deftest is testing]]
            [socialworkops.advisor :as advisor]
            [socialworkops.governor :as governor]
            [socialworkops.phase :as phase]
            [socialworkops.store :as store]))

(deftest full-stack-clean-approval-path
  (testing "end-to-end: clean proposal from advisor -> governor -> phase decision"
    (let [s (store/mem-store {"c1" {:client-id "c1" :name "Alice" :registered? true :verified? true}})
          adv (advisor/mock-advisor)
          request {:op :log-client-contact-note :client-id "c1"}
          proposal (advisor/-advise adv s request)
          verdict (governor/check request {} proposal s)
          phase-result (phase/gate 3 request (phase/verdict->disposition verdict))]
      (is (false? (:hard? verdict)) "no governor hard violations")
      (is (false? (:escalate? verdict)) "no escalation needed")
      (is (= :commit (:disposition phase-result)) "phase 3 allows auto-commit"))))

(deftest full-stack-low-confidence-escalates
  (testing "low-confidence proposal escalates even if governor is otherwise clean"
    (let [s (store/mem-store {"c1" {:client-id "c1" :name "Alice" :registered? true :verified? true}})
          low-conf-proposal {:op :log-client-contact-note :client-id "c1"
                             :summary "s" :rationale "r" :cites ["c1"]
                             :effect :propose :value {} :confidence 0.4}
          verdict (governor/check {} {} low-conf-proposal s)
          phase-result (phase/gate 3 {} (phase/verdict->disposition verdict))]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)) "low confidence triggers escalation")
      (is (= :escalate (:disposition phase-result))))))

(deftest full-stack-safety-concern-always-escalates
  (testing "safety concerns always escalate, clean or not"
    (let [s (store/mem-store {"c1" {:client-id "c1" :name "Alice" :registered? true :verified? true}})
          safety-proposal {:op :flag-safety-concern :client-id "c1"
                           :summary "observed signs of neglect"
                           :rationale "r" :cites ["c1"]
                           :effect :propose :value {} :confidence 0.95}
          verdict (governor/check {} {} safety-proposal s)
          phase-result (phase/gate 3 {} (phase/verdict->disposition verdict))]
      (is (false? (:hard? verdict)) "safety concern itself is not a hard violation")
      (is (true? (:high-stakes? verdict)) "safety concern is flagged as high-stakes")
      (is (true? (:escalate? verdict)) "always escalates")
      (is (= :escalate (:disposition phase-result))))))

(deftest full_stack_unverified_client_is_hard_hold
  (testing "unverified client causes hard hold at all phases"
    (let [s (store/mem-store {"c3" {:client-id "c3" :name "Carol" :registered? true :verified? false}})
          proposal {:op :log-client-contact-note :client-id "c3"
                   :summary "s" :rationale "r" :cites ["c3"]
                   :effect :propose :value {} :confidence 0.95}
          verdict (governor/check {} {} proposal s)]
      (is (true? (:hard? verdict)))
      (is (some #{:client-unverified} (map :rule (:violations verdict))))
      (doseq [p [0 1 2 3]]
        (let [phase-result (phase/gate p {} (phase/verdict->disposition verdict))]
          (is (= :hold (:disposition phase-result)) (str "phase " p)))))))

(deftest full-stack-scope-excluded-is-permanent
  (testing "scope-excluded proposals are HARD-held, can't be auto-committed at any phase"
    (let [s (store/mem-store {"c1" {:client-id "c1" :name "Alice" :registered? true :verified? true}})
          poisoned {:op :log-client-contact-note :client-id "c1"
                   :summary "assess for protective-custody removal decision"
                   :rationale "r" :cites ["c1"]
                   :effect :propose :value {} :confidence 0.95}
          verdict (governor/check {} {} poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict))))
      (doseq [p [0 1 2 3]]
        (let [phase-result (phase/gate p {} (phase/verdict->disposition verdict))]
          (is (= :hold (:disposition phase-result)) (str "phase " p)))))))

(deftest full-stack-phase-gating-requires-approval
  (testing "phase 1 requires approval even for clean advisor proposals"
    (let [s (store/mem-store {"c1" {:client-id "c1" :name "Alice" :registered? true :verified? true}})
          adv (advisor/mock-advisor)
          request {:op :log-client-contact-note :client-id "c1"}
          proposal (advisor/-advise adv s request)
          verdict (governor/check request {} proposal s)
          phase-result (phase/gate 1 request (phase/verdict->disposition verdict))]
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict)) "governor is clean")
      (is (= :escalate (:disposition phase-result)) "phase 1 still requires approval"))))

(deftest full-stack-all-valid-ops-work
  (testing "all five valid ops can commit at phase 3 with high confidence"
    (let [s (store/mem-store {"c1" {:client-id "c1" :name "Alice" :registered? true :verified? true}})
          ops [:log-client-contact-note :schedule-appointment :coordinate-referral
               :coordinate-benefits-application-assistance]
          make-proposal (fn [op] {:op op :client-id "c1" :summary "s" :rationale "r"
                                  :cites ["c1"] :effect :propose :value {} :confidence 0.85})]
      (doseq [op ops]
        (let [proposal (make-proposal op)
              verdict (governor/check {} {} proposal s)
              phase-result (phase/gate 3 {} (phase/verdict->disposition verdict))]
          (is (= :commit (:disposition phase-result)) (str "op " op)))))))
