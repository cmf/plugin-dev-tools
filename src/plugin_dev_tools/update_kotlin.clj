(ns plugin-dev-tools.update-kotlin
  (:refer-clojure :exclude [ensure update])
  (:require [borkdude.rewrite-edn :as rewrite]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn base-key [key]
  (if-let [index (str/index-of (name key) \$)]
    (symbol (namespace key) (subs (name key) 0 index))
    key))

(defn kotlin-key? [key]
  (and (= "org.jetbrains.kotlin" (namespace key))
       (or (#{"kotlin-serialization-compiler-plugin"} (name key))
           (str/starts-with? (name key) "kotlin-test")
           (str/starts-with? (name key) "kotlin-stdlib")
           (str/starts-with? (name key) "kotlin-reflect")
           (str/starts-with? (name key) "kotlin-compiler"))))

(defn serialization-key? [key]
  (and (= "org.jetbrains.kotlinx" (namespace key))
       (str/starts-with? (name key) "kotlinx-serialization")))

(defn coroutines-key? [key]
  (and (or (= "org.jetbrains.kotlinx" (namespace key))
           (= "com.intellij.platform" (namespace key)))
       (str/starts-with? (name key) "kotlinx-coroutines")))

(defn ksp-key? [key]
  (and (= "com.google.devtools.ksp" (namespace key))
       (str/starts-with? (name key) "symbol-processing")))

(defn update-deps-map [nodes deps-map path versions]
  (if deps-map
    (let [{:keys [kotlin-version serialization-version coroutines-version ksp-version]} versions]
      (reduce (fn [nodes key]
                (let [base (base-key key)]
                  (cond
                    (kotlin-key? base)
                    (rewrite/assoc-in nodes (into path [key :mvn/version]) kotlin-version)
                    (serialization-key? base)
                    (rewrite/assoc-in nodes (into path [key :mvn/version]) serialization-version)
                    (coroutines-key? base)
                    (rewrite/assoc-in nodes (into path [key :mvn/version]) coroutines-version)
                    (ksp-key? base)
                    (rewrite/assoc-in nodes (into path [key :mvn/version]) ksp-version)
                    :else nodes)))
              nodes
              (keys (get-in deps-map path))))
    nodes))

(defn update-deps-edn [file-name versions]
  (let [deps-edn-string (slurp file-name)
        nodes (rewrite/parse-string deps-edn-string)
        edn (edn/read-string deps-edn-string)
        nodes (update-deps-map nodes edn [:deps] versions)
        nodes (reduce (fn [nodes alias]
                        (-> nodes
                            (update-deps-map edn [:aliases alias :deps] versions)
                            (update-deps-map edn [:aliases alias :extra-deps] versions)))
                      nodes
                      (keys (:aliases edn)))]
    (spit file-name (str nodes))))
