(ns plugin-dev-tools.core-test
  (:require [clojure.test :refer :all]
            [plugin-dev-tools.core :as core]
            [plugin-dev-tools.update-kotlin :as kotlin]
            [plugin-dev-tools.ensure :as ensure]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [babashka.fs :as fs]))

(deftest test-ensure-kotlin-integration
  (testing "ensure-kotlin integration with update-deps-edn"
    ; This test verifies the integration by directly calling update-deps-edn
    ; instead of the full ensure-kotlin flow, since ensure-kotlin expects
    ; plugin.edn to be in the current directory
    (let [module1 "/tmp/test-module1-deps.edn"
          module1-content "{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"2.0.0\"}}}"
          versions {:kotlin-version "2.1.0"
                    :serialization-version "1.7.3"
                    :coroutines-version "1.9.0"
                    :ksp-version "2.1.0-1.0.29"}]

      (spit module1 module1-content)

      ; Call the underlying function that ensure-kotlin uses
      (kotlin/update-deps-edn module1 versions)

      ; Verify module1 was updated
      (let [result (edn/read-string (slurp module1))]
        (is (= "2.1.0" (get-in result [:deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version]))))

      (io/delete-file module1))))

(deftest test-ensure-kotlin-with-aliases
  (testing "update-deps-edn updates versions in aliases"
    (let [module1 "/tmp/test-module-aliases-deps.edn"
          module-content "{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"2.0.0\"}}
                           :aliases {:build {:extra-deps {org.jetbrains.kotlinx/kotlinx-serialization-json {:mvn/version \"1.6.0\"}}}}}"
          versions {:kotlin-version "2.1.0"
                    :serialization-version "1.7.3"}]

      (spit module1 module-content)

      (kotlin/update-deps-edn module1 versions)

      ; Verify both :deps and :aliases were updated
      (let [result (edn/read-string (slurp module1))]
        (is (= "2.1.0" (get-in result [:deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version])))
        (is (= "1.7.3" (get-in result [:aliases :build :extra-deps 'org.jetbrains.kotlinx/kotlinx-serialization-json :mvn/version]))))

      (io/delete-file module1))))

(deftest test-ensure-kotlin-error-handling
  (testing "ensure-kotlin handles missing plugin.edn gracefully"
    ; This test just verifies that ensure-kotlin doesn't throw when plugin.edn is missing
    ; We expect it to print an error but not crash
    ; Note: This will print an error message during test execution, which is expected
    (is (nil? (core/ensure-kotlin [])))))

(deftest test-ensure-sdk-integration
  (testing "update-deps-edn updates SDK paths correctly"
    ; This test verifies the integration by directly calling update-deps-edn
    ; from the ensure namespace
    (let [module1 "/tmp/test-sdk-deps.edn"
          version "2023.1.1"
          module-content "{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/path/2023.1.0\"}}}}}"]

      (spit module1 module-content)

      (ensure/update-deps-edn module1 version)

      ; Verify the SDK path was updated
      (let [result (edn/read-string (slurp module1))
            expected-path (.getAbsolutePath (io/file (ensure/sdks-dir) version))]
        (is (= expected-path (get-in result [:aliases :sdk :extra-deps 'intellij/sdk :local/root]))))

      (io/delete-file module1))))

(deftest test-ensure-sdk-error-handling
  (testing "ensure-sdk handles missing plugin.edn gracefully"
    ; This test just verifies that ensure-sdk doesn't throw when plugin.edn is missing
    ; We expect it to print an error but not crash
    ; Note: This will print an error message during test execution, which is expected
    (is (nil? (core/ensure-sdk [])))))
