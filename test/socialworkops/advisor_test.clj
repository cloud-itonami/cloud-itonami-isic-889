(ns socialworkops.advisor-test
  "Tests of `socialworkops.advisor/Advisor` contract and mock shapes.
  The mock advisor is deterministic and returns clean proposals; a
  real advisor would be seeded with a langchain LLM model."
  (:require [clojure.test :refer [deftest is testing]]
            [socialworkops.advisor :as advisor]
            [socialworkops.store :as store]))

(deftest mock-advisor-returns-proposal-shape
  (testing "mock advisor returns a map with all required proposal fields"
    (let [mock (advisor/mock-advisor)
          s (store/mem-store {})
          request {:op :log-client-contact-note :client-id "c1"}
          proposal (advisor/-advise mock s request)]
      (is (map? proposal))
      (is (:op proposal))
      (is (:client-id proposal))
      (is (:summary proposal))
      (is (:rationale proposal))
      (is (:cites proposal))
      (is (= :propose (:effect proposal)))
      (is (number? (:confidence proposal))))))

(deftest mock-advisor-always-propose-effect
  (testing "mock advisor always returns :effect :propose (never actuates)"
    (let [mock (advisor/mock-advisor)
          s (store/mem-store {})
          reqs [{:op :log-client-contact-note :client-id "c1"}
                {:op :schedule-appointment :client-id "c2"}
                {:op :coordinate-referral :client-id "c3"}
                {:op :coordinate-benefits-application-assistance :client-id "c1"}]]
      (doseq [req reqs]
        (let [proposal (advisor/-advise mock s req)]
          (is (= :propose (:effect proposal))
              (str "request " req)))))))

(deftest mock-advisor-confidence-in-range
  (testing "mock advisor confidence is always in [0..1]"
    (let [mock (advisor/mock-advisor)
          s (store/mem-store {})
          request {:op :log-client-contact-note :client-id "c1"}
          proposal (advisor/-advise mock s request)]
      (is (<= 0 (:confidence proposal) 1)))))

(deftest mock-advisor-cites-client
  (testing "mock advisor cites the client as a basis"
    (let [mock (advisor/mock-advisor)
          s (store/mem-store {})
          request {:op :log-client-contact-note :client-id "c1"}
          proposal (advisor/-advise mock s request)]
      (is (some #{"c1"} (:cites proposal))))))

(deftest trace-captures-proposal
  (testing "trace captures both request and proposal for audit"
    (let [request {:op :log-client-contact-note :client-id "c1"}
          proposal {:op :log-client-contact-note :summary "s" :confidence 0.85 :effect :propose}
          trace (advisor/trace request proposal)]
      (is (= :advisor-proposal (:t trace)))
      (is (= :log-client-contact-note (get-in trace [:request :op])))
      (is (= 0.85 (get-in trace [:proposal :confidence]))))))
