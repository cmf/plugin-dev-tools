(ns plugin-dev-tools.ensure-test
  (:require [clojure.test :refer :all]
            [plugin-dev-tools.ensure :as ensure]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io File)))

(deftest test-sdks-dir
  (testing "returns correct SDK directory path"
    (let [expected (io/file (System/getProperty "user.home") ".sdks")
          actual (ensure/sdks-dir)]
      (is (= expected actual)))))

(deftest test-zipfile
  (testing "returns correct zipfile path"
    (let [version "253.20558.43-EAP-SNAPSHOT"
          expected (io/file (ensure/sdks-dir) "ideaIU-253.20558.43-EAP-SNAPSHOT.zip")
          actual (ensure/zipfile version)]
      (is (= expected actual)))))

(deftest test-sources-file
  (testing "returns correct sources file path"
    (let [version "253.20558.43-EAP-SNAPSHOT"
          expected (io/file (ensure/sdks-dir) "ideaIC-253.20558.43-EAP-SNAPSHOT-sources.jar")
          actual (ensure/sources-file version)]
      (is (= expected actual)))))

(deftest test-sdk-url
  (testing "constructs correct SDK URL for releases"
    (let [version "2023.1.1"
          expected "https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIU/2023.1.1/ideaIU-2023.1.1.zip"
          actual (ensure/sdk-url "releases" version)]
      (is (= expected actual))))

  (testing "constructs correct SDK URL for snapshots"
    (let [version "253.20558.43-EAP-SNAPSHOT"
          expected "https://www.jetbrains.com/intellij-repository/snapshots/com/jetbrains/intellij/idea/ideaIU/253.20558.43-EAP-SNAPSHOT/ideaIU-253.20558.43-EAP-SNAPSHOT.zip"
          actual (ensure/sdk-url "snapshots" version)]
      (is (= expected actual)))))

(deftest test-update-deps-edn
  (testing "updates intellij dependency paths"
    (let [version "2023.1.1"
          input "{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/path/2023.1.0\"}}}}}"
          _ (spit "/tmp/test-ensure-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-ensure-deps.edn" version)
          result (slurp "/tmp/test-ensure-deps.edn")
          parsed (edn/read-string result)
          expected-path (.getAbsolutePath (io/file (ensure/sdks-dir) version))]
      (is (= expected-path (get-in parsed [:aliases :sdk :extra-deps 'intellij/sdk :local/root])))))

  (testing "updates intellij sources dependency paths"
    (let [version "2023.1.1"
          input "{:aliases {:sdk {:extra-deps {intellij/sdk$sources {:local/root \"/old/path/ideaIC-2023.1.0-sources.jar\"}}}}}"
          _ (spit "/tmp/test-ensure-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-ensure-deps.edn" version)
          result (slurp "/tmp/test-ensure-deps.edn")
          parsed (edn/read-string result)
          expected-path (.getAbsolutePath (ensure/sources-file version))]
      (is (= expected-path (get-in parsed [:aliases :sdk :extra-deps 'intellij/sdk$sources :local/root])))))

  (testing "updates plugin dependency paths"
    (let [version "2023.1.1"
          input "{:aliases {:sdk {:extra-deps {plugin/kotlin {:local/root \"/old/path/2023.1.0/plugins/kotlin\"}}}}}"
          _ (spit "/tmp/test-ensure-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-ensure-deps.edn" version)
          result (slurp "/tmp/test-ensure-deps.edn")
          parsed (edn/read-string result)
          expected-path (.getAbsolutePath (io/file (ensure/sdks-dir) version "plugins" "kotlin"))]
      (is (= expected-path (get-in parsed [:aliases :sdk :extra-deps 'plugin/kotlin :local/root])))))

  (testing "updates both :sdk and :ide aliases"
    (let [version "2023.1.1"
          input "{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/path/2023.1.0\"}}}
                            :ide {:extra-deps {intellij/ide {:local/root \"/old/path/2023.1.0\"}}}}}"
          _ (spit "/tmp/test-ensure-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-ensure-deps.edn" version)
          result (slurp "/tmp/test-ensure-deps.edn")
          parsed (edn/read-string result)
          expected-path (.getAbsolutePath (io/file (ensure/sdks-dir) version))]
      (is (= expected-path (get-in parsed [:aliases :sdk :extra-deps 'intellij/sdk :local/root])))
      (is (= expected-path (get-in parsed [:aliases :ide :extra-deps 'intellij/ide :local/root])))))

  (testing "preserves formatting"
    (let [version "2023.1.1"
          input "; Comment\n{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/path\"}}}}}"
          _ (spit "/tmp/test-ensure-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-ensure-deps.edn" version)
          result (slurp "/tmp/test-ensure-deps.edn")]
      (is (re-find #"; Comment" result))))

  (testing "leaves non-intellij/plugin dependencies unchanged"
    (let [version "2023.1.1"
          input "{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/path\"}\n                                          other/library {:mvn/version \"1.0.0\"}}}}}"
          _ (spit "/tmp/test-ensure-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-ensure-deps.edn" version)
          result (slurp "/tmp/test-ensure-deps.edn")
          parsed (edn/read-string result)]
      (is (= "1.0.0" (get-in parsed [:aliases :sdk :extra-deps 'other/library :mvn/version]))))))
