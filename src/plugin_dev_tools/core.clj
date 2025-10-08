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
          sdk (io/file (ensure/sdks-dir) version)]

      ; Download SDK if not present (this also processes and generates deps.edn files)
      (when-not (fs/exists? sdk)
        (ensure/download-sdk version))

      ; Now update deps.edn file
      (for [module (:modules config)]
        (ensure/update-deps-edn module version)))
    (catch Exception e
      (println (str "Error: "
                    (.getMessage e)
                    (when-let [data (ex-data e)]
                      (str ", data: " (pr-str data))))))))
