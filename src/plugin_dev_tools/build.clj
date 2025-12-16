(ns plugin-dev-tools.build
  (:refer-clojure :exclude [compile])
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.build.api :as api]
            [clojure.tools.build.api :as b]
            [clojure.tools.build.tasks.process :as process]
            [clojure.tools.build.util.file :as file]
            [clojure.tools.build.util.zip :as zip])
  (:import (java.io File FileOutputStream)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)
           (java.util.zip ZipOutputStream)
           (javax.tools DiagnosticListener ToolProvider)))

(def jvm-target "21")

(def javac-opts ["--release" jvm-target "-Xlint:deprecation" "-proc:none"])

(def kotlinc-opts ["-jvm-target" jvm-target "-no-stdlib" "-Xjvm-default=all"])


;; Config functions

(defn plugin-version
  "Returns the concatenated version string."
  [plugin-config]
  (let [{:keys [base-version platform-version]} plugin-config]
    (str base-version \- platform-version)))


(defn plugin-directory
  "Returns the name of the plugin directory in the zip file"
  [modules]
  (some #(when (:main-plugin? %)
           (:plugin-directory %))
        modules))

(defn plugin-module
  "Returns the module name of the main module"
  [modules]
  (some #(when (:main-plugin? %)
           (:module %))
        modules))

(defn without
  "Returns set s with x removed."
  [s x] (set/difference s #{x}))

(defn take-1
  "Returns the pair [element, s'] where s' is set s with element removed."
  [s] {:pre [(not (empty? s))]}
  (let [item (first s)]
    [item (without s item)]))

(defn no-incoming
  "Returns the set of nodes in graph g for which there are no incoming
  edges, where g is a map of nodes to sets of nodes."
  [g]
  (let [nodes (set (keys g))
        have-incoming (apply set/union (vals g))]
    (set/difference nodes have-incoming)))

(defn normalize
  "Returns g with empty outgoing edges added for nodes with incoming
  edges only.  Example: {:a #{:b}} => {:a #{:b}, :b #{}}"
  [g]
  (let [have-incoming (apply set/union (vals g))]
    (reduce #(if (get % %2) % (assoc % %2 #{})) g have-incoming)))

(defn kahn-sort
  "Proposes a topological sort for directed graph g using Kahn's
   algorithm, where g is a map of nodes to sets of nodes. If g is
   cyclic, returns nil."
  ([g]
   (kahn-sort (normalize g) [] (no-incoming g)))
  ([g l s]
   (if (empty? s)
     (when (every? empty? (vals g)) l)
     (let [[n s'] (take-1 s)
           m (g n)
           g' (reduce #(update-in % [n] without %2) g m)]
       (recur g' (conj l n) (set/union s' (set/intersection (no-incoming g') m)))))))

(defn path-to [{:keys [module-path]} file]
  (if (= module-path ".")
    file
    (str module-path "/" file)))

(defn module-info
  "Returns elaborated module info from plugin.edn in dependency order."
  [args]
  (let [config (edn/read-string (slurp "plugin.edn"))
        modules-config (:modules config)
        modules-config (if (map? modules-config)
                         modules-config
                         (into (array-map)
                               (map (fn [module-path]
                                      (let [module-id (if (= module-path ".")
                                                        "plugin"
                                                        (if-let [index (str/last-index-of module-path "/")]
                                                          (subs module-path (inc index))
                                                          module-path))]
                                        [module-id {:module-path module-path
                                                    :description module-id
                                                    :depends []
                                                    :main-plugin? (= module-path ".")}]))
                                    modules-config)))
        modules (reduce-kv (fn [ret id details]
                             (let [module-path (or (:module-path details) id)
                                   module (if (= module-path ".")
                                            id
                                            (if-let [index (str/last-index-of module-path "/")]
                                              (subs module-path (inc index))
                                              module-path))
                                   deps-file (if (= module-path ".")
                                               "deps.edn"
                                               (str module-path "/deps.edn"))
                                   jar-file (if (= module-path ".")
                                              (str "build/distributions/" module ".jar")
                                              (str module-path "/build/distributions/" module ".jar"))
                                   plugin-directory (or (:plugin-directory details) module)]
                               (assoc ret module (assoc details :module module
                                                                :module-path module-path
                                                                :deps-file deps-file
                                                                :jar-file jar-file
                                                                :plugin-directory plugin-directory))))
                           (sorted-map)
                           (:modules config))
        deps-map (reduce (fn [ret {:keys [module depends]}]
                           (assoc ret module (set depends)))
                         {}
                         (vals modules))
        order (kahn-sort deps-map)]
    (if (nil? order)
      (throw (ex-info "Dependency cycle" {:deps deps-map}))
      (let [ret (mapv modules (reverse order))]
        (if-let [build-ns (find-ns 'build)]
          (if-let [customise (ns-resolve build-ns 'customise-modules)]
            (customise ret args)
            ret)
          ret)))))


(defn clean [args]
  (let [modules (module-info args)
        dir (plugin-directory modules)
        dirs (into ["out/production"
                    "out/test"
                    "out/generated"
                    "build-tools/build"
                    (str "sandbox/plugins/" dir "/lib")]
                   (map #(path-to % "build") modules))]
    (doseq [path dirs]
      (b/delete {:path path}))))

(defn classpath-files
  "Return the files (non-directories) from a tools.build basis."
  [basis]
  (filter #(not (.isDirectory (io/file %)))
          (:classpath-roots basis)))

;; Compilation helpers

(defn javac
  "Compile Java sources with support for extra classpath dirs (matches cursive/scribe behaviour).
  Options: :basis, :javac-opts, :class-dir, :src-dirs, :extra-dirs."
  [{:keys [basis javac-opts class-dir src-dirs extra-dirs]}]
  (let [{:keys [libs]} basis]
    (when (seq src-dirs)
      (let [class-dir (file/ensure-dir (b/resolve-path class-dir))
            compiler (ToolProvider/getSystemJavaCompiler)
            listener (reify DiagnosticListener (report [_ diag] (println (str diag))))
            file-mgr (.getStandardFileManager compiler listener nil nil)
            class-dir-path (.getPath class-dir)
            classpath (str/join File/pathSeparator (-> []
                                                       (into (mapcat :paths) (vals libs))
                                                       (conj class-dir-path)
                                                       (into (map (fn [dir]
                                                                    (-> (b/resolve-path dir)
                                                                        (file/ensure-dir)
                                                                        (.getPath))))
                                                             extra-dirs)))
            options (concat ["-classpath" classpath "-d" class-dir-path] javac-opts)
            java-files (mapcat #(file/collect-files (b/resolve-path %) :collect (file/suffixes ".java")) src-dirs)
            file-objs (.getJavaFileObjectsFromFiles file-mgr java-files)
            task (.getTask compiler nil file-mgr listener options nil file-objs)
            success (.call task)]
        (when-not success
          (throw (ex-info "Java compilation failed" {})))))))

(defn- kotlin-compiler []
  (try
    (let [cls (Class/forName "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
          ctor (.getDeclaredConstructor cls (into-array Class []))]
      (.newInstance ctor (object-array 0)))
    (catch ClassNotFoundException e
      (throw (ex-info "Kotlin compiler not on classpath" {} e)))))

(defn kotlinc
  "Compile Kotlin sources using the in-process K2 compiler.
  Options: :basis, :kotlinc-opts, :class-dir, :src-dirs, :extra-paths, :headless? (default true)."
  [{:keys [basis kotlinc-opts class-dir src-dirs extra-paths headless?]
    :or   {extra-paths [] headless? true}}]
  (let [{:keys [libs]} basis]
    (when (seq src-dirs)
      (let [class-dir (file/ensure-dir (b/resolve-path class-dir))
            class-dir-path (.getPath class-dir)
            classpath (str/join File/pathSeparator (-> []
                                                       (into (mapcat :paths) (vals libs))
                                                       (conj class-dir-path)
                                                       (into extra-paths)))
            options (concat src-dirs ["-classpath" classpath "-d" class-dir-path] kotlinc-opts)
            compiler (kotlin-compiler)]
        (when headless?
          (System/setProperty "java.awt.headless" "true"))
        (let [exit-code (.exec compiler System/out (into-array String options))
              ordinal (when exit-code (try (.ordinal exit-code) (catch Exception _ nil)))]
          (when (and ordinal (not (zero? ordinal)))
            (throw (ex-info "Kotlin compilation failed" {:exit-code ordinal})))
          (when (and (nil? ordinal) exit-code (not= "OK" (str exit-code)))
            (throw (ex-info "Kotlin compilation failed" {:exit-code (str exit-code)}))))))))

;; Metadata helpers

(defn git-revision
  "Return git describe output for HEAD in dir (default \".\")."
  ([] (git-revision "."))
  ([dir]
   (-> (b/process {:command-args ["git" "describe" "--tags" "--always" "HEAD"]
                   :dir          dir
                   :out          :capture})
       :out
       str/trim)))

(defn update-plugin-xml
  "Update plugin.xml with version, description, build metadata and optional resource copy.
  Options:
    :target            Path to compiled output root (required)
    :plugin-version    Version string (required)
    :base-dir          Root dir for description/resources (default \".\")
    :description-path  Path to description.html (default \"description.html\")
    :plugin-xml-path   Override plugin.xml path (defaults to <target>/META-INF/plugin.xml)
    :copy-resources?   Copy resource dirs into target before writing plugin.xml
    :resource-dirs     Seq of resource dirs (relative or absolute) to copy when copy-resources? is true."
  [{:keys [target plugin-version base-dir description-path plugin-xml-path copy-resources? resource-dirs]
    :or   {base-dir "." description-path "description.html"}}]
  (when copy-resources?
    (doseq [dir resource-dirs]
      (b/copy-dir {:src-dirs   [(str (io/file base-dir dir))]
                   :target-dir target})))
  (let [rev (git-revision base-dir)
        description (slurp (str (io/file base-dir description-path)))
        now (-> (LocalDateTime/now)
                (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))
        plugin-xml (or plugin-xml-path (str target "/META-INF/plugin.xml"))
        xml (-> plugin-xml
                slurp
                (str/replace #"(<version>).*(</version>)"
                             (str "$1" plugin-version "$2"))
                (str/replace #"(?s)(<description>[\r\n\s]*).*([\r\n\s]*</description>)"
                             (str "$1<![CDATA[\n"
                                  description
                                  "<p>Built on: " now "</p>\n"
                                  "<p>Built from: " rev "</p>\n"
                                  "]]>$2")))]
    (spit plugin-xml xml)
    (println "Building" plugin-version "from" rev)))

(defn sync-kotlinc-plugin
  "Rewrite .idea/kotlinc.xml to point at a specific serialization compiler plugin."
  []
  (let [plugin-path (-> (api/create-basis {:aliases (into [:no-clojure :plugins])})
                        (get-in [:libs 'org.jetbrains.kotlin/kotlin-serialization-compiler-plugin :paths])
                        (first))]
    (println "Updating IntelliJ kotlinc config")
    (let [plugin-version (second (re-find #"/(\d+\.\d+\.\d+)/kotlin-serialization-compiler-plugin"
                                          plugin-path))
          kotlinc-xml-path ".idea/kotlinc.xml"
          kotlinc-config (slurp kotlinc-xml-path)
          with-plugin (str/replace kotlinc-config
                                   #"(<option name=\"additionalArguments\" value=\"[^\"]*-Xplugin=)[^\"]+(\" />)"
                                   (str "$1" plugin-path "$2"))
          jps-component (str "<component name=\"KotlinJpsPluginSettings\">\n"
                             "    <option name=\"version\" value=\"" plugin-version "\" />\n"
                             "  </component>")
          updated (if plugin-version
                    (if (str/includes? with-plugin "KotlinJpsPluginSettings")
                      (str/replace with-plugin
                                   #"(<component name=\"KotlinJpsPluginSettings\">\s*<option name=\"version\" value=\")[^\"]+(\" />)"
                                   (str "$1" plugin-version "$2"))
                      (str/replace with-plugin
                                   #"(</project>)"
                                   (str "  " jps-component "\n$1")))
                    with-plugin)]
      (spit kotlinc-xml-path updated))))

;; KSP

(defn- absolutize [project-root path]
  (let [cwd (System/getProperty "user.dir")
        file (io/file path)]
    (if (.isAbsolute file)
      file
      (if project-root
        (io/file cwd project-root path)
        (io/file cwd path)))))

(defn- basis-source-roots [basis project-root]
  (into []
        (comp
          (map #(absolutize project-root %))
          (filter #(.isDirectory ^File %)))
        (:classpath-roots basis)))

(defn ksp-run
  "Run the KSP CLI with basis-derived classpaths.
  Options:
    :project-root   Project root to bind tools.build (default \".\")
    :output-dir     Output directory for generated classes (required)
    :cache-dir      Cache directory (default output-dir)
    :src-dirs       Explicit source roots (defaults to dirs from :sdk-aliases basis)
    :aliases        Extra aliases appended to :ksp-aliases and :sdk-aliases
    :ksp-aliases    Aliases for KSP tool classpath (default [:no-clojure :ksp-plugin])
    :sdk-aliases    Aliases for libs classpath (default [:no-clojure :sdk])
    :jvm-target     JVM target (default \"21\")
    :language-version Kotlin language version (default \"2.0\")
    :api-version    Kotlin api version (default \"2.0\")
    :module-name    Module name (default \"main\")
    :processor-jar  Path to the KSP processor jar (required)
    :target-packages Vec of package filters (optional)
    :target-packages-prop System property name for target packages (optional)
    :allow-unsafe?  Add --sun-misc-unsafe-memory-access=allow (default false)
    :extra-jvm-opts Extra JVM options (vector) passed before main class
    :ksp-main       KSP main class (default \"com.google.devtools.ksp.cmdline.KSPJvmMain\")."
  [{:keys [project-root output-dir cache-dir src-dirs aliases ksp-aliases sdk-aliases
           jvm-target language-version api-version module-name processor-jar target-packages
           target-packages-prop allow-unsafe? extra-jvm-opts ksp-main]
    :or   {project-root   "." cache-dir nil ksp-aliases [:no-clojure :ksp-plugin] sdk-aliases [:no-clojure :sdk]
           jvm-target     "21" language-version "2.0" api-version "2.0" module-name "main" allow-unsafe? false
           extra-jvm-opts [] ksp-main "com.google.devtools.ksp.cmdline.KSPJvmMain"}}]
  (when-not processor-jar
    (throw (ex-info "KSP processor jar is required" {})))
  (binding [b/*project-root* project-root]
    (let [ksp-basis (b/create-basis {:aliases (into ksp-aliases aliases)})
          ksp-cp (mapcat :paths (vals (:libs ksp-basis)))
          libs-basis (b/create-basis {:aliases (into sdk-aliases aliases)})
          libs (mapcat :paths (vals (:libs libs-basis)))
          paths (map #(absolutize project-root %)
                     (or (seq src-dirs) (basis-source-roots libs-basis project-root)))
          cp (str/join File/pathSeparator ksp-cp)
          jdk-home (let [java-home (System/getProperty "java.home")
                         jdk (if (.endsWith java-home "jre")
                               (.getParentFile (io/file java-home))
                               (io/file java-home))]
                     (.getAbsolutePath jdk))
          cmdline (filterv some?
                           (concat ["java"]
                                   extra-jvm-opts
                                   (when allow-unsafe? ["--sun-misc-unsafe-memory-access=allow"])
                                   ["-Xmx2048m" "-cp" cp
                                    ksp-main
                                    "-jvm-target" jvm-target
                                    (str "-module-name=" module-name)
                                    "-jdk-home" jdk-home
                                    "-source-roots" (str/join File/pathSeparator (map #(.getPath ^File %) paths))
                                    "-libraries" (str/join File/pathSeparator libs)
                                    "-project-base-dir" "."
                                    "-output-base-dir" output-dir
                                    "-caches-dir" (or cache-dir output-dir)
                                    "-class-output-dir" output-dir
                                    "-kotlin-output-dir" output-dir
                                    "-java-output-dir" output-dir
                                    "-resource-output-dir" (str output-dir "/resources")
                                    "-language-version" language-version
                                    "-api-version" api-version
                                    "-incremental=false"
                                    "-incremental-log=false"
                                    processor-jar]
                                   (when (and target-packages-prop (seq target-packages))
                                     [(str "-D" target-packages-prop "=" (str/join "," target-packages))])))]
      (process/process {:command-args cmdline}))))

(defn compile-module
  ([module-config]
   (compile-module module-config false))
  ([{:keys [module module-path description depends
            javac-opts kotlinc-opts serialization? extra-aliases]
     :as   module-config}
    test?]
   (let [target (str "out/" (if test? "test" "production") "/" module)
         module-aliases (cond-> (into [:no-clojure :sdk] extra-aliases)
                          test? (into [:test :test-exec]))
         basis (binding [api/*project-root* module-path]
                 (api/create-basis {:aliases module-aliases}))
         dependency-dirs (into [] (map #(str "out/production/" %)) depends)
         production-dirs (when test?
                           (into []
                                 (comp
                                   (filter #(.isDirectory (io/file %)))
                                   (filter #(str/index-of % "out/production"))
                                   (map #(if (.isAbsolute (io/file %))
                                           %
                                           (.getAbsolutePath (io/file %)))))
                                 (:classpath-roots basis)))
         javac-opts (or javac-opts plugin-dev-tools.build/javac-opts)
         kotlinc-opts (let [opts (conj (vec (or kotlinc-opts plugin-dev-tools.build/kotlinc-opts)) "-module-name" module)
                            opts (if serialization?
                                   (let [serialization-plugin-path (-> (binding [api/*project-root* module-path]
                                                                         (api/create-basis {:aliases (into [:no-clojure :plugins])}))
                                                                       (get-in [:libs 'org.jetbrains.kotlin/kotlin-serialization-compiler-plugin :paths])
                                                                       (first))]
                                     (conj opts (str "-Xplugin=" serialization-plugin-path)))
                                   opts)
                            opts (if test?
                                   (conj opts (str "-Xfriend-paths=" (str/join "," production-dirs)))
                                   opts)]
                        opts)
         paths (reduce-kv (fn [ret k v]
                            (if (:path-key v)
                              (conj ret k)
                              ret))
                          []
                          (:classpath basis))
         find-paths (fn [patterns]
                      (into []
                            (comp
                              (filter (fn [path]
                                        (some #(str/index-of path %) patterns)))
                              (map #(path-to module-config %)))
                            paths))
         kotlin-paths (find-paths (if test?
                                    (:kotlin-test-paths module-config)
                                    (:kotlin-src-paths module-config)))
         java-paths (find-paths (if test?
                                  (:java-test-paths module-config)
                                  (:java-src-paths module-config)))
         clojure-paths (when-not test? (find-paths (:clojure-src-paths module-config)))]
     (when-not (and (empty? java-paths) (empty? kotlin-paths) (empty? clojure-paths))
       (println "Compiling" description (if test? "tests" ""))
       (when-not (empty? kotlin-paths)
         (println " - compiling Kotlin")
         (kotlinc (cond-> {:src-dirs     kotlin-paths
                           :class-dir    target
                           :basis        basis
                           :kotlinc-opts kotlinc-opts
                           :extra-paths  dependency-dirs}
                    test? (update :extra-paths into production-dirs))))
       (when-not (empty? java-paths)
         (println " - compiling Java")
         (javac (cond-> {:src-dirs   java-paths
                         :class-dir  target
                         :basis      basis
                         :javac-opts javac-opts
                         :extra-dirs dependency-dirs}
                  test? (update :extra-dirs into production-dirs))))
       (when-not (empty? clojure-paths)
         (println " - compiling Clojure")
         (api/compile-clj {:src-dirs  clojure-paths
                           :class-dir target
                           :basis     (update basis :classpath
                                              assoc target {:path-key :paths})}))))))


(defn build-module [{:keys [module module-path description resource-paths main-plugin? jar-file]
                     :as   module-config}]
  (let [target (str "out/production/" module)]
    (println "Building" description)
    (let [basis (binding [api/*project-root* module-path]
                  (api/create-basis {:aliases [:no-clojure :sdk]}))
          paths (reduce-kv (fn [ret k v]
                             (if (:path-key v)
                               (conj ret k)
                               ret))
                           []
                           (:classpath basis))
          resources (into []
                          (comp
                            (filter (fn [path]
                                      (some #(str/index-of path %) resource-paths)))
                            (map #(path-to module-config %)))
                          paths)]
      (when-not (empty? resources)
        (api/copy-dir {:src-dirs   resources
                       :target-dir target})))
    (when main-plugin?
      (let [config (edn/read-string (slurp "plugin.edn"))]
        (update-plugin-xml {:target           target
                            :plugin-version   (plugin-version config)
                            :base-dir         "."
                            :description-path "description.html"})))
    (api/jar {:class-dir target
              :jar-file  jar-file})))

;; Sandbox, packaging, verification

(defn prepare-sandbox
  "Populate a sandbox/plugins/<plugin-id>/lib directory with built jars and deps.
  Options:
    :sandbox-dir   Base sandbox directory (default \"sandbox\")"
  [{:keys [sandbox-dir] :or {sandbox-dir "sandbox"} :as args}]
  (let [basis (api/create-basis {:aliases (into [:no-clojure] (:extra-aliases args))})
        modules (module-info args)]
    (run! build-module modules)
    (let [plugin-jars (mapv :jar-file modules)
          dir (plugin-directory modules)
          disabled-file "disabled_plugins.txt"
          sandbox-lib (str sandbox-dir "/plugins/" dir "/lib")]
      (println "Preparing sandbox at" sandbox-lib)
      (b/delete {:path (str sandbox-dir "/plugins")})
      (doseq [jar plugin-jars]
        (b/copy-file {:src    jar
                      :target (str sandbox-lib "/" (.getName (io/file jar)))}))
      (when basis
        (doseq [root (classpath-files basis)]
          (b/copy-file {:src    root
                        :target (str sandbox-lib "/" (.getName (io/file root)))})))
      ;(doseq [{:keys [src target]} extra-copies]
      ;  (if (fs/directory? src)
      ;    (b/copy-dir {:src-dirs   [src]
      ;                 :target-dir target})
      ;    (b/copy-file {:src src :target target})))
      (when disabled-file
        (file/ensure-dir (str sandbox-dir "/config"))
        (b/copy-file {:src    disabled-file
                      :target (str sandbox-dir "/config/disabled_plugins.txt")})))))

(defn copy-to-zip
  "Copy a directory tree into a ZipOutputStream, preserving relative paths under root."
  [^ZipOutputStream jos ^File root ^File src-dir]
  (let [root-path (.toPath root)
        files (file/collect-files src-dir :dirs true)]
    (run! (fn [^File f]
            (let [rel-path (.toString (.relativize root-path (.toPath f)))]
              (when-not (= rel-path "")
                (#'zip/add-zip-entry jos rel-path f))))
          files)))

(defn package-plugin
  "Zip the sandbox plugin directory into build/distributions/<plugin-id>-<version>.zip.
  Options:
    :plugin-directory Plugin directory name under sandbox/plugins (required)
    :plugin-version Version string (required)
    :sandbox-dir    Sandbox root (default \"sandbox\")
    :output-dir     Override output dir (default \"build/distributions\")."
  [{:keys [plugin-module plugin-directory sandbox-dir plugin-version output-dir]
    :or   {sandbox-dir "sandbox" output-dir "build/distributions"}}]
  (let [zip-file (b/resolve-path (str output-dir "/" plugin-module "-" plugin-version ".zip"))
        class-dir (file/ensure-dir (b/resolve-path (str sandbox-dir "/plugins")))
        plugin-dir (file/ensure-dir (b/resolve-path (str sandbox-dir "/plugins/" plugin-directory)))]
    (file/ensure-dir (.getParent zip-file))
    (with-open [zos (ZipOutputStream. (FileOutputStream. zip-file))]
      (copy-to-zip zos class-dir plugin-dir))))


(defn verify-plugin
  "Download (if needed) and invoke IntelliJ Plugin Verifier.
  Options:
    :verifier-version Verifier version string (required)
    :idea-version     Target IDEA version (required)
    :plugin-path      Path to plugin directory or zip to verify (required)
    :ignored-file     Path to ignored-problems file (optional)
    :verifier-dir     Directory to store verifier jar (default \"sdks\")."
  [{:keys [verifier-version idea-version plugin-path ignored-file verifier-dir]
    :or   {verifier-dir "sdks"}}]
  (let [verifier-file (io/file verifier-dir (str "verifier-cli-" verifier-version ".jar"))]
    (when-not (.exists verifier-file)
      (println "Downloading verifier" verifier-version)
      (fs/create-dirs (io/file verifier-dir))
      (with-open [in (io/input-stream
                       (io/as-url (str "https://github.com/JetBrains/intellij-plugin-verifier/releases/download/"
                                       verifier-version
                                       "/verifier-cli-"
                                       verifier-version
                                       "-all.jar")))
                  out (io/output-stream verifier-file)]
        (io/copy in out)))
    (process/process {:command-args (cond-> ["java" "-Xmx4096m" "-jar" (.getPath verifier-file)
                                             "check-plugin" plugin-path
                                             (str verifier-dir "/" idea-version)]
                                      ignored-file (conj "-ignored-problems" ignored-file))})))

;; Top level commands

(defn compile [args]
  (clean args)
  (run! compile-module (module-info args))
  (sync-kotlinc-plugin))

(defn compile-tests [args]
  (run! #(compile-module % true) (module-info args)))

(defn package [args]
  (let [plugin-config (edn/read-string (slurp "plugin.edn"))
        modules (module-info args)]
    (clean args)
    (run! compile-module modules)
    (sync-kotlinc-plugin)
    (prepare-sandbox args)
    (package-plugin {:plugin-module    (plugin-module modules)
                     :plugin-directory (plugin-directory modules)
                     :plugin-version   (plugin-version plugin-config)})
    (let [formatter (DateTimeFormatter/ofPattern "uuuu-MM-dd HH:mm")
          now (LocalDateTime/now)]
      (println "Build finished at" (.format formatter now)))))
