(ns plugin-dev-tools.update-kotlin-test
  (:require [clojure.test :refer :all]
            [plugin-dev-tools.update-kotlin :as kotlin]
            [clojure.edn :as edn]))

(deftest test-base-key
  (testing "base-key strips $ suffix"
    (is (= 'org.jetbrains.kotlin/kotlin-stdlib
           (kotlin/base-key 'org.jetbrains.kotlin/kotlin-stdlib$sources)))
    (is (= 'org.jetbrains.kotlin/kotlin-test
           (kotlin/base-key 'org.jetbrains.kotlin/kotlin-test$jvm))))

  (testing "base-key preserves key without $"
    (is (= 'org.jetbrains.kotlin/kotlin-stdlib
           (kotlin/base-key 'org.jetbrains.kotlin/kotlin-stdlib)))
    (is (= 'some.other/library
           (kotlin/base-key 'some.other/library)))))

(deftest test-kotlin-key?
  (testing "identifies kotlin dependencies"
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-stdlib)))
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-stdlib-jdk8)))
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-test)))
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-test-junit)))
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-reflect)))
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-compiler)))
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-serialization-compiler-plugin))))

  (testing "rejects non-kotlin dependencies"
    (is (false? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-something)))
    (is (false? (kotlin/kotlin-key? 'org.jetbrains.kotlinx/kotlinx-coroutines-core)))
    (is (false? (kotlin/kotlin-key? 'com.example/library)))))

(deftest test-serialization-key?
  (testing "identifies kotlinx-serialization dependencies"
    (is (true? (kotlin/serialization-key? 'org.jetbrains.kotlinx/kotlinx-serialization-core)))
    (is (true? (kotlin/serialization-key? 'org.jetbrains.kotlinx/kotlinx-serialization-json))))

  (testing "rejects non-serialization dependencies"
    (is (false? (kotlin/serialization-key? 'org.jetbrains.kotlinx/kotlinx-coroutines-core)))
    (is (false? (kotlin/serialization-key? 'org.jetbrains.kotlin/kotlin-stdlib)))))

(deftest test-coroutines-key?
  (testing "identifies kotlinx-coroutines dependencies"
    (is (true? (kotlin/coroutines-key? 'org.jetbrains.kotlinx/kotlinx-coroutines-core)))
    (is (true? (kotlin/coroutines-key? 'org.jetbrains.kotlinx/kotlinx-coroutines-jdk8)))
    (is (true? (kotlin/coroutines-key? 'com.intellij.platform/kotlinx-coroutines-core))))

  (testing "rejects non-coroutines dependencies"
    (is (false? (kotlin/coroutines-key? 'org.jetbrains.kotlinx/kotlinx-serialization-core)))
    (is (false? (kotlin/coroutines-key? 'org.jetbrains.kotlin/kotlin-stdlib)))))

(deftest test-ksp-key?
  (testing "identifies KSP dependencies"
    (is (true? (kotlin/ksp-key? 'com.google.devtools.ksp/symbol-processing)))
    (is (true? (kotlin/ksp-key? 'com.google.devtools.ksp/symbol-processing-api))))

  (testing "rejects non-KSP dependencies"
    (is (false? (kotlin/ksp-key? 'org.jetbrains.kotlin/kotlin-stdlib)))
    (is (false? (kotlin/ksp-key? 'com.example/library)))))

(deftest test-update-deps-edn
  (testing "updates kotlin version in deps"
    (let [input "{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"1.9.0\"}}}"
          versions {:kotlin-version "2.0.0"}
          _ (spit "/tmp/test-deps.edn" input)
          _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
          result (slurp "/tmp/test-deps.edn")
          parsed (edn/read-string result)]
      (is (= "2.0.0" (get-in parsed [:deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version])))))

  (testing "updates serialization version in deps"
    (let [input "{:deps {org.jetbrains.kotlinx/kotlinx-serialization-json {:mvn/version \"1.5.0\"}}}"
          versions {:serialization-version "1.6.0"}
          _ (spit "/tmp/test-deps.edn" input)
          _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
          result (slurp "/tmp/test-deps.edn")
          parsed (edn/read-string result)]
      (is (= "1.6.0" (get-in parsed [:deps 'org.jetbrains.kotlinx/kotlinx-serialization-json :mvn/version])))))

  (testing "updates coroutines version in deps"
    (let [input "{:deps {org.jetbrains.kotlinx/kotlinx-coroutines-core {:mvn/version \"1.7.0\"}}}"
          versions {:coroutines-version "1.8.0"}
          _ (spit "/tmp/test-deps.edn" input)
          _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
          result (slurp "/tmp/test-deps.edn")
          parsed (edn/read-string result)]
      (is (= "1.8.0" (get-in parsed [:deps 'org.jetbrains.kotlinx/kotlinx-coroutines-core :mvn/version])))))

  (testing "updates KSP version in deps"
    (let [input "{:deps {com.google.devtools.ksp/symbol-processing {:mvn/version \"1.9.0-1.0.13\"}}}"
          versions {:ksp-version "2.0.0-1.0.20"}
          _ (spit "/tmp/test-deps.edn" input)
          _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
          result (slurp "/tmp/test-deps.edn")
          parsed (edn/read-string result)]
      (is (= "2.0.0-1.0.20" (get-in parsed [:deps 'com.google.devtools.ksp/symbol-processing :mvn/version])))))

  (testing "updates versions in aliases"
    (let [input "{:aliases {:build {:extra-deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"1.9.0\"}}}}}"
          versions {:kotlin-version "2.0.0"}
          _ (spit "/tmp/test-deps.edn" input)
          _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
          result (slurp "/tmp/test-deps.edn")
          parsed (edn/read-string result)]
      (is (= "2.0.0" (get-in parsed [:aliases :build :extra-deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version])))))

  (testing "preserves formatting and comments"
    (let [input "; Comment at top\n{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"1.9.0\"} ; inline comment\n        other/library {:mvn/version \"1.0.0\"}}}"
          versions {:kotlin-version "2.0.0"}
          _ (spit "/tmp/test-deps.edn" input)
          _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
          result (slurp "/tmp/test-deps.edn")]
      (is (re-find #"; Comment at top" result))
      (is (re-find #"; inline comment" result))
      (is (re-find #"2\.0\.0" result))
      (is (re-find #"1\.0\.0" result))))

  (testing "leaves non-kotlin dependencies unchanged"
    (let [input "{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"1.9.0\"}\n                other/library {:mvn/version \"1.0.0\"}}}"
          versions {:kotlin-version "2.0.0"}
          _ (spit "/tmp/test-deps.edn" input)
          _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
          result (slurp "/tmp/test-deps.edn")
          parsed (edn/read-string result)]
      (is (= "2.0.0" (get-in parsed [:deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version])))
      (is (= "1.0.0" (get-in parsed [:deps 'other/library :mvn/version])))))

  (testing "handles dependencies with $ suffix"
    (let [input "{:deps {org.jetbrains.kotlin/kotlin-stdlib$sources {:mvn/version \"1.9.0\"}}}"
          versions {:kotlin-version "2.0.0"}
          _ (spit "/tmp/test-deps.edn" input)
          _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
          result (slurp "/tmp/test-deps.edn")
          parsed (edn/read-string result)]
      (is (= "2.0.0" (get-in parsed [:deps 'org.jetbrains.kotlin/kotlin-stdlib$sources :mvn/version]))))))
