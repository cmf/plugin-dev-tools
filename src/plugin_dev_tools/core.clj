(ns plugin-dev-tools.core
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [plugin-dev-tools.ensure :as ensure]
            [plugin-dev-tools.update-kotlin :as kotlin]))

(defn ensure-kotlin [args]
  (try
    (let [config (edn/read-string (slurp "plugin.edn"))
          versions (select-keys config [:kotlin-version :serialization-version :coroutines-version :ksp-version])]
      ; Now update deps.edn files
      (for [module (:modules config)]
        (kotlin/update-deps-edn module versions)))
    (catch Exception e
      (println (str "Error: "
                    (.getMessage e)
                    (when-let [data (ex-data e)]
                      (str ", data: " (pr-str data))))))))

(defn ensure-sdk [args]
  (try
    (let [config (edn/read-string (slurp "plugin.edn"))
          version (:idea-version config)
          sdk (io/file (ensure/sdks-dir) version)
          aliases '{:aliases {:no-clojure {:classpath-overrides {org.clojure/clojure          ""
                                                                 org.clojure/spec.alpha       ""
                                                                 org.clojure/core.specs.alpha ""}}
                              :test       {:extra-paths []}}}]

      ; Download SDK if not present
      (when-not (fs/exists? sdk)
        (ensure/download-sdk version)

        ; Generate deps.edn files for the SDK itself and for each plugin
        (let [jars (->> (fs/glob sdk "lib/**.jar")
                        ; Remove annotations jar due to weird version conflict
                        (remove #(= (fs/file-name %) "annotations.jar"))
                        (map #(fs/relativize sdk %))
                        (mapv str))]
          (spit (io/file sdk "deps.edn") (pr-str (merge aliases {:paths jars})))
          (let [plugins (fs/glob sdk "plugins/*")]
            (doseq [plugin plugins]
              (when (fs/directory? plugin)
                (let [jars (->> (fs/glob plugin "lib/**.jar")
                                ; Remove JPS plugins due to another weird version conflict in Kotlin
                                (remove #(str/includes? (fs/file-name %) "jps-plugin"))
                                (map #(fs/relativize plugin %))
                                (mapv str))]
                  (spit (str plugin "/deps.edn") (pr-str (merge aliases {:paths jars})))))))))

      ; Now update deps.edn file
      (for [module (:modules config)]
        (ensure/update-deps-edn module version)))
    (catch Exception e
      (println (str "Error: "
                    (.getMessage e)
                    (when-let [data (ex-data e)]
                      (str ", data: " (pr-str data))))))))
