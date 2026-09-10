(ns socialworkops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [socialworkops.phase :as phase]))

(deftest read-only-no-writes
  (testing "phase 0 (read-only) never auto-commits anything"
    (let [request {:op :log-client-contact-note :client-id "c1"}
          result (phase/gate 0 request :commit)]
      (is (= :hold (:disposition result)))
      (is (= :phase-disabled (:reason result))))))

(deftest phase-1-logging-only
  (testing "phase 1 allows :log-client-contact-note but requires approval"
    (let [request {:op :log-client-contact-note :client-id "c1"}
          result (phase/gate 1 request :commit)]
      (is (= :escalate (:disposition result)))
      (is (= :phase-approval (:reason result))))))

(deftest phase-1-other-ops-blocked
  (testing "phase 1 blocks non-logging ops even if governor approves"
    (let [request {:op :schedule-appointment :client-id "c1"}
          result (phase/gate 1 request :commit)]
      (is (= :hold (:disposition result)))
      (is (= :phase-disabled (:reason result))))))

(deftest phase-3-auto-commits-logging
  (testing "phase 3 allows auto-commit for logging ops that pass governor"
    (let [request {:op :log-client-contact-note :client-id "c1"}
          result (phase/gate 3 request :commit)]
      (is (= :commit (:disposition result)))
      (is (nil? (:reason result))))))

(deftest phase-3-auto-commits-scheduling
  (testing "phase 3 allows auto-commit for appointment scheduling"
    (let [request {:op :schedule-appointment :client-id "c1"}
          result (phase/gate 3 request :commit)]
      (is (= :commit (:disposition result))))))

(deftest phase-3-auto-commits-referral
  (testing "phase 3 allows auto-commit for referral coordination"
    (let [request {:op :coordinate-referral :client-id "c1"}
          result (phase/gate 3 request :commit)]
      (is (= :commit (:disposition result))))))

(deftest phase-3-auto-commits-benefits-assistance
  (testing "phase 3 allows auto-commit for benefits-application assistance"
    (let [request {:op :coordinate-benefits-application-assistance :client-id "c1"}
          result (phase/gate 3 request :commit)]
      (is (= :commit (:disposition result))))))

(deftest phase-3-never-auto-commits-safety
  (testing "phase 3 NEVER auto-commits safety concerns, regardless of phase"
    (let [request {:op :flag-safety-concern :client-id "c1"}
          result (phase/gate 3 request :commit)]
      (is (= :escalate (:disposition result)))
      (is (= :phase-approval (:reason result))))))

(deftest governor-hold-always-stays-hold
  (testing "governor HOLD overrides every phase"
    (doseq [p [0 1 2 3]]
      (let [request {:op :log-client-contact-note :client-id "c1"}
            result (phase/gate p request :hold)]
        (is (= :hold (:disposition result)))
        (is (nil? (:reason result)) (str "phase " p))))))

(deftest verdict->disposition-hard
  (testing "HARD violation maps to :hold"
    (let [verdict {:hard? true :escalate? false}
          result (phase/verdict->disposition verdict)]
      (is (= :hold result)))))

(deftest verdict->disposition-escalate
  (testing "escalation (not hard) maps to :escalate"
    (let [verdict {:hard? false :escalate? true}
          result (phase/verdict->disposition verdict)]
      (is (= :escalate result)))))

(deftest verdict->disposition-commit
  (testing "clean verdict maps to :commit"
    (let [verdict {:hard? false :escalate? false}
          result (phase/verdict->disposition verdict)]
      (is (= :commit result)))))
