(ns plugin-dev-tools.ensure
  (:require [babashka.curl :as curl]
            [babashka.fs :as fs]
            [borkdude.rewrite-edn :as rewrite]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.xml :as xml])
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

(defn maven-metadata-url [repo]
  (str "https://www.jetbrains.com/intellij-repository/"
       repo
       "/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml"))

(defn marketing-version->branch
  "Convert marketing version to branch number (e.g., '2025.3' -> '253')"
  [version]
  (let [[year minor] (str/split version #"\.")]
    (str "2" (mod (parse-long year) 10) minor)))

(defn parse-maven-metadata
  "Parse maven-metadata.xml and return map with :latest and :versions"
  [xml-input-stream]
  (try
    (let [metadata (xml/parse xml-input-stream)
          versioning (->> (:content metadata)
                          (filter #(= :versioning (:tag %)))
                          first)
          latest (->> (:content versioning)
                      (filter #(= :latest (:tag %)))
                      first
                      :content
                      first)
          versions-element (->> (:content versioning)
                                (filter #(= :versions (:tag %)))
                                first)
          versions (->> (:content versions-element)
                        (filter #(= :version (:tag %)))
                        (map #(first (:content %)))
                        vec)]
      {:latest latest
       :versions versions})
    (catch Exception e
      (println "Error parsing maven metadata:" (.getMessage e))
      {:latest nil
       :versions []})))

(defn version-compare
  "Compare two version strings semantically (e.g., '2024.3.10' > '2024.3.9')"
  [v1 v2]
  (let [parts1 (mapv parse-long (str/split v1 #"\."))
        parts2 (mapv parse-long (str/split v2 #"\."))]
    (compare parts1 parts2)))

(defn resolve-release-version
  "Find exact match or latest point release for the given version.
  If point releases exist (e.g., 2025.2.1, 2025.2.3), returns the latest.
  Otherwise returns the exact match or the version as-is.
  Example: '2025.2' -> '2025.2.3' (if 2025.2.3 is the latest point release)"
  [marketing-version versions]
  (let [prefix-matches (->> versions
                            (filter #(str/starts-with? % (str marketing-version ".")))
                            (sort version-compare)
                            reverse)
        exact-match (some #(when (= % marketing-version) %) versions)]
    (or (first prefix-matches)  ; Prefer latest point release if it exists
        exact-match              ; Otherwise use exact match
        marketing-version)))     ; Fall back to version as-is

(defn resolve-eap-version
  "Convert marketing version to branch and find latest snapshot.
  Example: '2025.3' -> '253.25908.13-EAP-SNAPSHOT'"
  [marketing-version versions]
  (let [branch (marketing-version->branch marketing-version)
        branch-pattern (re-pattern (str "^" branch "\\.\\d+.*-EAP-SNAPSHOT$"))
        eap-versions (->> versions
                          (filter #(re-matches branch-pattern %))
                          (filter #(not (str/includes? % "CANDIDATE")))
                          (sort)
                          reverse)]
    (or (first eap-versions)
        (str branch "-EAP-SNAPSHOT"))))

(defn resolve-idea-version
  "Resolve marketing version to full version by fetching maven-metadata.xml.
  For release versions (e.g., '2025.2'), finds the exact or latest point release.
  For EAP versions (e.g., '2025.3-eap'), converts to branch and finds latest snapshot."
  [marketing-version]
  (let [is-eap? (str/ends-with? marketing-version "-eap")
        repo (if is-eap? "snapshots" "releases")
        clean-version (if is-eap?
                        (str/replace marketing-version #"-eap$" "")
                        marketing-version)
        url (maven-metadata-url repo)]
    (println "Resolving version" marketing-version "from" repo "repository")
    (try
      (let [resp (curl/get url {:as :stream :throw false})]
        (if (= 200 (:status resp))
          (let [{:keys [versions]} (parse-maven-metadata (:body resp))
                resolved (if is-eap?
                           (resolve-eap-version clean-version versions)
                           (resolve-release-version clean-version versions))]
            (println "Resolved" marketing-version "to" resolved)
            resolved)
          (do
            (println "Warning: Could not fetch maven metadata (status" (:status resp) "), using version as-is")
            marketing-version)))
      (catch Exception e
        (println "Warning: Error resolving version:" (.getMessage e) ", using version as-is")
        marketing-version))))

;; Plugin-related functions

(defn plugin-maven-url
  "Construct Maven repository URL for a plugin.
  Channel is optional. If provided, it's prepended to the group.
  Examples:
    (plugin-maven-url \"kotlin\" \"1.9.0\" nil)
    => \"https://plugins.jetbrains.com/maven/com/jetbrains/plugins/kotlin/1.9.0/kotlin-1.9.0.zip\"
    (plugin-maven-url \"kotlin\" \"1.9.0\" \"eap\")
    => \"https://plugins.jetbrains.com/maven/eap/com/jetbrains/plugins/kotlin/1.9.0/kotlin-1.9.0.zip\""
  [plugin-id version channel]
  (let [channel-path (if channel (str channel "/") "")]
    (str "https://plugins.jetbrains.com/maven/"
         channel-path
         "com/jetbrains/plugins/"
         plugin-id
         "/"
         version
         "/"
         plugin-id
         "-"
         version
         ".zip")))

(defn plugin-dir
  "Return the directory path for a downloaded plugin.
  Plugins are stored in ~/.sdks/plugins/{plugin-id}/{version}/"
  [plugin-id version]
  (io/file (sdks-dir) "plugins" plugin-id version))

(defn plugin-zipfile
  "Return the path to the downloaded plugin zip file.
  Example: ~/.sdks/plugins/kotlin/1.9.0/kotlin-1.9.0.zip"
  [plugin-id version]
  (io/file (plugin-dir plugin-id version) (str plugin-id "-" version ".zip")))

(defn process-plugin
  "Extracts plugin from zipfile and generates deps.edn file.
  Similar to process-sdk but for marketplace plugins."
  [plugin-id version]
  (println "Unzipping plugin" plugin-id)
  (let [plugin-path (.getAbsolutePath (plugin-dir plugin-id version))
        zip-path (.getAbsolutePath (plugin-zipfile plugin-id version))
        ret (sh "/usr/bin/unzip" "-q" zip-path "-d" plugin-path)]
    (if (not= 0 (:exit ret))
      (throw (ex-info "Problem unzipping plugin" ret)))

    ; Find the actual plugin directory (it might be nested inside the zip)
    (let [plugin-file (plugin-dir plugin-id version)
          ; First check if there's a lib/ directory directly
          lib-dir (io/file plugin-file "lib")
          ; If not, look for the first subdirectory that contains lib/
          actual-plugin-dir (if (fs/exists? lib-dir)
                              plugin-file
                              (first (filter #(fs/exists? (fs/file % "lib"))
                                           (filter fs/directory? (fs/list-dir plugin-file)))))
          aliases '{:aliases {:no-clojure {:classpath-overrides {org.clojure/clojure          ""
                                                                 org.clojure/spec.alpha       ""
                                                                 org.clojure/core.specs.alpha ""}}
                              :test       {:extra-paths []}}}]
      (when actual-plugin-dir
        (let [jars (->> (fs/glob actual-plugin-dir "lib/**.jar")
                        (remove #(str/includes? (fs/file-name %) "jps-plugin"))
                        (map #(fs/relativize actual-plugin-dir %))
                        (mapv str))]
          (spit (fs/file actual-plugin-dir "deps.edn") (pr-str (merge aliases {:paths jars}))))))))

(defn download-plugin
  "Downloads a plugin from the JetBrains marketplace.
  Takes a plugin spec map with :id, :version (optional), and :channel (optional).
  Returns the plugin-id and version as a map."
  [{:keys [id version channel] :as plugin-spec}]
  (let [plugin-id id
        ; If no version specified, we'll need to fetch it somehow
        ; For now, require version to be specified
        _ (when-not version
            (throw (ex-info "Plugin version must be specified" {:plugin-id plugin-id})))
        url (plugin-maven-url plugin-id version channel)
        plugin-path (plugin-dir plugin-id version)
        zip-path (plugin-zipfile plugin-id version)]

    (when-not (fs/exists? plugin-path)
      (fs/create-dirs plugin-path))

    (when-not (fs/exists? zip-path)
      (println "Downloading plugin" plugin-id version "from" url)
      (let [resp (curl/get url {:as :stream :throw false})]
        (if (not= 200 (:status resp))
          (throw (ex-info "Problem downloading plugin"
                         {:plugin-id plugin-id
                          :version version
                          :status (:status resp)
                          :url url})))
        (io/copy (:body resp) zip-path)
        @(:exit resp)))

    (process-plugin plugin-id version)
    {:id plugin-id :version version}))

(defn process-sdk
  "Extracts SDK from zipfile and generates deps.edn files for SDK and plugins.
  This function is separated for testing purposes."
  [version]
  (println "Unzipping SDK")
  (let [sdk (.getAbsolutePath (io/file (sdks-dir) version))
        ret (sh "/usr/bin/unzip" (.getAbsolutePath (zipfile version)) "-d" sdk)]
    (if (not= 0 (:exit ret))
      (throw (ex-info "Problem unzipping" ret)))
    ; Make some things executable that need to be
    (sh "/bin/chmod" "+x" (str sdk "/bin/mac/aarch64/fsnotifier"))
    (sh "/bin/chmod" "+x" (str sdk "/bin/mac/aarch64/printenv"))

    ; Generate deps.edn files for the SDK itself and for each plugin
    (let [sdk-file (io/file (sdks-dir) version)
          aliases '{:aliases {:no-clojure {:classpath-overrides {org.clojure/clojure          ""
                                                                 org.clojure/spec.alpha       ""
                                                                 org.clojure/core.specs.alpha ""}}
                              :test       {:extra-paths []}}}
          jars (->> (fs/glob sdk-file "lib/**.jar")
                    ; Remove annotations jar due to weird version conflict
                    (remove #(= (fs/file-name %) "annotations.jar"))
                    (map #(fs/relativize sdk-file %))
                    (mapv str))]
      (spit (io/file sdk-file "deps.edn") (pr-str (merge aliases {:paths jars})))
      (let [plugins (fs/glob sdk-file "plugins/*")]
        (doseq [plugin plugins]
          (when (fs/directory? plugin)
            (let [jars (->> (fs/glob plugin "lib/**.jar")
                            ; Remove JPS plugins due to another weird version conflict in Kotlin
                            (remove #(str/includes? (fs/file-name %) "jps-plugin"))
                            (map #(fs/relativize plugin %))
                            (mapv str))]
              (spit (str plugin "/deps.edn") (pr-str (merge aliases {:paths jars}))))))))))

(defn download-sdk
  "Downloads SDK for the given marketing version. Returns the resolved full version."
  [marketing-version]
  (let [version (resolve-idea-version marketing-version)
        repo (if (str/includes? version "SNAPSHOT") "snapshots" "releases")
        url (sdk-url repo version)]
    (when-not (fs/exists? (sdks-dir))
      (fs/create-dir (sdks-dir)))
    (when-not (fs/exists? (zipfile version))
      (println "Downloading" url)
      (let [resp (curl/get url {:as :stream :throw false})]
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

    (process-sdk version)
    version))

(defn update-deps-edn
  "Update deps.edn file with SDK and plugin paths.
  version: The IntelliJ SDK version
  plugins: Collection of plugin specs with :id and :version (optional, defaults to empty vector)"
  ([file-name version]
   (update-deps-edn file-name version []))
  ([file-name version plugins]
   (let [deps-edn-string (slurp file-name)
         nodes (rewrite/parse-string deps-edn-string)
         edn (edn/read-string deps-edn-string)
         ; Create a map of plugin-id -> version for lookup
         plugin-map (into {} (map (fn [{:keys [id version]}] [id version]) plugins))
         nodes (reduce (fn [nodes alias]
                         (let [keys (filter #(#{"intellij" "plugin" "marketplace-plugin"} (namespace %))
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
                                         (= "marketplace-plugin" (namespace key))
                                         (let [plugin-id (name key)
                                               plugin-version (get plugin-map plugin-id)]
                                           (if plugin-version
                                             (rewrite/assoc-in nodes target
                                                               (.getAbsolutePath (plugin-dir plugin-id plugin-version)))
                                             nodes))
                                         :else nodes)))
                                   nodes
                                   keys)))
                       nodes
                       [:sdk :ide])]
     (spit file-name (str nodes)))))

(comment
  (update-deps-edn "/Users/colin/dev/scribe/integrations/cursive/deps.edn" "253.20558.43-EAP-SNAPSHOT"))
