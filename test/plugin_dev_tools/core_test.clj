(ns plugin-dev-tools.core-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [plugin-dev-tools.core :as core]
            [plugin-dev-tools.ensure :as ensure]
            [plugin-dev-tools.update-kotlin :as kotlin]))

(defmacro with-temp-project
  [[project-dir sdks-cache sdks-link] & body]
  `(let [~project-dir (fs/create-temp-dir {:prefix "plugin-dev-tools-core-test"})
         ~sdks-cache (fs/file ~project-dir ".sdks")
         ~sdks-link (fs/file ~project-dir "sdks")]
     (try
       (fs/create-dirs ~sdks-cache)
       ~@body
       (finally
         (fs/delete-tree ~project-dir)))))

(deftest test-ensure-kotlin-integration
  (testing "ensure-kotlin integration with update-deps-edn"
    ;; This test verifies the integration by directly calling update-deps-edn
    ;; instead of the full ensure-kotlin flow, since ensure-kotlin expects
    ;; plugin.edn to be in the current directory.
    (let [module1 "/tmp/test-module1-deps.edn"
          module1-content "{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"2.0.0\"}}}"
          versions {:kotlin-version "2.1.0"
                    :serialization-version "1.7.3"
                    :coroutines-version "1.9.0"
                    :ksp-version "2.1.0-1.0.29"}]

      (spit module1 module1-content)

      ;; Call the underlying function that ensure-kotlin uses
      (kotlin/update-deps-edn module1 versions)

      ;; Verify module1 was updated
      (let [result (edn/read-string (slurp module1))]
        (is (= "2.1.0" (get-in result [:deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version]))))

      (io/delete-file module1))))

(deftest test-ensure-kotlin-with-aliases
  (testing "update-deps-edn updates versions in aliases"
    (let [module1 "/tmp/test-module-aliases-deps.edn"
          module-content "{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"2.0.0\"}}\n                           :aliases {:build {:extra-deps {org.jetbrains.kotlinx/kotlinx-serialization-json {:mvn/version \"1.6.0\"}}}}}"
          versions {:kotlin-version "2.1.0"
                    :serialization-version "1.7.3"}]

      (spit module1 module-content)

      (kotlin/update-deps-edn module1 versions)

      ;; Verify both :deps and :aliases were updated
      (let [result (edn/read-string (slurp module1))]
        (is (= "2.1.0" (get-in result [:deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version])))
        (is (= "1.7.3" (get-in result [:aliases :build :extra-deps 'org.jetbrains.kotlinx/kotlinx-serialization-json :mvn/version]))))

      (io/delete-file module1))))

(deftest test-ensure-kotlin-error-handling
  (testing "ensure-kotlin handles missing plugin.edn gracefully"
    ;; This test just verifies that ensure-kotlin doesn't throw when plugin.edn is missing
    ;; We expect it to print an error but not crash
    ;; Note: This will print an error message during test execution, which is expected
    (is (nil? (core/ensure-kotlin [])))))

(deftest test-ensure-sdk-integration
  (with-temp-project [project-dir sdks-cache sdks-link]
    (testing "update-deps-edn updates SDK paths correctly (relative)"
      ;; This test verifies the integration by directly calling update-deps-edn
      ;; from the ensure namespace.
      (let [version "2023.1.1"
            deps-file (fs/file project-dir "deps.edn")
            module-content "{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/path/2023.1.0\"}}}}}"
            _ (spit deps-file module-content)
            _ (ensure/ensure-project-sdks-symlink! sdks-link sdks-cache)
            _ (with-redefs [ensure/project-sdks-link (fn [] (io/file (str sdks-link)))]
                (ensure/update-deps-edn (str deps-file) version))
            result (edn/read-string (slurp deps-file))]
        (is (= (str "sdks/" version)
               (get-in result [:aliases :sdk :extra-deps 'intellij/sdk :local/root])))))))

(deftest test-ensure-sdk-error-handling
  (testing "ensure-sdk handles missing plugin.edn gracefully"
    ;; This test just verifies that ensure-sdk doesn't throw when plugin.edn is missing
    ;; We expect it to print an error but not crash
    ;; Note: This will print an error message during test execution, which is expected
    (is (nil? (core/ensure-sdk [])))))

(deftest test-ensure-sdk-with-marketplace-plugins
  (with-temp-project [project-dir sdks-cache sdks-link]
    (testing "update-deps-edn updates both SDK and marketplace plugin paths (relative)"
      ;; This test verifies the integration with marketplace plugins.
      (let [version "2023.1.1"
            deps-file (fs/file project-dir "deps.edn")
            plugins [{:id "kotlin" :version "1.9.0"}
                     {:id "org.intellij.plugins.markdown" :version "1.0.0"}]
            module-content "{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/sdk\"}\n                                                         marketplace-plugin/kotlin {:local/root \"/old/kotlin\"}\n                                                         marketplace-plugin/org.intellij.plugins.markdown {:local/root \"/old/markdown\"}}}}}"
            _ (spit deps-file module-content)
            _ (ensure/ensure-project-sdks-symlink! sdks-link sdks-cache)
            _ (with-redefs [ensure/project-sdks-link (fn [] (io/file (str sdks-link)))]
                (ensure/update-deps-edn (str deps-file) version plugins))
            result (edn/read-string (slurp deps-file))]
        (is (= (str "sdks/" version)
               (get-in result [:aliases :sdk :extra-deps 'intellij/sdk :local/root])))
        (is (= "sdks/plugins/kotlin/1.9.0"
               (get-in result [:aliases :sdk :extra-deps 'marketplace-plugin/kotlin :local/root])))
        (is (= "sdks/plugins/org.intellij.plugins.markdown/1.0.0"
               (get-in result [:aliases :sdk :extra-deps 'marketplace-plugin/org.intellij.plugins.markdown :local/root])))))))
