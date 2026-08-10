(ns plugin-dev-tools.build
  (:refer-clojure :exclude [compile])
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.build.api :as api]
            [clojure.tools.build.tasks.process :as process]
            [clojure.tools.build.util.file :as file]
            [clojure.tools.build.util.zip :as zip]
            [plugin-dev-tools.testing :as testing])
  (:import (java.io File FileOutputStream)
           (java.net ServerSocket URL URLClassLoader)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)
           (java.util.zip ZipOutputStream)
           (javax.tools DiagnosticListener ToolProvider)))

(declare build-module get-plugin-id)

(def default-jvm-target "21")

(def jvm-target default-jvm-target)

(defn default-javac-opts
  [jvm-target]
  ["--release" jvm-target "-Xlint:deprecation" "-proc:none"])

(def javac-opts (default-javac-opts jvm-target))

(defn- kotlin-at-least?
  [version major minor]
  (let [[v-major v-minor] (let [parts (->> (or version "")
                                           (re-seq #"\d+")
                                           (take 2)
                                           (mapv #(Integer/parseInt %)))]
                            [(or (nth parts 0 nil) 0)
                             (or (nth parts 1 nil) 0)])]
    (or (> v-major major)
        (and (= v-major major)
             (>= v-minor minor)))))

(defn- kotlinc-jvm-default-opt
  [kotlin-version]
  (if (kotlin-at-least? kotlin-version 2 2)
    "-jvm-default=no-compatibility"
    "-Xjvm-default=all"))

(defn default-kotlinc-opts
  ([kotlin-version]
   (default-kotlinc-opts kotlin-version jvm-target))
  ([kotlin-version jvm-target]
   ["-jvm-target" jvm-target
    "-no-stdlib"
    (kotlinc-jvm-default-opt kotlin-version)
    "-language-version" "2.2"]))

(def kotlinc-opts (default-kotlinc-opts "2.2.0"))

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

(defn- intellij-sdk-path
  []
  (try
    (get-in (edn/read-string (slurp "deps.edn"))
            [:aliases :sdk :extra-deps 'intellij/sdk :local/root])
    (catch Exception _
      nil)))

(defn- product-info
  [intellij-sdk]
  (try
    (let [file (io/file intellij-sdk "product-info.json")]
      (when (.exists file)
        (json/read-str (slurp file) :key-fn keyword)))
    (catch Exception _
      nil)))

(defn- product-info-jvm-target
  [product-info]
  (some-> (:minRequiredJavaVersion product-info) str))

(defn- project-jvm-target
  []
  (or (some-> (intellij-sdk-path) product-info product-info-jvm-target)
      jvm-target))

(defn module-info
  "Returns elaborated module info from plugin.edn in dependency order."
  [args]
  (let [config (edn/read-string (slurp "plugin.edn"))
        kotlin-version (:kotlin-version config)
        jvm-target (project-jvm-target)
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
                                   plugin-directory (or (:plugin-directory details) module)
                                   include-in-sandbox? (if (contains? details :include-in-sandbox?)
                                                         (:include-in-sandbox? details)
                                                         true)
                                   merge-into-main? (if (contains? details :merge-into-main?)
                                                      (:merge-into-main? details)
                                                      false)
                                   intellij-tests? (if (contains? details :intellij-tests?)
                                                     (:intellij-tests? details)
                                                     true)]
                               (assoc ret module (assoc details :module module
                                                                :module-path module-path
                                                                :deps-file deps-file
                                                                :jar-file jar-file
                                                                :plugin-directory plugin-directory
                                                                :include-in-sandbox? include-in-sandbox?
                                                                :merge-into-main? merge-into-main?
                                                                :intellij-tests? intellij-tests?
                                                                :kotlin-version kotlin-version
                                                                :jvm-target jvm-target))))
                           (sorted-map)
                           (:modules config))
        deps-map (reduce (fn [ret {:keys [module depends ksp ksp-test]}]
                           (let [processor-deps (keep :processor-module [ksp ksp-test])
                                 depends (set (concat depends processor-deps))]
                             (assoc ret module depends)))
                         {}
                         (vals modules))
        order (kahn-sort deps-map)]
    (if (nil? order)
      (throw (ex-info "Dependency cycle" {:deps deps-map}))
      (let [modules-with-deps (into {} (map (fn [[k v]] [k (assoc v :depends (get deps-map k))]) modules))
            ret (mapv #(assoc % :all-modules modules-with-deps)
                      (mapv modules (reverse order)))]
        (if-let [build-ns (find-ns 'build)]
          (if-let [customise (ns-resolve build-ns 'customise-modules)]
            (customise ret args)
            ret)
          ret)))))

(defn- clean*
  [args tests?]
  (let [modules (module-info args)
        dir (plugin-directory modules)
        ksp-dirs (for [m modules
                       suffix (cond-> ["src/generated/ksp"]
                                tests? (conj "test/generated/ksp"))
                       :when (get m (if (= suffix "test/generated/ksp") :ksp-test :ksp))]
                   (path-to m suffix))
        dirs (into (cond-> ["out/production"
                            "out/generated"
                            "build-tools/build"
                            (str "sandbox/plugins/" dir "/lib")]
                     tests? (conj "out/test"))
                   (concat (map #(path-to % "build") modules)
                           ksp-dirs))]
    (doseq [path dirs]
      (api/delete {:path path}))))

(defn clean [args]
  (clean* args true))

(defn clean-sandbox
  "Delete the entire sandbox root so the next launch starts from scratch.
  Options:
    :sandbox-dir   Base sandbox directory (default \"sandbox\")"
  [{:keys [sandbox-dir] :or {sandbox-dir "sandbox"}}]
  (api/delete {:path sandbox-dir}))

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
      (let [class-dir (file/ensure-dir (api/resolve-path class-dir))
            compiler (ToolProvider/getSystemJavaCompiler)
            listener (reify DiagnosticListener (report [_ diag] (println (str diag))))
            file-mgr (.getStandardFileManager compiler listener nil nil)
            class-dir-path (.getPath class-dir)
            classpath (str/join File/pathSeparator (-> []
                                                       (into (mapcat :paths) (vals libs))
                                                       (conj class-dir-path)
                                                       (into (map (fn [dir]
                                                                    (-> (api/resolve-path dir)
                                                                        (file/ensure-dir)
                                                                        (.getPath))))
                                                             extra-dirs)))
            options (concat ["-classpath" classpath "-d" class-dir-path] javac-opts)
            java-files (mapcat #(file/collect-files (api/resolve-path %) :collect (file/suffixes ".java")) src-dirs)
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
      (let [class-dir (file/ensure-dir (api/resolve-path class-dir))
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

(defn- newest-file-time
  "Return the newest modification time (millis) across files/dirs, or nil when none exist."
  [paths]
  (let [update-max (fn update-max [acc path]
                     (let [f (fs/file path)]
                       (cond
                         (fs/regular-file? f)
                         (let [t (-> f fs/last-modified-time fs/file-time->millis)]
                           (if acc (max acc t) t))

                         (fs/directory? f)
                         (let [latest (reduce update-max nil (filter fs/regular-file? (file-seq f)))]
                           (if latest (if acc (max acc latest) latest) acc))

                         :else acc)))]
    (reduce update-max nil paths)))

(defn needs-build?
  "Returns true when outputs are missing/empty or any input is newer than outputs."
  [outputs inputs]
  (let [inputs (remove nil? inputs)
        outputs (remove nil? outputs)
        missing-output? (some #(not (fs/exists? %)) outputs)
        newest-input (newest-file-time inputs)
        newest-output (newest-file-time outputs)]
    (cond
      (empty? outputs) true
      missing-output? true
      (nil? newest-output) true
      (nil? newest-input) false
      (> newest-input newest-output) true
      :else false)))

(defn jj-revision
  "Return jj revision ID in dir (default \".\")."
  ([] (jj-revision "."))
  ([dir]
   (-> (api/process {:command-args ["jj" "log" "-r" "@-" "--no-graph" "-T" "change_id.short() ++ \" \" ++ commit_id.short(8) ++ \"\\n\""]
                     :dir          dir
                     :out          :capture})
       :out
       str/trim)))

(defn- idea-build-major
  [{:keys [platform-version idea-version]}]
  (let [candidates (->> [platform-version idea-version]
                        (map #(some-> % str str/trim))
                        (remove str/blank?))]
    (or (some (fn [version]
                (some->> (re-matches #"^(\d{3})(?:\..*)?$" version)
                         second))
              candidates)
        (some (fn [version]
                (when-let [[_ year minor] (re-matches #"^(\d{4})\.(\d+)(?:[.-].*)?$" version)]
                  (str "2" (mod (parse-long year) 10) minor)))
              candidates)
        (throw (ex-info "Could not derive IntelliJ build major version"
                        {:platform-version platform-version
                         :idea-version     idea-version})))))

(defn- idea-version-tag
  [args]
  (let [build-major (idea-build-major args)
        until-build (str build-major ".*")]
    (str "<idea-version since-build=\"" build-major ".0\""
         " until-build=\"" until-build "\""
         (when (>= (parse-long build-major) 253)
           (str " strict-until-build=\"" until-build "\""))
         "/>")))

(defn update-plugin-xml
  "Update plugin.xml with version, description, build metadata and optional resource copy.
  Options:
    :target            Path to compiled output root (required)
    :plugin-version    Version string (required)
    :base-dir          Root dir for description/resources (default \".\")
    :description-path  Path to description.html (default \"description.html\")
    :plugin-xml-path   Override plugin.xml path (defaults to <target>/META-INF/plugin.xml)
    :copy-resources?   Copy resource dirs into target before writing plugin.xml
    :resource-dirs     Seq of resource dirs (relative or absolute) to copy when copy-resources? is true.
    :idea-version      IntelliJ version string used to derive the idea-version tag
    :platform-version  IntelliJ build major or branch used to derive the idea-version tag."
  [{:keys [target plugin-version base-dir description-path plugin-xml-path copy-resources? resource-dirs]
    :as   args
    :or   {base-dir "." description-path "description.html"}}]
  (when copy-resources?
    (doseq [dir resource-dirs]
      (api/copy-dir {:src-dirs   [(str (io/file base-dir dir))]
                     :target-dir target})))
  (let [rev (jj-revision base-dir)
        description (slurp (str (io/file base-dir description-path)))
        now (-> (LocalDateTime/now)
                (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))
        plugin-xml (or plugin-xml-path (str target "/META-INF/plugin.xml"))
        xml (-> plugin-xml
                slurp
                (str/replace #"(<version>).*(</version>)"
                             (str "$1" plugin-version "$2"))
                (str/replace #"(?m)^(\s*)<idea-version\b[^>]*/>"
                             (fn [[_ indent]]
                               (str indent (idea-version-tag args))))
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
    :extra-libraries Additional library classpath entries appended to :sdk-aliases deps
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
  [{:keys [project-root output-dir cache-dir src-dirs aliases ksp-aliases sdk-aliases extra-libraries
           jvm-target language-version api-version module-name processor-jar target-packages
           target-packages-prop allow-unsafe? extra-jvm-opts ksp-main]
    :or   {project-root   "." cache-dir nil ksp-aliases [:no-clojure :ksp-plugin] sdk-aliases [:no-clojure :sdk]
           jvm-target     default-jvm-target language-version "2.0" api-version "2.0" module-name "main" allow-unsafe? false
           extra-jvm-opts [] ksp-main "com.google.devtools.ksp.cmdline.KSPJvmMain"}}]
  (when-not processor-jar
    (throw (ex-info "KSP processor jar is required" {})))
  (let [aliases (or aliases [])
        ksp-aliases (or ksp-aliases [:no-clojure :ksp-plugin])
        sdk-aliases (or sdk-aliases [:no-clojure :sdk])
        extra-jvm-opts (or extra-jvm-opts [])
        extra-libraries (or extra-libraries [])
        ksp-basis (binding [api/*project-root* project-root]
                    (api/create-basis {:aliases (into ksp-aliases aliases)}))
        ksp-cp (mapcat :paths (vals (:libs ksp-basis)))
        libs-basis (api/create-basis {:aliases (into sdk-aliases aliases)})
        libs (concat (mapcat :paths (vals (:libs libs-basis))) extra-libraries)
        paths (map #(absolutize project-root %)
                   (or (seq src-dirs) (basis-source-roots libs-basis project-root)))
        cp (str/join File/pathSeparator ksp-cp)
        jdk-home (let [java-home (System/getProperty "java.home")
                       jdk (if (.endsWith java-home "jre")
                             (.getParentFile (io/file java-home))
                             (io/file java-home))]
                   (.getAbsolutePath jdk))
        main-present? (or (not ksp-main)
                          (not (seq ksp-cp))
                          (try
                            (let [urls (into-array URL (map #(-> % io/file .toURI .toURL) ksp-cp))]
                              (with-open [loader (URLClassLoader. urls)]
                                (.loadClass loader ksp-main)))
                            true
                            (catch ClassNotFoundException _
                              false)))]
    (if-not main-present?
      (do
        (println "Skipping KSP run: missing main class" ksp-main)
        nil)
      (let [system-props (when (and target-packages-prop (seq target-packages))
                           [(str "-D" target-packages-prop "=" (str/join "," target-packages))])
            cmdline (filterv some?
                             (concat ["java"]
                                     extra-jvm-opts
                                     system-props
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
                                      processor-jar]))]
        (process/process {:command-args cmdline})))))

(defn- generated-source-root?
  [path]
  (let [path (str/replace (str path) #"\\\\" "/")]
    (or (str/ends-with? path "/generated")
        (= path "generated")
        (str/includes? path "/generated/"))))

(defn- test-ksp-source-dirs
  [{:keys [kotlin-test-paths java-test-paths]} ksp]
  (or (:src-dirs ksp)
      (vec (distinct (remove generated-source-root?
                             (concat kotlin-test-paths java-test-paths))))))

(defn- test-ksp-extra-libraries
  [{:keys [module depends]} ksp]
  (vec (distinct (concat [(str "out/production/" module)]
                         (map #(str "out/production/" %) depends)
                         (:extra-libraries ksp)))))

(defn- module-ksp-options
  "Build ksp-run options for a module config (or test variant).
  Returns nil when no KSP config."
  [{:keys [module module-path all-modules] :as module-config} test?]
  (let [ksp (get module-config (if test? :ksp-test :ksp))]
    (when ksp
      (let [processor-module (:processor-module ksp)
            proc-info (get all-modules processor-module)]
        (when-not proc-info
          (throw (ex-info "Processor module not found" {:processor-module processor-module
                                                        :available        (keys all-modules)})))
        (let [processor-jar (:jar-file proc-info)
              cache-dir (str (when (not= (:module-path proc-info) ".")
                               (str (:module-path proc-info) "/"))
                             "build/caches")
              output-dir (str (when (not= module-path ".") (str module-path "/"))
                              (if test? "test/generated/ksp" "src/generated/ksp"))]
          (merge {:project-root         module-path
                  :output-dir           output-dir
                  :cache-dir            cache-dir
                  :processor-jar        processor-jar
                  :target-packages      (:target-packages ksp)
                  :target-packages-prop (:target-packages-prop ksp)
                  :aliases              (:aliases ksp)
                  :ksp-aliases          (:ksp-aliases ksp)
                  :sdk-aliases          (:sdk-aliases ksp)
                  :src-dirs             (if test?
                                          (test-ksp-source-dirs module-config ksp)
                                          (:src-dirs ksp))
                  :extra-libraries      (if test?
                                          (test-ksp-extra-libraries module-config ksp)
                                          (:extra-libraries ksp))
                  :allow-unsafe?        (:allow-unsafe? ksp)
                  :extra-jvm-opts       (:extra-jvm-opts ksp)
                  :module-name          (or (:module-name ksp) module)
                  :jvm-target           (:jvm-target module-config)}
                 (select-keys ksp [:jvm-target :language-version :api-version])))))))

(defn- ensure-processor-jar
  "Ensure the processor jar exists by building the processor module if needed."
  [processor-module all-modules]
  (let [{:keys [jar-file] :as proc-info} (get all-modules processor-module)]
    (when-not proc-info
      (throw (ex-info "Processor module not found" {:processor-module processor-module
                                                    :available        (keys all-modules)})))
    (when-not (fs/exists? jar-file)
      (build-module proc-info))
    jar-file))

(defn run-module-ksp
  "Invoke KSP for a module if configured. Returns nil when no KSP config."
  [module-config test?]
  (when-let [opts (module-ksp-options module-config test?)]
    (ensure-processor-jar (:processor-module (get module-config (if test? :ksp-test :ksp)))
                          (:all-modules module-config))
    (println "Running KSP for" (:description module-config) (if test? "(test)" ""))
    (ksp-run opts)))

(defn compile-module
  ([module-config]
   (compile-module module-config false))
  ([{:keys [module module-path description depends
            javac-opts kotlinc-opts serialization? extra-aliases kotlin-version jvm-target]
     :as   module-config}
    test?]
   (let [target (str "out/" (if test? "test" "production") "/" module)
         module-aliases (cond-> (into [:no-clojure :sdk] extra-aliases)
                          test? (into [:test :test-exec]))
         basis (binding [api/*project-root* module-path]
                 (api/create-basis {:aliases module-aliases}))
         dep-prod-dirs (into [] (map #(str "out/production/" %)) depends)
         self-prod (str "out/production/" module)
         prod-dirs (when test?
                     (into []
                           (keep (fn [m]
                                   (let [p (str "out/production/" m)]
                                     (when (fs/directory? p)
                                       (.getAbsolutePath (io/file p))))))
                           (into [module] depends)))
         dependency-dirs (if test?
                           (into [self-prod] dep-prod-dirs)
                           dep-prod-dirs)
         production-dirs prod-dirs
         jvm-target (or jvm-target default-jvm-target)
         javac-opts (or javac-opts (default-javac-opts jvm-target))
         kotlinc-opts (let [base-opts (or kotlinc-opts (default-kotlinc-opts kotlin-version jvm-target))
                            opts (conj (vec base-opts) "-module-name" module)
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
         clojure-paths (when-not test? (find-paths (:clojure-src-paths module-config)))
         resource-paths (map #(path-to module-config %) (:resource-paths module-config))
         ksp-opts (when-not test? (module-ksp-options module-config false))
         ksp-test-opts (when test? (module-ksp-options module-config true))
         generated-dirs (into [] (keep :output-dir) [ksp-opts ksp-test-opts])
         kotlin-paths (let [paths (vec kotlin-paths)
                            prefixes (map fs/absolutize paths)
                            extras (if (seq paths)
                                     (remove (fn [p]
                                               (let [p* (fs/absolutize p)]
                                                 (some #(fs/starts-with? p* %) prefixes)))
                                             generated-dirs)
                                     generated-dirs)]
                        (vec (distinct (into paths extras))))
         resource-paths (let [generated-resources (map #(str % "/resources") generated-dirs)]
                          (vec (distinct (into resource-paths generated-resources))))
         inputs (into []
                      (comp
                        cat
                        (remove nil?))
                      [kotlin-paths
                       java-paths
                       clojure-paths
                       resource-paths
                       generated-dirs
                       [(:deps-file module-config)
                        "plugin.edn"]])
         outputs (into [target] generated-dirs)]
     (if (or (empty? inputs)
             (needs-build? outputs inputs))
       (do
         (doseq [dir (into [target] generated-dirs)]
           (when (fs/exists? dir)
             (fs/delete-tree dir))
           (fs/create-dirs dir))
         (when (and (empty? java-paths) (empty? kotlin-paths) (empty? clojure-paths))
           (println "Compiling" description (if test? "tests" "") "(no sources found, only KSP/resources may run)"))
         (run-module-ksp module-config (boolean test?))
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
                                                  assoc target {:path-key :paths})}))))
       (println "Skipping" description (if test? "tests" "") "- up to date")))))

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
        (api/copy-dir {:src-dirs   (-> resources
                                       (into (map #(path-to module-config %)) resource-paths)
                                       distinct)
                       :target-dir target})))
    (when main-plugin?
      (let [all-modules (vals (:all-modules module-config))
            mergees (filter #(and (not= (:module %) module)
                                  (:merge-into-main? %))
                            all-modules)]
        (doseq [{:keys [module module-path resource-paths]} mergees
                :let [src (str "out/production/" module)]]
          (when (fs/exists? src)
            (api/copy-dir {:src-dirs   [src]
                           :target-dir target}))
          (doseq [res resource-paths]
            (let [res-path (path-to {:module-path module-path} res)]
              (when (fs/exists? res-path)
                (api/copy-dir {:src-dirs   [res-path]
                               :target-dir target})))))))
    (when main-plugin?
      (let [config (edn/read-string (slurp "plugin.edn"))]
        (update-plugin-xml {:target           target
                            :plugin-version   (plugin-version config)
                            :base-dir         "."
                            :description-path "description.html"
                            :copy-resources?  true
                            :resource-dirs    resource-paths
                            :idea-version     (:idea-version config)
                            :platform-version (:platform-version config)})))
    (api/jar {:class-dir target
              :jar-file  jar-file})))

;; Sandbox, packaging, verification

(defn prepare-sandbox
  "Populate a sandbox/plugins/<plugin-id>/lib directory with built jars and deps.
  Options:
    :sandbox-dir   Base sandbox directory (default \"sandbox\")"
  [{:keys [sandbox-dir] :or {sandbox-dir "sandbox"} :as args}]
  (let [basis (api/create-basis {:aliases (into [:no-clojure] (:extra-aliases args))})
        modules (module-info args)
        sandbox-modules (filter #(get % :include-in-sandbox? true) modules)]
    (run! build-module modules)
    (let [plugin-jars (mapv :jar-file sandbox-modules)
          dir (plugin-directory modules)
          disabled-file "disabled_plugins.txt"
          sandbox-lib (str sandbox-dir "/plugins/" dir "/lib")]
      (println (str "Preparing sandbox at " sandbox-dir " (plugin lib: plugins/" dir "/lib)"))
      (api/delete {:path (str sandbox-dir "/plugins")})
      (doseq [jar plugin-jars]
        (api/copy-file {:src    jar
                        :target (str sandbox-lib "/" (.getName (io/file jar)))}))
      (when basis
        (doseq [root (classpath-files basis)]
          (api/copy-file {:src    root
                          :target (str sandbox-lib "/" (.getName (io/file root)))})))
      ;(doseq [{:keys [src target]} extra-copies]
      ;  (if (fs/directory? src)
      ;    (api/copy-dir {:src-dirs   [src]
      ;                 :target-dir target})
      ;    (api/copy-file {:src src :target target})))
      (when disabled-file
        (file/ensure-dir (str sandbox-dir "/config"))
        (api/copy-file {:src    disabled-file
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
  (let [zip-file (api/resolve-path (str output-dir "/" plugin-module "-" plugin-version ".zip"))
        class-dir (file/ensure-dir (api/resolve-path (str sandbox-dir "/plugins")))
        plugin-dir (file/ensure-dir (api/resolve-path (str sandbox-dir "/plugins/" plugin-directory)))]
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
  (run! compile-module (module-info args))
  (sync-kotlinc-plugin))

(defn compile-tests [args]
  (run! #(compile-module % true) (module-info args)))

(defn generate-ksp
  "Generate all KSP files for production and test code across all modules.
  Useful for IDE support while editing."
  [args]
  (let [modules (module-info args)
        ;; Collect unique processor modules
        processor-modules (->> modules
                               (mapcat (fn [m]
                                         [(get-in m [:ksp :processor-module])
                                          (get-in m [:ksp-test :processor-module])]))
                               (remove nil?)
                               distinct)]
    ;; Compile and build processor modules first
    (doseq [proc-name processor-modules
            :let [proc-module (get (:all-modules (first modules)) proc-name)]]
      (compile-module proc-module)
      (build-module proc-module))
    ;; Run KSP for all modules. Test KSP needs production classes available
    ;; as libraries, so compile production first for modules with test processors.
    (doseq [m modules]
      (if (:ksp-test m)
        (do
          (compile-module m)
          (run-module-ksp m true))
        (when (:ksp m)
          (run-module-ksp m false))))))

(defn package [args]
  (let [plugin-config (edn/read-string (slurp "plugin.edn"))
        modules (module-info args)
        {:keys [compile] :or {compile true}} args]
    (when compile
      (clean* args false)
      (run! compile-module modules))
    (sync-kotlinc-plugin)
    (prepare-sandbox args)
    (package-plugin {:plugin-module    (plugin-module modules)
                     :plugin-directory (plugin-directory modules)
                     :plugin-version   (plugin-version plugin-config)})
    (let [formatter (DateTimeFormatter/ofPattern "uuuu-MM-dd HH:mm")
          now (LocalDateTime/now)]
      (println "Build finished at" (.format formatter now)))))

;; =============================================================================
;; IDE Execution
;; =============================================================================

(defn- debug-enabled?
  [{:keys [debug]}]
  (cond
    (true? debug) true
    (false? debug) false
    (string? debug) (contains? #{"1" "true" "yes" "y" "on"}
                                (str/lower-case (str/trim debug)))
    :else false))

(defn- find-free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.setReuseAddress socket true)
    (.getLocalPort socket)))

(defn- parse-debug-port
  [port]
  (cond
    (nil? port) nil
    (integer? port) (int port)
    (number? port) (int port)
    (string? port) (try
                     (Integer/parseInt (str/trim port))
                     (catch NumberFormatException _
                       nil))
    :else nil))

(defn- resolve-debug-port
  [args]
  (when (debug-enabled? args)
    (let [provided-port (cond
                          (contains? args :debug-port) (:debug-port args)
                          (contains? args :port) (:port args)
                          :else nil)
          parsed-port (if (some? provided-port)
                        (parse-debug-port provided-port)
                        (find-free-port))]
      (when-not (and parsed-port (<= 1 parsed-port 65535))
        (throw (ex-info "Invalid :debug-port/:port, expected integer in range 1-65535"
                        {:debug-port (:debug-port args)
                         :port (:port args)})))
      parsed-port)))

(defn- ide-jvm-args
  [{:keys [intellij-sdk sandbox-dir plugin-id launch-config current-os debug-port]}]
  (let [vm-opts-path (when-let [path (:vmOptionsFilePath launch-config)]
                       (str intellij-sdk "/" path))
        vm-opts (or (testing/load-vm-options vm-opts-path) [])
        launch-jvm-args (->> (:additionalJvmArguments launch-config)
                             (map #(testing/resolve-path-variables % intellij-sdk)))
        ide-props ["-Didea.classpath.index.enabled=false"
                   "-Didea.is.internal=true"
                   "-Didea.plugin.in.sandbox.mode=true"
                   "-Didea.vendor.name=JetBrains"
                   "-Dide.no.platform.update=false"
                   "-Dide.experimental.ui.onboarding=false"
                   "-Djdk.module.illegalAccess.silent=true"
                   (str "-Didea.config.path=" sandbox-dir "/config")
                   (str "-Didea.plugins.path=" sandbox-dir "/plugins")
                   (str "-Didea.system.path=" sandbox-dir "/system")
                   (str "-Didea.log.path=" sandbox-dir "/system/log")
                   (str "-Didea.required.plugins.id=" plugin-id)
                   "-Didea.auto.reload.plugins=true"
                   "-Dide.native.launcher=false"
                   "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader"]
        platform-props (case current-os
                         "macOS" ["-Didea.smooth.progress=false"
                                   "-Dapple.laf.useScreenMenuBar=true"
                                   "-Dapple.awt.fileDialogForDirectories=true"]
                         "Linux" ["-Dsun.awt.disablegrab=true"]
                         [])
        debug-arg (when debug-port
                    (str "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" debug-port))]
    (concat vm-opts
            launch-jvm-args
            ide-props
            platform-props
            (when debug-arg [debug-arg]))))

(defn- ide-classpath-entries
  [intellij-sdk launch-config]
  (let [lib-dir (str intellij-sdk "/lib")
        boot-jars (or (:bootClassPathJarNames launch-config)
                      ["3rd-party-rt.jar" "jna.jar" "util.jar" "util_rt.jar"])
        jar-paths (->> boot-jars
                       (map #(str lib-dir "/" %))
                       (filter fs/exists?)
                       vec)]
    (when (empty? jar-paths)
      (throw (ex-info "Could not resolve IDE boot classpath jars"
                      {:boot-jars boot-jars :lib-dir lib-dir})))
    jar-paths))

(defn- fail!
  [message]
  (binding [*out* *err*]
    (println "Error:" message))
  (System/exit 1))

(defn- absolute-path
  [path]
  (some-> path fs/absolutize str))

(defn- project-sandbox-dir
  [project-path]
  (when project-path
    (let [project-path (fs/absolutize project-path)
          project-dir (if (.isFile (io/file (str project-path)))
                        (fs/parent project-path)
                        project-path)]
      (str (fs/path project-dir ".sandbox")))))

(defn- resolve-sandbox-dir
  [args]
  (absolute-path (or (:sandbox-dir args)
                     (project-sandbox-dir (:project-path args))
                     "sandbox")))

(defn- app-arg-vector
  [app-args]
  (cond
    (nil? app-args) []
    (sequential? app-args) (mapv str app-args)
    :else [(str app-args)]))

(defn- dont-reopen-projects?
  [args]
  (if (contains? args :dont-reopen-projects?)
    (:dont-reopen-projects? args)
    (boolean (:project-path args))))

(defn- ide-app-args
  [args]
  (cond-> []
    (dont-reopen-projects? args)
    (conj "dontReopenProjects")

    (:project-path args)
    (conj (absolute-path (:project-path args)))

    (:app-args args)
    (into (app-arg-vector (:app-args args)))))

(defn- ide-launch-params
  [args]
  (let [modules (module-info args)
        sandbox-dir (resolve-sandbox-dir args)
        intellij-sdk (some-> (testing/find-intellij-sdk) absolute-path)
        _ (when-not intellij-sdk
            (fail! "Could not find IntelliJ SDK path in deps.edn"))
        plugin-id (get-plugin-id)
        _ (when-not plugin-id
            (fail! ":plugin-id not found in plugin.edn"))
        product-info (testing/read-product-info intellij-sdk)
        current-os (testing/detect-os)
        current-arch (testing/detect-architecture)
        launch-config (testing/find-launch-config product-info current-os current-arch)
        _ (when-not launch-config
            (fail! (str "Could not find launch configuration for " current-os " " current-arch)))
        debug-port (try
                     (resolve-debug-port args)
                     (catch Exception e
                       (fail! (.getMessage e))))
        launch-jvm-target (or (product-info-jvm-target product-info)
                              (some :jvm-target modules)
                              default-jvm-target)]

    (println "Compiling modules...")
    (run! compile-module modules)

    (testing/ensure-sandbox! sandbox-dir)
    (prepare-sandbox {:sandbox-dir sandbox-dir})

    (println "Using IntelliJ SDK:" intellij-sdk)

    (let [java-exec (testing/find-java-exec intellij-sdk)
          jvm-args (vec (ide-jvm-args {:intellij-sdk  intellij-sdk
                                       :sandbox-dir   sandbox-dir
                                       :plugin-id     plugin-id
                                       :launch-config launch-config
                                       :current-os    current-os
                                       :debug-port    debug-port}))
          classpath-entries (ide-classpath-entries intellij-sdk launch-config)
          main-class (or (:mainClass launch-config) "com.intellij.idea.Main")
          bin-dir (str intellij-sdk "/bin")]
      (cond-> {:version 1
               :cwd bin-dir
               :javaPath java-exec
               :javaRequirements {:major (Integer/parseInt launch-jvm-target)
                                  :jbr true}
               :mainClass main-class
               :vmArgs jvm-args
               :classpathEntries classpath-entries
               :appArgs (ide-app-args args)}
        debug-port (assoc :debugPort debug-port)))))

(defn ide-params
  "Print IDE launch parameters as JSON.

   Options from args:
   - :sandbox-dir              Sandbox dir (default \"sandbox\")
   - :project-path             Optional project/file path to open on startup; defaults sandbox to <project>/.sandbox
   - :dont-reopen-projects?    Optional flag to pass dontReopenProjects launcher arg (defaults true when :project-path is set)
   - :app-args                 Optional extra launcher args appended after :project-path
   - :debug                    Optional flag to add JDWP debug JVM argument
   - :debug-port               Optional debug port (alias: :port); if omitted and :debug is true, auto-selects a free port.

   Compiles all modules, prepares sandbox, then prints JSON launch parameters to stdout."
  [args]
  (let [params (binding [*out* *err*]
                 (ide-launch-params args))]
    (println (json/write-str params))))

(defn run-ide
  "Deprecated alias for ide-params. Use ide-params with scripts/run-ide."
  [args]
  (ide-params args))

;; =============================================================================
;; Test Execution
;; =============================================================================

(defn- get-plugin-id
  "Get the plugin ID from plugin.edn"
  []
  (let [config (edn/read-string (slurp "plugin.edn"))]
    (:plugin-id config)))

(defn- build-test-args
  "Build JUnit test selection arguments from options.
   Accepts :class, :method, or :package as symbols.
   Method can use either foo.Bar.testMethod or foo.Bar#testMethod syntax."
  [{:keys [class method package]}]
  (cond
    class ["-c" (str class)]
    method (let [m (str method)
                 ;; Convert foo.Bar.testMethod to foo.Bar#testMethod if needed
                 m (if (str/includes? m "#")
                     m
                     ;; Find last dot and replace with #
                     (if-let [idx (str/last-index-of m ".")]
                       (str (subs m 0 idx) "#" (subs m (inc idx)))
                       m))]
             ["-m" m])
    package ["-p" (str package)]
    :else nil))

(defn test-module
  "Run tests for a single module.

   Options from args:
   - :class   - Test class to run (symbol, e.g. foo.BarTest)
   - :method  - Test method to run (symbol, e.g. foo.BarTest.testSomething or foo.BarTest#testSomething)
   - :package - Test package to run (symbol, e.g. foo.bar)

   Uses :intellij-tests? from module config to determine whether to run with
   IntelliJ test framework infrastructure or simple JUnit."
  [module-config args]
  (let [{:keys [intellij-tests? kotlin-test-paths java-test-paths module module-path]} module-config
        test-args (build-test-args args)
        test-output-dir (str "out/test/" module)
        sandbox-dir (or (:sandbox-dir args) "sandbox/test")
        ;; For submodules, run -Spath from their directory to get their deps
        module-root (when (not= module-path ".") module-path)]

    ;; Check if module has tests
    (when (and (empty? kotlin-test-paths) (empty? java-test-paths))
      (println "No test paths configured for module" module)
      (System/exit 0))

    ;; Compile production code and tests
    (println "=== Testing module:" module "===")
    (println)

    (if intellij-tests?
      ;; IntelliJ test framework execution
      (let [intellij-sdk (testing/find-intellij-sdk)
            _ (when-not intellij-sdk
                (println "Error: Could not find IntelliJ SDK path in deps.edn")
                (System/exit 1))
            plugin-id (get-plugin-id)
            _ (when-not plugin-id
                (println "Error: :plugin-id not found in plugin.edn")
                (System/exit 1))
            product-info (testing/read-product-info intellij-sdk)
            current-os (testing/detect-os)
            current-arch (testing/detect-architecture)
            launch-config (testing/find-launch-config product-info current-os current-arch)
            _ (when-not launch-config
                (println "Error: Could not find launch configuration for" current-os current-arch)
                (System/exit 1))]

        (println "Using IntelliJ SDK:" intellij-sdk)

        (let [java-exec (testing/find-java-exec intellij-sdk)
              test-classpath (testing/get-test-classpath [:test :test-exec :sdk]
                                                         :project-root module-root)
              _ (when-not test-classpath
                  (println "Error: Failed to resolve test classpath")
                  (System/exit 1))
              full-classpath (str test-output-dir ":" test-classpath)
              jvm-args (testing/intellij-test-jvm-args {:intellij-sdk  intellij-sdk
                                                        :sandbox-dir   sandbox-dir
                                                        :plugin-id     plugin-id
                                                        :launch-config launch-config})
              exit-code (testing/run-junit-tests {:java-exec      java-exec
                                                  :jvm-args       jvm-args
                                                  :classpath      full-classpath
                                                  :scan-classpath test-output-dir
                                                  :test-args      test-args})]
          (when-not (zero? exit-code)
            (System/exit exit-code))))

      ;; Simple JUnit execution (no IntelliJ framework)
      ;; For submodules, use :test alias only (not :test-exec which has root-relative paths)
      ;; Use absolute paths since classpath is resolved from module dir but JUnit runs from root
      (let [project-root (System/getProperty "user.dir")
            abs-test-output (str project-root "/" test-output-dir)
            abs-prod-output (str project-root "/out/production/" module)
            test-classpath (testing/get-test-classpath [:test :test-exec] :project-root module-root)
            _ (when-not test-classpath
                (println "Error: Failed to resolve test classpath")
                (System/exit 1))
            full-classpath (str abs-test-output ":" abs-prod-output ":" test-classpath)
            jvm-args (testing/simple-test-jvm-args)
            exit-code (testing/run-junit-tests {:java-exec      "java"
                                                :jvm-args       jvm-args
                                                :classpath      full-classpath
                                                :scan-classpath abs-test-output
                                                :test-args      test-args})]
        (when-not (zero? exit-code)
          (System/exit exit-code))))))

(defn run-tests
  "Run tests for all modules that have test paths configured.

   Options from args:
   - :class   - Test class to run (symbol, e.g. foo.BarTest)
   - :method  - Test method to run (symbol, e.g. foo.BarTest.testSomething or foo.BarTest#testSomething)
   - :package - Test package to run (symbol, e.g. foo.bar)

   Modules with :intellij-tests? true (default) run with IntelliJ test framework.
   Modules with :intellij-tests? false run with simple JUnit.

   Compiles production code, tests, and prepares sandbox before running tests."
  [args]
  (let [modules (module-info args)
        testable-modules (filter #(or (seq (:kotlin-test-paths %))
                                      (seq (:java-test-paths %)))
                                 modules)
        sandbox-dir (or (:sandbox-dir args) "sandbox/test")]

    (when (empty? testable-modules)
      (println "No modules with test paths found")
      (System/exit 0))

    ;; Compile production code and tests for all modules
    (run! compile-module modules)
    (run! #(compile-module % true) testable-modules)

    (testing/setup-sandbox! sandbox-dir)
    (prepare-sandbox {:sandbox-dir sandbox-dir})

    (println "=== Running tests for" (count testable-modules) "modules ===")
    (println)

    (doseq [module-config testable-modules]
      (test-module module-config args))))
