(ns plugin-dev-tools.core
  (:require [babashka.curl :as curl]
            [babashka.fs :as fs]
            [borkdude.rewrite-edn :as rewrite]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import (java.io File)))

(defn sdks-dir []
  (io/file (System/getProperty "user.home") ".sdks"))

(defn ^File zipfile [version]
  (io/file (sdks-dir) (str "ideaIU-" version ".zip")))

(defn ^File sources-file [version]
  (io/file (sdks-dir) (str "ideaIC-" version "-sources.jar")))

(defn sdk-url [repo version]
  (str "https://www.jetbrains.com/intellij-repository/"
       repo
       "/com/jetbrains/intellij/idea/ideaIU/"
       version
       "/ideaIU-"
       version
       ".zip"))

(defn download-sdk [version]
  (let [url (sdk-url "releases" version)]
    (when-not (fs/exists? (sdks-dir))
      (fs/create-dir (sdks-dir)))
    (when-not (fs/exists? (zipfile version))
      (println "Downloading" url)
      (let [[resp repo]
            (let [resp (curl/get url {:as :stream :throw false})]
              (if (= 200 (:status resp))
                [resp "releases"]
                [(let [url (sdk-url "snapshots" version)]
                   (println "Not found (response" (:status resp) "), downloading" url)
                   (curl/get url {:as :stream :throw false}))
                 "snapshots"]))]
        (if (not= 200 (:status resp))
          (throw (ex-info "Problem downloading SDK" resp)))
        (io/copy (:body resp) (zipfile version))
        @(:exit resp)

        (when-not (fs/exists? (sources-file version))
          (let [url (str "https://www.jetbrains.com/intellij-repository/"
                         repo
                         "/com/jetbrains/intellij/idea/ideaIC/"
                         version
                         "/ideaIC-"
                         version
                         "-sources.jar")
                _ (println "Downloading" url)
                resp (curl/get url {:as :stream :throw false})]
            (if (not= 200 (:status resp))
              (throw (ex-info "Problem downloading sources" resp)))
            (io/copy (:body resp) (sources-file version))
            @(:exit resp)))))

    (println "Unzipping SDK")
    (let [sdk (.getAbsolutePath (io/file (sdks-dir) version))
          ret (sh "/usr/bin/unzip" (.getAbsolutePath (zipfile version)) "-d" sdk)]
      (if (not= 0 (:exit ret))
        (throw (ex-info "Problem unzipping" ret)))
      ; Make some things executable that need to be
      (sh "/bin/chmod" "+x" (str sdk "/bin/mac/aarch64/fsnotifier"))
      (sh "/bin/chmod" "+x" (str sdk "/bin/mac/aarch64/printenv")))))

(defn update-deps-edn [file-name version]
  (let [deps-edn-string (slurp file-name)
        nodes (rewrite/parse-string deps-edn-string)
        edn (edn/read-string deps-edn-string)
        nodes (reduce (fn [nodes alias]
                        (let [keys (filter #(#{"intellij" "plugin"} (namespace %))
                                           (keys (get-in edn [:aliases alias :extra-deps])))]
                          (reduce (fn [nodes key]
                                    (let [target [:aliases alias :extra-deps key :local/root]]
                                      (cond
                                        (.endsWith (name key) "$sources")
                                        (rewrite/assoc-in nodes target
                                                          (.getAbsolutePath (io/file (sdks-dir) (str "ideaIC-" version "-sources.jar"))))
                                        (= "intellij" (namespace key))
                                        (rewrite/assoc-in nodes target
                                                          (.getAbsolutePath (io/file (sdks-dir) version)))
                                        (= "plugin" (namespace key))
                                        (let [previous (get-in edn target)
                                              file (io/file previous)
                                              name (.getName file)]
                                          (rewrite/assoc-in nodes target
                                                            (.getAbsolutePath (io/file (sdks-dir) version "plugins" name))))
                                        :else nodes)))
                                  nodes
                                  keys)))
                      nodes
                      [:sdk :ide])]
    (spit file-name (str nodes))))

(defn ensure-sdk [args]
  (try
    (let [config (edn/read-string (slurp "plugin.edn"))
          version (:idea-version config)
          sdk (io/file (sdks-dir) version)
          aliases '{:aliases {:no-clojure {:classpath-overrides {org.clojure/clojure          ""
                                                                 org.clojure/spec.alpha       ""
                                                                 org.clojure/core.specs.alpha ""}}
                              :test       {:extra-paths []}}}]

      ; Download SDK if not present
      (when-not (fs/exists? sdk)
        (download-sdk version)

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
        (update-deps-edn module version)))
    (catch Exception e
      (println (str "Error: "
                    (.getMessage e)
                    (when-let [data (ex-data e)]
                      (str ", data: " (pr-str data))))))))
