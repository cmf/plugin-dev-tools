(ns plugin-dev-tools.ensure
  (:require [babashka.curl :as curl]
            [babashka.fs :as fs]
            [borkdude.rewrite-edn :as rewrite]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.xml :as xml])
  (:import (java.io File)
           (java.util.jar JarFile)))

(declare maybe-write-test-framework-deps!)
(declare deps-relative-path)

(defn sdks-dir []
  (io/file (System/getProperty "user.home") ".sdks"))

(defn project-sdks-link
  "Project-local path (relative to user.dir) where deps.edn will point to SDKs.

  This is expected to be a symlink to ~/.sdks (created by ensure-sdk)."
  []
  (io/file "sdks"))

(defn ensure-project-sdks-symlink!
  "Ensure there is a project-local symlink at ./sdks pointing to ~/.sdks.

  This lets deps.edn use relative paths like sdks/<version> which stay stable
  across macOS/Linux home directory differences." 
  ([]
   (ensure-project-sdks-symlink! (project-sdks-link) (sdks-dir)))
  ([link target]
   (let [link (fs/file link)
         target (fs/file target)]
     (when-not (fs/exists? link)
       (println "Creating sdks symlink" (str link) "->" (str target))
       (fs/create-sym-link link target)))))

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
              (spit (str plugin "/deps.edn") (pr-str (merge aliases {:paths jars}))))))
        (maybe-write-test-framework-deps! sdk-file version)))))

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

;; =============================================================================
;; Test framework exclusions
;; =============================================================================

(def ^:private kotlin-stdlib-exclusions
  #{'org.jetbrains.kotlin/kotlin-stdlib
    'org.jetbrains.kotlin/kotlin-stdlib-jdk8})

(def ^:private coroutines-exclusions
  (let [groups ["org.jetbrains.kotlinx"
                "com.intellij.platform"
                "org.jetbrains.intellij.deps.kotlinx"]
        artifacts ["kotlinx-coroutines-core-jvm"
                   "kotlinx-coroutines-jdk8"
                   "kotlinx-coroutines-core"
                   "kotlinx-coroutines-debug"
                   "kotlinx-coroutines-guava"
                   "kotlinx-coroutines-slf4j"
                   "kotlinx-coroutines-test"]]
    (set (for [group groups
               artifact artifacts]
           (symbol (str group "/" artifact))))))

(def ^:private explicit-exclusions
  (set (concat
        ['junit/junit
         'org.hamcrest/hamcrest-core
         'org.jetbrains/jetCheck
         'org.jetbrains.teamcity/serviceMessages]
        kotlin-stdlib-exclusions
        coroutines-exclusions)))

(def ^:private fallback-exclusions
  #{'com.jetbrains.intellij.java/java-resources-en
    'com.jetbrains.intellij.java/java-rt
    'com.jetbrains.intellij.platform/boot
    'com.jetbrains.intellij.platform/code-style-impl
    'com.jetbrains.intellij.platform/core-ui
    'com.jetbrains.intellij.platform/execution-impl
    'com.jetbrains.intellij.platform/ide-impl
    'com.jetbrains.intellij.platform/ide-util-io-impl
    'com.jetbrains.intellij.platform/ide-util-io
    'com.jetbrains.intellij.platform/ide-util-netty
    'com.jetbrains.intellij.platform/images
    'com.jetbrains.intellij.platform/lang-impl
    'com.jetbrains.intellij.platform/lang
    'com.jetbrains.intellij.platform/resources
    'com.jetbrains.intellij.platform/service-container
    'com.jetbrains.intellij.platform/util-class-loader
    'com.jetbrains.intellij.platform/util-jdom
    'com.jetbrains.intellij.platform/workspace-model-jps
    'com.jetbrains.intellij.platform/workspace-model-storage
    'com.jetbrains.intellij.regexp/regexp
    'com.jetbrains.intellij.xml/xml-dom-impl
    'com.jetbrains.intellij.java/java-compiler-impl
    'com.jetbrains.intellij.java/java-debugger-impl
    'com.jetbrains.intellij.java/java-execution-impl
    'com.jetbrains.intellij.java/java-execution
    'com.jetbrains.intellij.java/java-impl-refactorings
    'com.jetbrains.intellij.java/java-impl
    'com.jetbrains.intellij.java/java-plugin
    'com.jetbrains.intellij.java/java-ui
    'com.jetbrains.intellij.java/java
    'com.jetbrains.intellij.platform/external-system-impl
    'com.jetbrains.intellij.platform/jps-build
    'com.jetbrains.intellij.platform/util})

(defn- detect-os
  "Detect current operating system, returning the format used in product-info.json."
  []
  (let [os-name (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os-name "mac") "macOS"
      (str/includes? os-name "linux") "Linux"
      (str/includes? os-name "windows") "Windows"
      :else (do
              (println "Warning: Unknown OS" os-name)
              nil))))

(defn- normalize-resource-path [path]
  (when path
    (loop [path path]
      (let [path (str/replace path "\\" "/")]
        (if (str/starts-with? path "../")
          (recur (subs path 3))
          path)))))

(defn- read-product-info [sdk-dir]
  (let [product-info-path (io/file sdk-dir "product-info.json")]
    (when (fs/exists? product-info-path)
      (json/read-str (slurp product-info-path) :key-fn keyword))))

(defn- product-info-classpath
  [sdk-dir]
  (when-let [product-info (read-product-info sdk-dir)]
    (let [os (detect-os)
          launch (first (filter #(= os (:os %)) (:launch product-info)))
          boot (->> (:bootClassPathJarNames launch)
                    (map #(str "lib/" %)))
          layout (->> (:layout product-info)
                      (filter #(= "com.intellij" (:name %)))
                      (mapcat :classPath))]
      (->> (concat boot layout)
           (remove #{"lib/junit4.jar" "lib/junit.jar" "lib/testFramework.jar"})
           (map normalize-resource-path)
           set))))

(defn- bundled-plugin-jars
  [sdk-dir]
  (let [plugins-dir (io/file sdk-dir "plugins")]
    (if (fs/exists? plugins-dir)
      (->> (fs/list-dir plugins-dir)
           (filter fs/directory?)
           (mapcat (fn [plugin-dir]
                     (let [lib-dir (fs/file plugin-dir "lib")
                           modules-dir (fs/file plugin-dir "lib" "modules")]
                       (concat (when (fs/exists? lib-dir)
                                 (fs/glob lib-dir "*.jar"))
                               (when (fs/exists? modules-dir)
                                 (fs/glob modules-dir "*.jar"))))))
           (map #(fs/relativize sdk-dir %))
           (map str)
           (map normalize-resource-path)
           set)
      #{})))

(defn- collected-jar-paths [sdk-dir]
  (set/union (or (product-info-classpath sdk-dir) #{})
             (bundled-plugin-jars sdk-dir)))

(defn- module-name->coordinates
  [module-name]
  (let [segments (str/split module-name #"[\.\s]+")]
    (when (>= (count segments) 2)
      (let [group-id (str "com.jetbrains." (str/join "." (take 2 segments)))
            remaining (rest segments)
            remaining (if (#{"platform" "vcs" "cloud"} (first remaining))
                        (rest remaining)
                        remaining)]
        (when (seq remaining)
          (let [artifact-id (->> remaining
                                 (map #(str/replace % #"([a-z])([A-Z])" "$1-$2"))
                                 (map str/lower-case)
                                 (str/join "-"))]
            (symbol (str group-id "/" artifact-id))))))))

(defn- module-resource-path
  [module-descriptor]
  (->> (:content module-descriptor)
       (filter #(= :resources (:tag %)))
       first
       :content
       (filter #(= :resource-root (:tag %)))
       first
       :attrs
       :path
       normalize-resource-path))

(defn- module-descriptor-exclusions
  [sdk-dir]
  (let [module-descriptors (io/file sdk-dir "modules" "module-descriptors.jar")
        collected (collected-jar-paths sdk-dir)]
    (when (fs/exists? module-descriptors)
      (with-open [jar-file (JarFile. module-descriptors)]
        (reduce (fn [acc entry]
                  (if (str/ends-with? (.getName entry) ".xml")
                    (let [descriptor (with-open [stream (.getInputStream jar-file entry)]
                                       (xml/parse stream))
                          module-name (get-in descriptor [:attrs :name])
                          resource-path (module-resource-path descriptor)
                          coordinate (when (and module-name
                                                resource-path
                                                (contains? collected resource-path))
                                       (module-name->coordinates module-name))]
                      (if coordinate
                        (conj acc coordinate)
                        acc))
                    acc))
                #{}
                (enumeration-seq (.entries jar-file)))))))

(defn test-framework-exclusions
  "Return exclusions for the IntelliJ test framework dependency based on SDK contents."
  [sdk-dir]
  (let [sdk-dir (fs/file sdk-dir)
        module-exclusions (module-descriptor-exclusions sdk-dir)
        exclusions (if (seq module-exclusions)
                     (set/union module-exclusions explicit-exclusions)
                     (set/union fallback-exclusions explicit-exclusions))]
    (->> exclusions
         (sort-by str)
         vec)))

(def ^:private test-framework-min-version "2026.1")

(def ^:private test-framework-aliases
  '{:no-clojure {:classpath-overrides {org.clojure/clojure          ""
                                       org.clojure/spec.alpha       ""
                                       org.clojure/core.specs.alpha ""}}
    :test       {:extra-paths []}})

(def ^:private test-framework-coordinates
  ['com.jetbrains.intellij.platform/test-framework
   'com.jetbrains.intellij.platform/test-framework-junit5
   'com.jetbrains.intellij.css/css-test-framework
   'com.jetbrains.intellij.platform/debugger-test-framework
   'com.jetbrains.intellij.platform/external-system-test-framework
   'com.jetbrains.intellij.go/go-test-framework
   'com.jetbrains.intellij.idea/ruby-test-framework
   'com.jetbrains.intellij.java/java-test-framework
   'com.jetbrains.intellij.javascript/javascript-test-framework
   'com.jetbrains.intellij.platform/lsp-test-framework
   'com.jetbrains.intellij.maven/maven-test-framework
   'com.jetbrains.intellij.platform/poly-symbols-test-framework
   'com.jetbrains.intellij.qodana/qodana-test-framework
   'com.jetbrains.intellij.resharper/resharper-test-framework
   'com.jetbrains.intellij.platform/uast-test-framework
   'com.jetbrains.intellij.platform/vcs-test-framework
   'com.jetbrains.intellij.xml/xml-test-framework
   'com.jetbrains.intellij.platform/web-symbols-test-framework])

(defn- version-parts [version]
  (->> (str/split (or version "") #"\.")
       (map #(or (some->> (re-find #"\d+" %) parse-long) 0))
       vec))

(defn- version>=? [version required]
  (let [left (version-parts version)
        right (version-parts required)
        size (max (count left) (count right))
        left (vec (take size (concat left (repeat 0))))
        right (vec (take size (concat right (repeat 0))))]
    (not (neg? (compare left right)))))

(defn- supports-test-framework-deps? [sdk-dir]
  (when-let [product-info (read-product-info sdk-dir)]
    (version>=? (:version product-info) test-framework-min-version)))

(defn- write-test-framework-deps! [sdk-dir version coord exclusions]
  (let [target (fs/file sdk-dir (name coord))
        deps {:paths []
              :deps {coord {:mvn/version version
                            :exclusions exclusions}}
              :aliases test-framework-aliases}]
    (fs/create-dirs target)
    (spit (fs/file target "deps.edn") (pr-str deps))))

(defn maybe-write-test-framework-deps!
  "Create test-framework deps.edn files inside the SDK when supported.
  Returns true when the files are written."
  [sdk-dir version]
  (let [sdk-dir (fs/file sdk-dir)]
    (when (supports-test-framework-deps? sdk-dir)
      (let [exclusions (test-framework-exclusions sdk-dir)]
        (doseq [coord test-framework-coordinates]
          (write-test-framework-deps! sdk-dir version coord exclusions)))
      true)))

(defn- update-test-framework-exclusions
  [nodes edn exclusions]
  (let [aliases (keys (:aliases edn))]
    (reduce (fn [nodes alias]
              (reduce (fn [nodes dep-key]
                        (if (contains? (get-in edn [:aliases alias :extra-deps]) dep-key)
                          (rewrite/assoc-in nodes [:aliases alias :extra-deps dep-key :exclusions] (vec exclusions))
                          nodes))
                      nodes
                      test-framework-coordinates))
            nodes
            aliases)))

(defn- update-test-framework-local-root
  [nodes edn deps-file version]
  (let [aliases (keys (:aliases edn))]
    (reduce (fn [nodes alias]
              (let [extra-deps (get-in edn [:aliases alias :extra-deps])]
                (reduce (fn [nodes dep-key]
                          (let [dep-name (name dep-key)]
                            (if (and (= "intellij" (namespace dep-key))
                                     (str/includes? dep-name "test-framework"))
                              (rewrite/assoc-in nodes [:aliases alias :extra-deps dep-key]
                                                {:local/root (deps-relative-path deps-file
                                                                                (fs/file (project-sdks-link) version dep-name))})
                              nodes)))
                        nodes
                        (keys extra-deps))))
            nodes
            aliases)))

(defn- deps-relative-path
  "Return a relative path string from the deps.edn file's directory to target.

  target is expected to be a path relative to the project root (user.dir),
  e.g. (io/file sdks version)."
  [deps-file target]
  (let [deps-path (fs/file deps-file)
        deps-dir (or (fs/parent deps-path) (fs/file "."))
        rel (fs/relativize (fs/absolutize deps-dir)
                           (fs/absolutize target))]
    (str rel)))

(defn update-deps-edn
  "Update deps.edn file with SDK and plugin paths.

  The rewritten :local/root paths are *relative* to each deps.edn file so they
  remain stable across different machines/OSes.

  version: The IntelliJ SDK version
  plugins: Collection of plugin specs with :id and :version (optional, defaults to empty vector)
  test-framework-exclusions: Collection of exclusions for com.jetbrains.intellij.platform/test-framework"
  ([file-name version]
   (update-deps-edn file-name version [] nil))
  ([file-name version plugins]
   (update-deps-edn file-name version plugins nil))
  ([file-name version plugins test-framework-exclusions]
   (let [deps-edn-string (slurp file-name)
         nodes (rewrite/parse-string deps-edn-string)
         edn (edn/read-string deps-edn-string)
         ;; Create a map of plugin-id -> version for lookup
         plugin-map (into {} (map (fn [{:keys [id version]}] [id version]) plugins))
         nodes (reduce (fn [nodes alias]
                         (let [keys (filter #(#{"intellij" "plugin" "marketplace-plugin"} (namespace %))
                                            (keys (get-in edn [:aliases alias :extra-deps])))]
                           (reduce (fn [nodes key]
                                     (let [target [:aliases alias :extra-deps key :local/root]]
                                       (cond
                                         (.endsWith (name key) "$sources")
                                         (rewrite/assoc-in nodes target
                                                           (deps-relative-path file-name
                                                                              (fs/file (project-sdks-link)
                                                                                       (str "ideaIC-" version "-sources.jar"))))

                                         (= "intellij" (namespace key))
                                         (let [sdk-root (fs/file (project-sdks-link) version)
                                               dep-name (name key)
                                               sdk-path (if (str/includes? dep-name "test-framework")
                                                          (fs/file sdk-root dep-name)
                                                          sdk-root)]
                                           (rewrite/assoc-in nodes target
                                                             (deps-relative-path file-name sdk-path)))

                                         (= "plugin" (namespace key))
                                         (rewrite/assoc-in nodes target
                                                           (deps-relative-path file-name
                                                                              (fs/file (project-sdks-link) version "plugins" (name key))))

                                         (= "marketplace-plugin" (namespace key))
                                         (let [plugin-id (name key)
                                               plugin-version (get plugin-map plugin-id)]
                                           (if plugin-version
                                             (rewrite/assoc-in nodes target
                                                               (deps-relative-path file-name
                                                                                  (fs/file (project-sdks-link)
                                                                                           "plugins" plugin-id plugin-version)))
                                             nodes))
                                         :else nodes)))
                                   nodes
                                   keys)))
                       nodes
                       [:sdk :ide])
         nodes (cond
                 (= test-framework-exclusions :clear)
                 (-> nodes
                     (update-test-framework-local-root edn file-name version)
                     (update-test-framework-exclusions edn []))

                 (seq test-framework-exclusions)
                 (update-test-framework-exclusions nodes edn test-framework-exclusions)

                 :else nodes)]
     (spit file-name (str nodes)))))

(comment
  (update-deps-edn "/Users/colin/dev/scribe/integrations/cursive/deps.edn" "253.20558.43-EAP-SNAPSHOT"))
