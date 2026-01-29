(ns plugin-dev-tools.ensure-test
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [plugin-dev-tools.ensure :as ensure]
            [plugin-dev-tools.testing :as testing])
  (:import (java.util.jar JarEntry JarOutputStream)))

(defmacro with-temp-dir
  "Create a temporary directory and ensure it is deleted after the body runs."
  [[binding] & body]
  `(let [~binding (fs/create-temp-dir {:prefix "plugin-dev-tools-ensure-test"})]
     (try
       ~@body
       (finally
         (fs/delete-tree ~binding)))))

(defn- write-jar!
  "Create a jar file at jar-path with entries map of path -> content string."
  [jar-path entries]
  (fs/create-dirs (fs/parent jar-path))
  (with-open [jar (JarOutputStream. (io/output-stream jar-path))]
    (doseq [[entry-path content] entries]
      (.putNextEntry jar (JarEntry. entry-path))
      (.write jar (.getBytes content "UTF-8"))
      (.closeEntry jar))))

(defn- module-xml
  [name resource-path]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<module name=\"" name "\">\n"
       "  <resources>\n"
       "    <resource-root path=\"" resource-path "\"/>\n"
       "  </resources>\n"
       "</module>\n"))

(defn- write-product-info!
  [sdk-dir]
  (let [os (testing/detect-os)
        product-info {:launch [{:os os
                                :bootClassPathJarNames ["intellij.platform.ide.impl.jar"
                                                        "lib.jar"]}]
                      :layout [{:name "com.intellij"
                                :classPath ["lib/intellij.platform.ide.impl.jar"]}]}]
    (spit (fs/file sdk-dir "product-info.json") (json/write-str product-info))))

(defn- create-sample-sdk! [sdk-dir]
  (fs/create-dirs (fs/file sdk-dir "lib"))
  (fs/create-dirs (fs/file sdk-dir "plugins" "test-plugin" "lib"))
  (spit (fs/file sdk-dir "lib" "intellij.platform.ide.impl.jar") "")
  (spit (fs/file sdk-dir "lib" "lib.jar") "")
  (spit (fs/file sdk-dir "lib" "not-in-classpath.jar") "")
  (spit (fs/file sdk-dir "plugins" "test-plugin" "lib" "vcs-git.jar") "")
  (write-product-info! sdk-dir)
  (write-jar! (fs/file sdk-dir "modules" "module-descriptors.jar")
              {"intellij.platform.ide.impl.xml"
               (module-xml "intellij.platform.ide.impl" "../lib/intellij.platform.ide.impl.jar")
               "intellij.vcs.git.xml"
               (module-xml "intellij.vcs.git" "../plugins/test-plugin/lib/vcs-git.jar")
               "lib.Java Compatibility.xml"
               (module-xml "lib.Java Compatibility" "../lib/lib.jar")
               "intellij.platform.notPresent.xml"
               (module-xml "intellij.platform.notPresent" "../lib/not-in-classpath.jar")}))

(deftest test-test-framework-exclusions-from-module-descriptors
  (with-temp-dir [sdk-dir]
    (create-sample-sdk! sdk-dir)
    (let [exclusions (set (ensure/test-framework-exclusions sdk-dir))]
      (is (contains? exclusions 'com.jetbrains.intellij.platform/ide-impl))
      (is (contains? exclusions 'com.jetbrains.intellij.vcs/git))
      (is (contains? exclusions 'com.jetbrains.lib.Java/java-compatibility))
      (is (contains? exclusions 'junit/junit))
      (is (not (contains? exclusions 'com.jetbrains.intellij.platform/not-present))))))

(deftest test-test-framework-exclusions-fallback
  (with-temp-dir [sdk-dir]
    (write-product-info! sdk-dir)
    (let [exclusions (set (ensure/test-framework-exclusions sdk-dir))]
      (is (contains? exclusions 'com.jetbrains.intellij.platform/boot))
      (is (contains? exclusions 'org.jetbrains.kotlin/kotlin-stdlib)))))

(deftest test-update-deps-edn-updates-test-framework-path
  (with-temp-dir [project-dir]
    (let [deps-file (fs/file project-dir "deps.edn")
          sdks-link (fs/file project-dir "sdks")
          module-content "{:aliases {:sdk {:extra-deps {intellij/test-framework {:local/root \"/old/path\"}}}}}"
          _ (spit deps-file module-content)
          _ (with-redefs [ensure/project-sdks-link (fn [] sdks-link)]
              (ensure/update-deps-edn (str deps-file) "261.17801.55-EAP-SNAPSHOT" [] :clear))
          result (edn/read-string (slurp deps-file))]
      (is (= "sdks/261.17801.55-EAP-SNAPSHOT/test-framework"
             (get-in result [:aliases :sdk :extra-deps 'intellij/test-framework :local/root]))))))

(deftest test-test-framework-deps-created-for-2026-1
  (with-temp-dir [sdk-dir]
    (create-sample-sdk! sdk-dir)
    (spit (fs/file sdk-dir "product-info.json")
          (json/write-str {:version "2026.1"
                           :launch [{:os (testing/detect-os)
                                     :bootClassPathJarNames ["intellij.platform.ide.impl.jar"
                                                             "lib.jar"]}]
                           :layout [{:name "com.intellij"
                                     :classPath ["lib/intellij.platform.ide.impl.jar"]}]}))
    (ensure/maybe-write-test-framework-deps! sdk-dir "261.17801.55-EAP-SNAPSHOT")
    (let [deps-file (fs/file sdk-dir "test-framework" "deps.edn")
          result (edn/read-string (slurp deps-file))]
      (is (= "261.17801.55-EAP-SNAPSHOT"
             (get-in result [:deps 'com.jetbrains.intellij.platform/test-framework :mvn/version])))
      (is (contains? (set (get-in result [:deps 'com.jetbrains.intellij.platform/test-framework :exclusions]))
                     'com.jetbrains.intellij.platform/ide-impl))
      (is (nil? (:mvn/repos result))))))

(deftest test-test-framework-deps-not-created-before-2026-1
  (with-temp-dir [sdk-dir]
    (create-sample-sdk! sdk-dir)
    (spit (fs/file sdk-dir "product-info.json")
          (json/write-str {:version "2025.3"
                           :launch [{:os (testing/detect-os)
                                     :bootClassPathJarNames ["intellij.platform.ide.impl.jar"
                                                             "lib.jar"]}]
                           :layout [{:name "com.intellij"
                                     :classPath ["lib/intellij.platform.ide.impl.jar"]}]}))
    (ensure/maybe-write-test-framework-deps! sdk-dir "2025.3.1.1")
    (is (not (fs/exists? (fs/file sdk-dir "test-framework" "deps.edn"))))))
