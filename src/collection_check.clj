(ns collection-check
  (:use
    [clojure.pprint])
  (:require
    [clojure.string :as str]
    [simple-check.generators :as gen]
    [simple-check.properties :as prop]
    [simple-check.core :refer (quick-check)])
  (:import
    [java.util Collection List Map]))

(set! *warn-on-reflection* false)

;;;

(defn- tuple* [& args]
  (->> args
    (map
      #(if (and (map? %) (contains? % :gen))
         %
         (gen/return %)))
    (apply gen/tuple)))

(defn- ttuple [& args]
  (gen/tuple (apply tuple* args)))

;;;

;; These generators produce lists of action-group tuples. Action
;; groups are discrete manipulation sequences where intermediate
;; results should not be compared. The elements of an action group are
;; actions, which are tuples of values where the value in head
;; position is a keyword indicating some manipulation to perform and
;; the rest of the values are arguments for that manipulation.

(defn- gen-vector-actions [element-generator transient?]
  (let [standard [(ttuple :pop)
                  (ttuple :conj element-generator)
                  (ttuple :assoc (gen/choose 0 1e3) element-generator)]
        transient (gen/fmap
                    (fn [[pre actions post]]
                      (concat [pre] actions [post]))
                    (tuple*
                      [:transient]
                      (gen/list
                        (gen/one-of
                          [(tuple* :conj! element-generator)
                           (tuple* :assoc! (gen/choose 0 999) element-generator)
                           (tuple* :pop!)]))
                      [:persistent!]))]
    (gen/list
      (gen/one-of
        (if transient?
          (conj standard transient)
          standard)))))

(defn- gen-map-actions [key-generator value-generator transient?]
  (let [standard [(ttuple :dissoc key-generator)
                  (ttuple :assoc key-generator value-generator)]
        transient (gen/fmap
                    (fn [[pre actions post]]
                      (concat [pre] actions [post]))
                    (tuple*
                      [:transient]
                      (gen/list
                        (gen/one-of
                          [(tuple* :dissoc! key-generator)
                           (tuple* :assoc! key-generator value-generator)]))
                      [:persistent!]))]
    (gen/list
      (gen/one-of
        (if transient?
          (conj standard transient)
          standard)))))

(defn- gen-set-actions [element-generator transient?]
  (let [standard [(ttuple :conj element-generator)
                  (ttuple :disj element-generator)]
        transient (gen/fmap
                    (fn [[pre actions post]]
                      (concat [pre] actions [post]))
                    (tuple*
                      [:transient]
                      (gen/list
                        (gen/one-of
                          [(tuple* :conj! element-generator)
                           (tuple* :disj! element-generator)]))
                      [:persistent!]))]
    (gen/list
      (gen/one-of
        (if transient?
          (conj standard transient)
          standard)))))

(defn- transient? [x]
  (instance? clojure.lang.IEditableCollection x))

(defn- build-collections
  "Given a list of actions, constructs two parallel collections that can be compared
   for equivalencies."
  [coll-a coll-b vector-like? actions]
  (let [transient? (and (transient? coll-a) (transient? coll-b))]
    (reduce
      (fn [[coll-a coll-b prev-actions] [action x y :as act]]
        ;; if it's a vector, we need to choose a valid index
        (let [idx (when (and vector-like? (#{:assoc :assoc!} action))
                    (if (< 900 x)
                      (count coll-a)
                      (int (* (count coll-a) (/ x 1e3)))))
              [action x y :as act] (if idx
                                     [action idx y]
                                     act)
              f (case action
                  :persistent! persistent!
                  :transient transient
                  :pop #(if (empty? %) % (pop %))
                  :pop! #(if (= 0 (count %)) % (pop! %))
                  :conj #(conj % x)
                  :conj! #(conj! % x)
                  :disj #(disj % x)
                  :disj! #(disj! % x)
                  :assoc #(assoc % x y)
                  :assoc! #(assoc! % x y)
                  :dissoc #(dissoc % x)
                  :dissoc! #(dissoc! % x))]
          [(f coll-a)
           (f coll-b)
           (conj prev-actions act)]))
      [coll-a coll-b []]
      (apply concat actions))))

;;;

;; indirection for 1.4 compatibility
(def reduced* (ns-resolve 'clojure.core 'reduced))

(defn assert-equivalent-collections
  [a b]
  (assert (= (count a) (count b) (.size a) (.size b)))
  (assert (= a b))
  (assert (= b a))
  (assert (.equals ^Object a b))
  (assert (.equals ^Object b a))
  (assert (= (hash a) (hash b)))
  (assert (= (.hashCode ^Object a) (.hashCode ^Object b)))
  (assert (= a b
            (into (empty a) a)
            (into (empty b) b)
            (into (empty a) b)
            (into (empty b) a)))
  (when reduced*
    (assert (= (into (empty a) (take 1 a))
               (reduce #(reduced* (conj %1 %2)) (empty a) a)))
    (assert (= (into (empty b) (take 1 b))
               (reduce #(reduced* (conj %1 %2)) (empty b) b)))))

(defn assert-equivalent-vectors [a b]
  (assert-equivalent-collections a b)
  (assert (= (first a) (first b)))
  (assert (= (map #(nth a %) (range (count a)))
            (map #(nth b %) (range (count b)))))
  (assert (= (map #(a %) (range (count a)))
            (map #(b %) (range (count b)))))
  (assert (= (map #(get a %) (range (count a)))
            (map #(get b %) (range (count b)))))
  (assert (= (map #(.get ^List a %) (range (count a)))
            (map #(.get ^List b %) (range (count b)))))
  (assert (= 0 (compare a b))))

(defn assert-equivalent-sets [a b]
  (assert-equivalent-collections a b)
  (assert (= (set (map #(a %) a))
            (set (map #(b %) b))))
  (assert (and
            (every? #(contains? a %) b)
            (every? #(contains? b %) a))))

(defn assert-equivalent-maps [a b]
  (assert-equivalent-collections a b)
  (assert (= (set (keys a)) (set (keys b))))
  (let [ks (keys a)]
    (assert (= (map #(get a %) ks)
              (map #(get b %) ks)))
    (assert (= (map #(a %) ks)
              (map #(b %) ks)))
    (assert (= (map #(.get ^Map a %) ks)
              (map #(.get ^Map b %) ks))))
  (assert (and
            (every? #(= (key %) (first %)) a)
            (every? #(= (key %) (first %)) b)))
  (assert (every? #(= (val %) (a (key %)) (b (key %))) a)))

;;;

(defn- assert-not-failed [x]
  (if (:fail x)
    (let [[actions] (-> x :shrunk :smallest)]
      (throw (Exception.
               (str (.getMessage ^Throwable (:result x))
                 "\n  actions = " (->> actions
                                    (apply concat)
                                    (map (fn [[f & rst]]
                                           (if (empty? rst)
                                             (symbol (name f))
                                             (list* (symbol (name f)) rst))))
                                    (list* '-> 'coll)
                                    pr-str))
               (:result x))))
    x))

(defn assert-vector-like
  "Asserts that the given empty collection behaves like a vector."
  ([empty-coll element-generator]
     (assert-vector-like 1e3 empty-coll element-generator))
  ([n empty-coll element-generator]
     (assert-not-failed
       (quick-check n
         (prop/for-all [actions (gen-vector-actions element-generator (transient? empty-coll))]
           (let [[a b actions] (build-collections empty-coll [] true actions)]
             (assert-equivalent-vectors a b)
             true))))))

(defn assert-set-like
  ([empty-coll element-generator]
     (assert-set-like 1e3 empty-coll element-generator))
  ([n empty-coll element-generator]
     (assert-not-failed
       (quick-check n
         (prop/for-all [actions (gen-set-actions element-generator (transient? empty-coll))]
           (let [[a b actions] (build-collections empty-coll #{} false actions)]
             (assert-equivalent-sets a b)
             true))))))

(defn assert-map-like
  ([empty-coll key-generator value-generator]
     (assert-map-like 1e3 empty-coll key-generator value-generator))
  ([n empty-coll key-generator value-generator]
     (assert-not-failed
       (quick-check n
         (prop/for-all [actions (gen-map-actions key-generator value-generator (transient? empty-coll))]
           (let [[a b actions] (build-collections empty-coll {} false actions)]
             (assert-equivalent-maps a b)
             true))))))

(defn- gen-iterator-actions [removable?]
  (let [standard [(ttuple :.hasNext)
                  (ttuple :.next)]
        removable (ttuple :.remove)]
    (gen/list
     (gen/one-of
      (if removable?
        (conj standard removable)
        standard)))))

(defn interpret-iterator-actions
  "Convert iterator action tuples into [subject, return] function calls."
  [action-groups]
  (for [group action-groups]
    (vec (for [[action] group]
           (case action
             :.hasNext  #(vector % (.hasNext %))
             :.next     #(vector % (.next %))
             :.remove   #(vector % (.remove %)))))))

(defn apply-fn-group!
  "Apply [subject, return] fns and yield final {:subject, :return}
or {:throw}."
  [subject fn-group]
  (try
    (reduce (fn [{:keys [subject return]} f]
              ;; throws away intermediate :return values
              (zipmap [:subject :return]
                      (f subject)))
            {:subject subject :return nil}
            fn-group)
    (catch Throwable t
      {:throw (class t)})))

(defn apply-fn-groups!
  "Apply [subject, return] function groups to an object, yielding a
sequence of return values and possibly a terminal exception, one per
group (until throws.)"
  [subject fn-groups]
  (:results
   (reduce (fn [{:keys [subject done results] :as accum} fn-group]
             (if done
               accum
               (let [result (apply-fn-group! subject fn-group)]
                 (if (contains? result :throw)
                   {:done true
                    :results (conj results (select-keys result [:throw]))}
                   {:subject (:subject result)
                    :results (conj results (select-keys result [:return]))}))))
           {:subject subject, :results [], :done false}
           fn-groups)))

(defn gen-iterator-like
  "A generator version of assert-iterator-like. Run quick-check on it."
  [seq-gen iterator-of removable?]
  (prop/for-all [action-groups (gen-iterator-actions removable?)
                 target-seq seq-gen]
    (let [reference (.iterator (if removable?
                                 (java.util.ArrayList. target-seq)
                                 target-seq))
          under-test (iterator-of target-seq)
          action-group-fs (interpret-iterator-actions action-groups)]
      (let [res-r (apply-fn-groups! reference  action-group-fs)
            res-t (apply-fn-groups! under-test action-group-fs)]
        (assert (= res-r res-t)))
      true)))

(defn assert-iterator-like
  "Given a sequence generator, a function to make an iterator (for a
target output sequence), and a boolean indicating whether elements
should be removable, assert that the iterators behave as expected.

An example seq-gen is (gen/list gen/int)"
  ([seq-gen iterator-of removable?]
     (assert-iterator-like 1e3 seq-gen iterator-of removable?))
  ([n seq-gen iterator-of removable?]
     (assert-not-failed
       (quick-check n
         (gen-iterator-like seq-gen iterator-of removable?)))))
