(ns socialworkops.governor-test
  "Pure unit tests of `socialworkops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [socialworkops.governor :as gov]
            [socialworkops.store :as store]))

(def client-1 {:client-id "client-1" :name "Alice Johnson" :registered? true :verified? true})
(def client-3 {:client-id "client-3" :name "Carol Smith" :registered? true :verified? false})

(defn- clean-proposal [op client-id]
  {:op op :client-id client-id :summary "s" :rationale "routine social work coordination"
   :cites [client-id] :effect :propose :value {} :confidence 0.85})

(deftest client-unregistered-is-hard
  (testing "no client record at all -> HARD hold"
    (let [s (store/mem-store {"client-1" client-1})
          verdict (gov/check {} nil (clean-proposal :log-client-contact-note "unknown-client") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:client-unverified} (map :rule (:violations verdict)))))))

(deftest client-unverified-is-hard
  (testing "client registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"client-3" client-3})
          verdict (gov/check {} nil (clean-proposal :log-client-contact-note "client-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:client-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"client-1" client-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-appointment "client-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed five-op allowlist is a scope violation"
    (let [s (store/mem-store {"client-1" client-1})
          verdict (gov/check {} nil (clean-proposal :adjust-benefits-plan "client-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest protective-custody-content-is-hard-and-permanent
  (testing "a proposal touching protective-custody territory is HARD-blocked"
    (let [s (store/mem-store {"client-1" client-1})
          poisoned (assoc (clean-proposal :log-client-contact-note "client-1")
                          :rationale "assessed for protective custody removal decision"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest clinical-diagnosis-content-is-hard
  (testing "a proposal touching clinical diagnosis/assessment is HARD-blocked"
    (let [s (store/mem-store {"client-1" client-1})
          poisoned (assoc (clean-proposal :log-client-contact-note "client-1")
                          :rationale "psychiatric assessment and clinical diagnosis completed"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest benefits-eligibility-content-is-hard
  (testing "a proposal touching benefits eligibility determination is HARD-blocked"
    (let [s (store/mem-store {"client-1" client-1})
          poisoned (assoc (clean-proposal :coordinate-benefits-application-assistance "client-1")
                          :summary "determine benefits eligibility and make award decision")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest involuntary-commitment-content-is-hard
  (testing "a proposal touching involuntary commitment is HARD-blocked"
    (let [s (store/mem-store {"client-1" client-1})
          poisoned (assoc (clean-proposal :schedule-appointment "client-1")
                          :value {:decision "recommend involuntary commitment to psychiatric facility"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legal-representation-content-is-hard
  (testing "a proposal touching legal representation is HARD-blocked"
    (let [s (store/mem-store {"client-1" client-1})
          poisoned (assoc (clean-proposal :coordinate-referral "client-1")
                          :summary "provide legal representation and attorney advice")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-safety-concern-is-not-scope-excluded
  (testing "flagging observed welfare/safety concerns (not clinical diagnosis) never trips scope-exclusion -- this actor's core valid use case"
    (let [s (store/mem-store {"client-1" client-1})
          concern (assoc (clean-proposal :flag-safety-concern "client-1")
                         :value {:concern "client disclosed signs of neglect and food insecurity"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (welfare/safety signals) is exactly what this op exists to surface"))))
