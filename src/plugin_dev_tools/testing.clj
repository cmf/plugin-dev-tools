(ns plugin-dev-tools.testing
  "Common utilities for running JUnit tests in IntelliJ plugin projects."
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as api])
  (:import [java.io File]))

;; =============================================================================
;; Platform Detection
;; =============================================================================

(defn detect-os
  "Detect current operating system, returning the format used in product-info.json"
  []
  (let [os-name (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os-name "mac") "macOS"
      (str/includes? os-name "linux") "Linux"
      (str/includes? os-name "windows") "Windows"
      :else (do
              (println "Warning: Unknown OS" os-name)
              nil))))

(defn detect-architecture
  "Detect system architecture, returning the format used in product-info.json"
  []
  (let [arch (System/getProperty "os.arch")]
    (case arch
      "aarch64" "aarch64"
      "x86_64" "amd64"
      "amd64" "amd64"
      (do
        (println "Warning: Unknown architecture" arch ", defaulting to amd64")
        "amd64"))))

;; =============================================================================
;; IntelliJ SDK Discovery
;; =============================================================================

(defn find-intellij-sdk
  "Parse deps.edn to find IntelliJ SDK path"
  []
  (let [deps-content (edn/read-string (slurp "deps.edn"))]
    (get-in deps-content [:aliases :sdk :extra-deps 'intellij/sdk :local/root])))

(defn read-product-info
  "Read and parse product-info.json from the IntelliJ SDK"
  [intellij-sdk]
  (let [product-info-path (io/file intellij-sdk "product-info.json")]
    (when (fs/exists? product-info-path)
      (json/read-str (slurp product-info-path) :key-fn keyword))))

(defn find-launch-config
  "Find the launch configuration matching OS and architecture"
  [product-info os arch]
  (when product-info
    (first (filter #(and (= (:os %) os)
                         (= (:arch %) arch))
                   (:launch product-info)))))

;; =============================================================================
;; JVM Configuration
;; =============================================================================

(defn find-java-exec
  "Find JBR Java executable or fallback to JAVA_HOME or system java"
  [intellij-sdk]
  (let [jbr-candidates [(io/file intellij-sdk "jbr" "bin" "java")
                        (io/file intellij-sdk "jbr-17" "bin" "java")
                        (io/file intellij-sdk "Contents" "jbr" "Contents" "Home" "bin" "java")]
        jbr-java (first (filter fs/exists? jbr-candidates))]
    (or (when jbr-java (.getAbsolutePath ^File jbr-java))
        (when-let [java-home (System/getenv "JAVA_HOME")]
          (let [java-exec (io/file java-home "bin" "java")]
            (when (fs/exists? java-exec)
              (.getAbsolutePath ^File java-exec))))
        "java")))

(defn resolve-path-variables
  "Resolve path variables in JVM arguments ($APP_PACKAGE, $IDE_HOME, %IDE_HOME%)"
  [s intellij-sdk]
  (-> s
      (str/replace "$APP_PACKAGE" intellij-sdk)
      (str/replace "$IDE_HOME" intellij-sdk)
      (str/replace "%IDE_HOME%" intellij-sdk)))

(defn load-vm-options
  "Load VM options from file, filtering comments and blank lines"
  [vmoptions-file]
  (when (and vmoptions-file (fs/exists? vmoptions-file))
    (->> (str/split-lines (slurp vmoptions-file))
         (remove str/blank?)
         (remove #(re-find #"^\s*#" %)))))

(defn filter-incompatible-jvm-args
  "Filter out JVM arguments that are incompatible with test execution"
  [args]
  (remove #(str/includes? % "-Dsplash=") args))

;; =============================================================================
;; Sandbox Setup
;; =============================================================================

(defn setup-sandbox!
  "Set up sandbox directory structure for test execution"
  [sandbox-dir]
  (println "Setting up sandbox directory...")
  (when (fs/exists? sandbox-dir)
    (fs/delete-tree sandbox-dir))
  (doseq [subdir ["config" "system" "plugins" "system/log"]]
    (fs/create-dirs (fs/path sandbox-dir subdir)))
  (println "Sandbox directory ready")
  true)

;; =============================================================================
;; Classpath Building
;; =============================================================================

(defn get-test-classpath
  "Get the test classpath by invoking clojure -Spath.
   If project-root is provided, runs from that directory."
  [aliases & {:keys [project-root]}]
  (let [alias-str (str/join "" (map #(str ":" (name %)) aliases))
        result (api/process (cond-> {:command-args ["clojure" "-Spath" (str "-M" alias-str)]
                                     :out :capture}
                              project-root (assoc :dir project-root)))]
    (when (zero? (:exit result))
      (str/trim (:out result)))))

(defn find-junit-launcher
  "Find the JUnit Platform Console Launcher jar on the classpath"
  [classpath]
  (first (filter #(str/includes? % "junit-platform-console-standalone")
                 (str/split classpath #":"))))

;; =============================================================================
;; IntelliJ Test Framework Configuration
;; =============================================================================

(defn intellij-test-jvm-args
  "Build JVM arguments needed for IntelliJ test framework"
  [{:keys [intellij-sdk sandbox-dir plugin-id launch-config]}]
  (let [vm-opts-path (when-let [path (:vmOptionsFilePath launch-config)]
                       (str intellij-sdk "/" path))
        vm-opts (when vm-opts-path
                  (conj (vec (load-vm-options vm-opts-path)) "-ea"))
        launch-jvm-args (->> (:additionalJvmArguments launch-config)
                             (map #(resolve-path-variables % intellij-sdk))
                             (filter-incompatible-jvm-args))
        test-props ["--add-opens=java.base/java.nio.file.spi=ALL-UNNAMED"
                    "-Didea.classpath.index.enabled=false"
                    "-Didea.is.unit.test=true"
                    "-Djava.awt.headless=true"
                    "-Didea.is.internal=true"
                    "-Didea.plugin.in.sandbox.mode=true"
                    (str "-Didea.config.path=" sandbox-dir "/config")
                    (str "-Didea.plugins.path=" sandbox-dir "/plugins")
                    (str "-Didea.system.path=" sandbox-dir "/system")
                    (str "-Didea.log.path=" sandbox-dir "/system/log")
                    (str "-Didea.required.plugins.id=" plugin-id)
                    (str "-Didea.use.core.classloader.for=" plugin-id)
                    "-Didea.auto.reload.plugins=true"
                    "-Didea.use.core.classloader.for.plugin.path=true"
                    "-Didea.force.use.core.classloader=true"
                    "-Dintellij.testFramework.rethrow.logged.errors=true"]]
    (concat vm-opts launch-jvm-args test-props)))

(defn simple-test-jvm-args
  "Build JVM arguments for simple JUnit tests (no IntelliJ framework)"
  []
  ["-ea"])

;; =============================================================================
;; Test Execution
;; =============================================================================

(defn run-junit-tests
  "Run JUnit tests using the JUnit Platform Console Launcher.

   Options:
   - :java-exec      - Path to java executable
   - :jvm-args       - JVM arguments
   - :classpath      - Test classpath
   - :scan-classpath - Directory to scan for tests (for --scan-classpath)
   - :test-args      - Additional JUnit arguments (e.g., for test selection)

   Returns exit code. When test-args is provided, exit code 2 (no tests found)
   is converted to 0 since the selected test may not exist in this module."
  [{:keys [java-exec jvm-args classpath scan-classpath test-args]}]
  (let [selecting-tests? (seq test-args)
        junit-args (if selecting-tests?
                     ;; When selecting specific tests, use lenient discovery so missing classes
                     ;; don't abort. This allows running tests that only exist in some modules.
                     (into ["-classpath" classpath
                            "org.junit.platform.console.ConsoleLauncher"
                            "execute"
                            "--config=junit.platform.discovery.listener.default=logging"]
                           test-args)
                     ["-classpath" classpath
                      "org.junit.platform.console.ConsoleLauncher"
                      "execute"
                      "--scan-classpath" scan-classpath
                      "--fail-if-no-tests"])
        all-args (concat jvm-args junit-args)
        _ (println "Running tests...")
        result (api/process {:command-args (into [java-exec] all-args)})
        exit-code (:exit result)]
    ;; When selecting tests, exit code 2 (no tests) is OK - test may be in another module
    (if (and selecting-tests? (= exit-code 2))
      0
      exit-code)))
