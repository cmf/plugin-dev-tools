(ns plugin-dev-tools.core
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [plugin-dev-tools.build :as build]
            [plugin-dev-tools.ensure :as ensure]
            [plugin-dev-tools.update-kotlin :as kotlin]))

(defn ensure-kotlin [args]
  (try
    (let [config (edn/read-string (slurp "plugin.edn"))
          versions (select-keys config [:kotlin-version :serialization-version :coroutines-version :ksp-version])
          deps-files (->> (build/module-info args)
                          (map :deps-file)
                          distinct)]
      ; Now update deps.edn files
      (doseq [deps-file deps-files]
        (kotlin/update-deps-edn deps-file versions)))
    (catch Exception e
      (println (str "Error: "
                    (.getMessage e)
                    (when-let [data (ex-data e)]
                      (str ", data: " (pr-str data))))))))

(defn ensure-sdk [args]
  (try
    (let [config (edn/read-string (slurp "plugin.edn"))
          marketing-version (:idea-version config)
          ; Resolve version early to check if SDK exists
          resolved-version (if (re-matches #"^\d{4}\.\d+(-eap)?$" marketing-version)
                             (ensure/resolve-idea-version marketing-version)
                             marketing-version)             ; Already a full version
          sdk (io/file (ensure/sdks-dir) resolved-version)
          plugins (or (:plugins config) [])]

      ; Download SDK if not present (this also processes and generates deps.edn files)
      (when-not (fs/exists? sdk)
        (ensure/download-sdk marketing-version))

      ; Download plugins if specified
      (let [downloaded-plugins (doall
                                 (for [plugin-spec plugins]
                                   (let [plugin-id (:id plugin-spec)
                                         plugin-version (:version plugin-spec)
                                         plugin-path (ensure/plugin-dir plugin-id plugin-version)]
                                     (when-not (fs/exists? plugin-path)
                                       (ensure/download-plugin plugin-spec))
                                     plugin-spec)))]

        ; Now update deps.edn files with resolved version and plugin info
        (doseq [deps-file (->> (build/module-info args)
                               (map :deps-file)
                               distinct)]
          (ensure/update-deps-edn deps-file resolved-version downloaded-plugins))))
    (catch Exception e
      (println (str "Error: "
                    (.getMessage e)
                    (when-let [data (ex-data e)]
                      (str ", data: " (pr-str data))))))))
