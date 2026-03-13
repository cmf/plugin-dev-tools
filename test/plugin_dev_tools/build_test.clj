(ns plugin-dev-tools.build-test
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.test :refer :all]
            [plugin-dev-tools.build :as build]))

(defn- run-package
  [args]
  (let [compile-calls (atom 0)
        clean-calls (atom 0)
        slurp* clojure.core/slurp
        result (atom {})]
    (with-redefs [build/module-info (fn [_]
                                      [{:module "main"
                                        :main-plugin? true
                                        :plugin-directory "main-plugin"}])
                  build/clean (fn [_] (swap! clean-calls inc))
                  build/compile-module (fn [_] (swap! compile-calls inc))
                  build/sync-kotlinc-plugin (fn [] nil)
                  build/prepare-sandbox (fn [_] nil)
                  build/package-plugin (fn [_] nil)
                  clojure.core/slurp (fn [path]
                                       (if (= path "plugin.edn")
                                         "{:base-version \"1.0.0\" :platform-version \"261\"}"
                                         (slurp* path)))]
      (build/package args)
      (reset! result {:compile @compile-calls
                      :clean @clean-calls}))
    @result))

(deftest test-package-compiles-by-default
  (let [{:keys [compile clean]} (run-package {})]
    (is (= 1 clean))
    (is (= 1 compile))))

(deftest test-package-skips-compile-when-disabled
  (let [{:keys [compile clean]} (run-package {:compile false})]
    (is (= 0 clean))
    (is (= 0 compile))))

(deftest test-clean-sandbox-deletes-entire-sandbox-root
  (let [delete-calls (atom [])
        clean-sandbox-var (ns-resolve 'plugin-dev-tools.build 'clean-sandbox)]
    (is (some? clean-sandbox-var))
    (when clean-sandbox-var
      (with-redefs [clojure.tools.build.api/delete (fn [args]
                                                     (swap! delete-calls conj args))]
        (clean-sandbox-var {:sandbox-dir "/tmp/sandbox"}))
      (is (= [{:path "/tmp/sandbox"}] @delete-calls)))))

(deftest test-debug-enabled?
  (is (true? (#'build/debug-enabled? {:debug true})))
  (is (true? (#'build/debug-enabled? {:debug "true"})))
  (is (true? (#'build/debug-enabled? {:debug "yes"})))
  (is (false? (#'build/debug-enabled? {})))
  (is (false? (#'build/debug-enabled? {:debug false})))
  (is (false? (#'build/debug-enabled? {:debug "no"}))))

(deftest test-resolve-debug-port
  (with-redefs [build/find-free-port (fn [] 43123)]
    (is (nil? (#'build/resolve-debug-port {:debug false})))
    (is (= 43123 (#'build/resolve-debug-port {:debug true})))
    (is (= 5005 (#'build/resolve-debug-port {:debug true :debug-port 5005})))
    (is (= 5006 (#'build/resolve-debug-port {:debug true :debug-port "5006"})))
    (is (= 5007 (#'build/resolve-debug-port {:debug true :port 5007})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid :debug-port/:port"
                          (#'build/resolve-debug-port {:debug true :debug-port "abc"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid :debug-port/:port"
                          (#'build/resolve-debug-port {:debug true :debug-port 70000})))))

(deftest test-ide-params-preserves-existing-sandbox-state-and-prints-launch-json
  (let [compile-calls (atom 0)
        prepared (atom nil)
        sdk-dir (fs/create-temp-dir {:prefix "sdk-"})
        sandbox-dir (fs/create-temp-dir {:prefix "sandbox-"})
        marker-file (fs/path sandbox-dir "config" "recentProjects.xml")
        a-jar (fs/path sdk-dir "lib" "a.jar")
        b-jar (fs/path sdk-dir "lib" "b.jar")
        expected-sandbox (str (fs/absolutize sandbox-dir))]
    (try
      (fs/create-dirs (fs/parent marker-file))
      (spit (str marker-file) "<recentProjects />")
      (fs/create-dirs (fs/parent a-jar))
      (spit (str a-jar) "")
      (spit (str b-jar) "")
      (let [stdout (with-out-str
                     (with-redefs [build/module-info (fn [_] [{:module "main"}])
                                   build/compile-module (fn [_] (swap! compile-calls inc))
                                   build/prepare-sandbox (fn [args] (reset! prepared args))
                                   plugin-dev-tools.testing/find-intellij-sdk (fn [] (str sdk-dir))
                                   build/get-plugin-id (fn [] "com.example.plugin")
                                   plugin-dev-tools.testing/read-product-info (fn [_] {:launch []})
                                   plugin-dev-tools.testing/detect-os (fn [] "Linux")
                                   plugin-dev-tools.testing/detect-architecture (fn [] "amd64")
                                   plugin-dev-tools.testing/find-launch-config (fn [& _]
                                                                                 {:mainClass "com.intellij.idea.Main"
                                                                                  :bootClassPathJarNames ["a.jar" "b.jar"]
                                                                                  :additionalJvmArguments ["-Dfoo=$IDE_HOME"]})
                                   plugin-dev-tools.testing/find-java-exec (fn [_] "/java/bin/java")
                                   plugin-dev-tools.testing/load-vm-options (fn [_] ["-Xmx2g"])]
                       (build/ide-params {:sandbox-dir (str sandbox-dir)})))
            params (json/read-str stdout :key-fn keyword)]
        (is (= 1 @compile-calls))
        (is (= {:sandbox-dir expected-sandbox} @prepared))
        (is (fs/exists? marker-file))
        (is (= "<recentProjects />" (slurp (str marker-file))))
        (is (= 1 (:version params)))
        (is (= (str sdk-dir "/bin") (:cwd params)))
        (is (= "/java/bin/java" (:javaPath params)))
        (is (= {:major 21 :jbr true} (:javaRequirements params)))
        (is (= "com.intellij.idea.Main" (:mainClass params)))
        (is (= [(str a-jar) (str b-jar)] (:classpathEntries params)))
        (is (= [] (:appArgs params)))
        (is (some #(= "-Xmx2g" %) (:vmArgs params)))
        (is (some #(= (str "-Dfoo=" sdk-dir) %) (:vmArgs params)))
        (is (some #(= "-Dsun.awt.disablegrab=true" %) (:vmArgs params)))
        (is (some #(= (str "-Didea.config.path=" expected-sandbox "/config") %) (:vmArgs params))))
      (finally
        (fs/delete-tree sdk-dir)
        (fs/delete-tree sandbox-dir)))))

(deftest test-ide-params-includes-debug-port-when-enabled
  (let [sdk-dir (fs/create-temp-dir {:prefix "sdk-"})
        sandbox-dir (fs/create-temp-dir {:prefix "sandbox-"})
        a-jar (fs/path sdk-dir "lib" "a.jar")]
    (try
      (fs/create-dirs (fs/parent a-jar))
      (spit (str a-jar) "")
      (let [stdout (with-out-str
                     (with-redefs [build/module-info (fn [_] [{:module "main"}])
                                   build/compile-module (fn [_] nil)
                                   build/prepare-sandbox (fn [_] nil)
                                   plugin-dev-tools.testing/find-intellij-sdk (fn [] (str sdk-dir))
                                   build/get-plugin-id (fn [] "com.example.plugin")
                                   plugin-dev-tools.testing/read-product-info (fn [_] {:launch []})
                                   plugin-dev-tools.testing/detect-os (fn [] "Linux")
                                   plugin-dev-tools.testing/detect-architecture (fn [] "amd64")
                                   plugin-dev-tools.testing/find-launch-config (fn [& _]
                                                                                 {:mainClass "com.intellij.idea.Main"
                                                                                  :bootClassPathJarNames ["a.jar"]
                                                                                  :additionalJvmArguments []})
                                   plugin-dev-tools.testing/find-java-exec (fn [_] "/java/bin/java")
                                   plugin-dev-tools.testing/load-vm-options (fn [_] [])
                                   build/find-free-port (fn [] 43123)]
                       (build/ide-params {:debug true
                                          :sandbox-dir (str sandbox-dir)})))
            params (json/read-str stdout :key-fn keyword)]
        (is (= 43123 (:debugPort params)))
        (is (some #(= "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:43123" %)
                  (:vmArgs params))))
      (finally
        (fs/delete-tree sdk-dir)
        (fs/delete-tree sandbox-dir)))))
