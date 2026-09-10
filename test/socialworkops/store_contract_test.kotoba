(ns socialworkops.store-contract-test
  "Contract tests for `socialworkops.store/Store` -- ensuring all
  Store implementations (MemStore, future DatomicStore) satisfy the
  protocol's contract: client directory by string ID, append-only ledger,
  append-only coordination log."
  (:require [clojure.test :refer [deftest is testing]]
            [socialworkops.store :as store]))

(deftest memstore-empty-store
  (testing "a new MemStore with no clients returns nil for any lookup"
    (let [s (store/mem-store {})]
      (is (nil? (store/client s "nonexistent")))
      (is (empty? (store/all-clients s)))
      (is (empty? (store/ledger s)))
      (is (empty? (store/coordination-log s))))))

(deftest memstore-seed-and-lookup
  (testing "a MemStore seeded with clients can retrieve them by string ID"
    (let [clients {"c1" {:client-id "c1" :name "Alice"}
                   "c2" {:client-id "c2" :name "Bob"}}
          s (store/mem-store clients)]
      (is (= "Alice" (:name (store/client s "c1"))))
      (is (= "Bob" (:name (store/client s "c2"))))
      (is (nil? (store/client s "c3"))))))

(deftest memstore-all-clients-sorted
  (testing "all-clients returns clients in sorted order by client-id"
    (let [clients {"c3" {:client-id "c3" :name "Carol"}
                   "c1" {:client-id "c1" :name "Alice"}
                   "c2" {:client-id "c2" :name "Bob"}}
          s (store/mem-store clients)
          all (store/all-clients s)]
      (is (= ["c1" "c2" "c3"] (map :client-id all))))))

(deftest memstore-append-ledger-immutable
  (testing "append-ledger! accumulates decision facts in order"
    (let [s (store/mem-store {})
          f1 {:t :proposal :client-id "c1"}
          f2 {:t :approved :client-id "c1"}]
      (store/append-ledger! s f1)
      (store/append-ledger! s f2)
      (let [ledger (store/ledger s)]
        (is (= 2 (count ledger)))
        (is (= :proposal (:t (first ledger))))
        (is (= :approved (:t (second ledger))))))))

(deftest memstore-commit-record-appends
  (testing "commit-record! appends to coordination-log and returns the record"
    (let [s (store/mem-store {})
          rec1 {:op :log-client-contact-note :client-id "c1" :value {:note "test"}}
          rec2 {:op :schedule-appointment :client-id "c2" :value {}}]
      (store/commit-record! s rec1)
      (store/commit-record! s rec2)
      (let [log (store/coordination-log s)]
        (is (= 2 (count log)))
        (is (= rec1 (first log)))
        (is (= rec2 (second log)))))))

(deftest memstore-with-clients-replaces
  (testing "with-clients replaces the client directory and returns the store"
    (let [s1 (store/mem-store {"c1" {:client-id "c1" :name "Alice"}})
          new-clients {"c2" {:client-id "c2" :name "Bob"}}
          s2 (store/with-clients s1 new-clients)]
      (is (identical? s1 s2) "returns the same store object")
      (is (nil? (store/client s2 "c1")))
      (is (= "Bob" (:name (store/client s2 "c2")))))))

(deftest memstore-seeded-vs-empty
  (testing "a seeded MemStore and an explicitly configured one behave the same"
    (let [seeded (store/seed-db)
          explicit (store/mem-store (into {}
                                          (map #(vector (:client-id %) %)
                                               (store/all-clients seeded))))]
      (is (= (count (store/all-clients seeded))
             (count (store/all-clients explicit))))
      (doseq [c (store/all-clients seeded)]
        (is (= c (store/client explicit (:client-id c))))))))
