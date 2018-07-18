(ns metabase.sync.analyze.fingerprint.fingerprinters
  "Non-identifying fingerprinters for various field types."
  (:require [cheshire.core :as json]
            [clj-time
             [coerce :as t.coerce]
             [core :as t]]
            [kixi.stats.core :as stats]
            [medley.core :as m]
            [metabase.sync.util :as sync-util]
            [metabase.util :as u]
            [metabase.util.date :as du]
            [redux.core :as redux])
  (:import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus))

(defn col-wise
  "Apply reducing functinons `rfs` coll-wise to a seq of seqs."
  [& rfs]
  (fn
    ([]
     (mapv (fn [rf]
             (rf))
           rfs))
    ([acc]
     (mapv (fn [rf acc]
             (rf acc))
           rfs acc))
    ([acc e]
     (mapv (fn [rf acc e]
             (rf acc e))
           rfs acc e))))

(defn- monoid
  [f init]
  (fn
    ([] init)
    ([acc] (f acc))
    ([acc x] (f acc x))))

(defn- share
  [pred]
  (fn
    ([]
     {:match 0
      :total 0})
    ([{:keys [match total]}]
     (/ match (max total 1)))
    ([{:keys [match total]} e]
     {:match (cond-> match
               (pred e) inc)
      :total (inc total)})))

(defn- cardinality
  "Transducer that sketches cardinality using HyperLogLog++.
   https://research.google.com/pubs/pub40671.html"
  ([] (HyperLogLogPlus. 14 25))
  ([^HyperLogLogPlus acc] (.cardinality acc))
  ([^HyperLogLogPlus acc x]
   (.offer acc x)
   acc))

(defmulti
  ^{:doc "Return a fingerprinter transducer for a given field based on the field's type."
    :arglists '([field])}
  fingerprinter (juxt :base_type (some-fn :special_type (constantly :type/*))))

(def ^:private global-fingerprinter
  (redux/fuse {:distinct-count cardinality}))

(defmethod fingerprinter :default
  [_]
  (redux/post-complete global-fingerprinter (partial hash-map :global)))

(defmethod fingerprinter [:type/* :type/FK]
  [_]
  (redux/post-complete global-fingerprinter (partial hash-map :global)))

(prefer-method fingerprinter [:type/* :type/FK] [:type/Number :type/*])
(prefer-method fingerprinter [:type/* :type/FK] [:type/Text :type/*])

(defn- with-global-fingerprinter
  [prefix fingerprinter]
  (redux/post-complete
   (redux/juxt
    fingerprinter
    global-fingerprinter)
   (fn [[type-fingerprint global-fingerprint]]
     {:global global-fingerprint
      :type   {prefix type-fingerprint}})))

(defmacro ^:private with-reduced-error
  [msg & body]
  `(let [result# (sync-util/with-error-handling ~msg ~@body)]
     (if (instance? Exception result#)
       (reduced result#)
       result#)))

(defn- with-error-handling
  [rf msg]
  (fn
    ([] (with-reduced-error msg (rf)))
    ([acc] (with-reduced-error msg (rf acc)))
    ([acc e] (with-reduced-error msg (rf acc e)))))

(defmacro ^:private deffingerprinter
  [type transducer]
  (let [type (if (vector? type)
               type
               [type :type/*])]
    `(defmethod fingerprinter ~type
       [field#]
       (with-error-handling
         (with-global-fingerprinter (first ~type) ~transducer)
         (format "Error generating fingerprint for %s" (sync-util/name-for-logging field#))))))

(deffingerprinter :type/DateTime
  ((keep du/str->date-time)
   (redux/post-complete
    (redux/fuse {:earliest (monoid t/min-date (t.coerce/from-long Long/MAX_VALUE))
                 :latest   (monoid t/max-date (t.coerce/from-long 0))})
    (partial m/map-vals str))))

(deffingerprinter :type/Number
  ((remove nil?)
   (redux/fuse {:min (monoid min Double/POSITIVE_INFINITY)
                :max (monoid max Double/NEGATIVE_INFINITY)
                :avg stats/mean})))

(defn- valid-serialized-json?
  "True if X is a serialized JSON dictionary or array."
  [x]
  (boolean
   (when-let [parsed-json (json/parse-string x)]
     (or (map? parsed-json)
         (sequential? parsed-json)))))

(deffingerprinter :type/Text
  (redux/fuse {:percent-json   (share valid-serialized-json?)
               :percent-url    (share u/url?)
               :percent-email  (share u/email?)
               :average-length ((map (comp count str)) stats/mean)}))

(defn fingerprint-fields
  "Return a transducer for fingerprinting a resultset with fields `fields`."
  [fields]
  (apply col-wise (map fingerprinter fields)))