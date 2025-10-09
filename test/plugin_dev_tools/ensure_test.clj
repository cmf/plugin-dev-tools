(ns plugin-dev-tools.ensure-test
  (:require [clojure.test :refer :all]
            [plugin-dev-tools.ensure :as ensure]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [babashka.fs :as fs])
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

(deftest test-maven-metadata-url
  (testing "constructs correct metadata URL for releases"
    (let [expected "https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml"
          actual (ensure/maven-metadata-url "releases")]
      (is (= expected actual))))

  (testing "constructs correct metadata URL for snapshots"
    (let [expected "https://www.jetbrains.com/intellij-repository/snapshots/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml"
          actual (ensure/maven-metadata-url "snapshots")]
      (is (= expected actual)))))

(deftest test-marketing-version->branch
  (testing "converts 2025.3 to 253"
    (is (= "253" (ensure/marketing-version->branch "2025.3"))))

  (testing "converts 2025.2 to 252"
    (is (= "252" (ensure/marketing-version->branch "2025.2"))))

  (testing "converts 2024.3 to 243"
    (is (= "243" (ensure/marketing-version->branch "2024.3"))))

  (testing "converts 2024.1 to 241"
    (is (= "241" (ensure/marketing-version->branch "2024.1")))))

(def sample-release-metadata-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<metadata>
  <groupId>com.jetbrains.intellij.idea</groupId>
  <artifactId>ideaIU</artifactId>
  <versioning>
    <latest>2025.2.3</latest>
    <release>2025.2.3</release>
    <versions>
      <version>2024.3</version>
      <version>2024.3.1</version>
      <version>2024.3.7</version>
      <version>2025.1</version>
      <version>2025.1.6</version>
      <version>2025.2</version>
      <version>2025.2.1</version>
      <version>2025.2.3</version>
    </versions>
  </versioning>
</metadata>")

(def sample-snapshot-metadata-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<metadata>
  <groupId>com.jetbrains.intellij.idea</groupId>
  <artifactId>ideaIU</artifactId>
  <versioning>
    <latest>253.25908.13-EAP-SNAPSHOT</latest>
    <versions>
      <version>LATEST-EAP-SNAPSHOT</version>
      <version>242-EAP-SNAPSHOT</version>
      <version>243-EAP-SNAPSHOT</version>
      <version>251-EAP-SNAPSHOT</version>
      <version>252-EAP-SNAPSHOT</version>
      <version>252.26830.24-EAP-SNAPSHOT</version>
      <version>253-EAP-SNAPSHOT</version>
      <version>253.20558.43-EAP-SNAPSHOT</version>
      <version>253.25908-EAP-CANDIDATE-SNAPSHOT</version>
      <version>253.25908.13-EAP-SNAPSHOT</version>
    </versions>
  </versioning>
</metadata>")

(deftest test-parse-maven-metadata
  (testing "parses release metadata correctly"
    (let [input-stream (java.io.ByteArrayInputStream. (.getBytes sample-release-metadata-xml))
          result (ensure/parse-maven-metadata input-stream)]
      (is (= "2025.2.3" (:latest result)))
      (is (= ["2024.3" "2024.3.1" "2024.3.7" "2025.1" "2025.1.6" "2025.2" "2025.2.1" "2025.2.3"]
             (:versions result)))))

  (testing "parses snapshot metadata correctly"
    (let [input-stream (java.io.ByteArrayInputStream. (.getBytes sample-snapshot-metadata-xml))
          result (ensure/parse-maven-metadata input-stream)]
      (is (= "253.25908.13-EAP-SNAPSHOT" (:latest result)))
      (is (some #(= "253.25908.13-EAP-SNAPSHOT" %) (:versions result)))))

  (testing "handles malformed XML gracefully"
    (let [malformed-xml "<metadata><broken>"
          input-stream (java.io.ByteArrayInputStream. (.getBytes malformed-xml))
          result (ensure/parse-maven-metadata input-stream)]
      (is (nil? (:latest result)))
      (is (= [] (:versions result))))))

(deftest test-resolve-release-version
  (testing "finds exact match"
    (let [versions ["2024.3" "2024.3.1" "2025.1" "2025.2"]
          result (ensure/resolve-release-version "2025.1" versions)]
      (is (= "2025.1" result))))

  (testing "finds latest point release"
    (let [versions ["2024.3" "2024.3.1" "2024.3.5" "2024.3.7" "2025.1"]
          result (ensure/resolve-release-version "2024.3" versions)]
      (is (= "2024.3.7" result))))

  (testing "returns version as-is if no matches"
    (let [versions ["2024.3" "2025.1"]
          result (ensure/resolve-release-version "2025.2" versions)]
      (is (= "2025.2" result)))))

(deftest test-resolve-eap-version
  (testing "finds latest snapshot for branch 253"
    (let [versions ["253-EAP-SNAPSHOT"
                    "253.20558.43-EAP-SNAPSHOT"
                    "253.25908.13-EAP-SNAPSHOT"]
          result (ensure/resolve-eap-version "2025.3" versions)]
      (is (= "253.25908.13-EAP-SNAPSHOT" result))))

  (testing "excludes CANDIDATE snapshots"
    (let [versions ["253.20558.43-EAP-SNAPSHOT"
                    "253.25908-EAP-CANDIDATE-SNAPSHOT"
                    "253.25908.13-EAP-SNAPSHOT"]
          result (ensure/resolve-eap-version "2025.3" versions)]
      (is (= "253.25908.13-EAP-SNAPSHOT" result))))

  (testing "falls back to simple format if no specific builds"
    (let [versions ["253-EAP-SNAPSHOT" "252-EAP-SNAPSHOT"]
          result (ensure/resolve-eap-version "2025.3" versions)]
      (is (= "253-EAP-SNAPSHOT" result))))

  (testing "returns simple format if no matches at all"
    (let [versions ["252-EAP-SNAPSHOT" "251-EAP-SNAPSHOT"]
          result (ensure/resolve-eap-version "2025.3" versions)]
      (is (= "253-EAP-SNAPSHOT" result)))))

;; Plugin-related tests

(deftest test-plugin-maven-url
  (testing "constructs correct URL without channel"
    (let [expected "https://plugins.jetbrains.com/maven/com/jetbrains/plugins/org.intellij.plugins.markdown/1.0.0/org.intellij.plugins.markdown-1.0.0.zip"
          actual (ensure/plugin-maven-url "org.intellij.plugins.markdown" "1.0.0" nil)]
      (is (= expected actual))))

  (testing "constructs correct URL with channel"
    (let [expected "https://plugins.jetbrains.com/maven/eap/com/jetbrains/plugins/kotlin/1.9.0/kotlin-1.9.0.zip"
          actual (ensure/plugin-maven-url "kotlin" "1.9.0" "eap")]
      (is (= expected actual)))))

(deftest test-plugin-dir
  (testing "returns correct plugin directory path"
    (let [expected (io/file (ensure/sdks-dir) "plugins" "kotlin" "1.9.0")
          actual (ensure/plugin-dir "kotlin" "1.9.0")]
      (is (= expected actual)))))

(deftest test-plugin-zipfile
  (testing "returns correct plugin zipfile path"
    (let [expected (io/file (ensure/sdks-dir) "plugins" "kotlin" "1.9.0" "kotlin-1.9.0.zip")
          actual (ensure/plugin-zipfile "kotlin" "1.9.0")]
      (is (= expected actual)))))

(deftest test-update-deps-edn-with-marketplace-plugins
  (testing "updates marketplace-plugin dependency paths"
    (let [version "2023.1.1"
          plugins [{:id "kotlin" :version "1.9.0"}]
          input "{:aliases {:sdk {:extra-deps {marketplace-plugin/kotlin {:local/root \"/old/path/kotlin\"}}}}}"
          _ (spit "/tmp/test-marketplace-plugin-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-marketplace-plugin-deps.edn" version plugins)
          result (slurp "/tmp/test-marketplace-plugin-deps.edn")
          parsed (edn/read-string result)
          expected-path (.getAbsolutePath (ensure/plugin-dir "kotlin" "1.9.0"))]
      (is (= expected-path (get-in parsed [:aliases :sdk :extra-deps 'marketplace-plugin/kotlin :local/root])))))

  (testing "leaves marketplace-plugin unchanged if plugin not in list"
    (let [version "2023.1.1"
          plugins []
          input "{:aliases {:sdk {:extra-deps {marketplace-plugin/kotlin {:local/root \"/old/path/kotlin\"}}}}}"
          _ (spit "/tmp/test-marketplace-plugin-missing.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-marketplace-plugin-missing.edn" version plugins)
          result (slurp "/tmp/test-marketplace-plugin-missing.edn")
          parsed (edn/read-string result)]
      (is (= "/old/path/kotlin" (get-in parsed [:aliases :sdk :extra-deps 'marketplace-plugin/kotlin :local/root])))))

  (testing "updates both intellij and marketplace-plugin dependencies"
    (let [version "2023.1.1"
          plugins [{:id "kotlin" :version "1.9.0"}]
          input "{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/sdk\"}
                                                marketplace-plugin/kotlin {:local/root \"/old/kotlin\"}}}}}"
          _ (spit "/tmp/test-mixed-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-mixed-deps.edn" version plugins)
          result (slurp "/tmp/test-mixed-deps.edn")
          parsed (edn/read-string result)
          expected-sdk-path (.getAbsolutePath (io/file (ensure/sdks-dir) version))
          expected-plugin-path (.getAbsolutePath (ensure/plugin-dir "kotlin" "1.9.0"))]
      (is (= expected-sdk-path (get-in parsed [:aliases :sdk :extra-deps 'intellij/sdk :local/root])))
      (is (= expected-plugin-path (get-in parsed [:aliases :sdk :extra-deps 'marketplace-plugin/kotlin :local/root]))))))

(deftest test-process-plugin-with-nested-lib-directory
  (testing "handles plugin with lib/ in subdirectory using fs/file with Path objects"
    ;; This test verifies the fix for the UnixPath coercion bug
    ;; Create a mock plugin directory structure with lib/ in a subdirectory
    (let [test-plugin-dir (io/file "/tmp/test-plugin-nested")
          plugin-subdir (io/file test-plugin-dir "plugin-name")
          lib-dir (io/file plugin-subdir "lib")]

      ;; Setup: Create directory structure
      (fs/create-dirs lib-dir)
      (spit (io/file lib-dir "test.jar") "mock jar content")

      (try
        ;; Test the logic that was buggy: fs/list-dir returns Path objects
        ;; and we need to use fs/file (not io/file) to construct paths from them
        (let [subdirs (filter fs/directory? (fs/list-dir test-plugin-dir))
              ;; This would fail with io/file before the fix
              found-lib-dir (first (filter #(fs/exists? (fs/file % "lib")) subdirs))]

          ;; Verify we found the subdirectory with lib/
          (is (not (nil? found-lib-dir)))
          (is (fs/exists? (fs/file found-lib-dir "lib")))

          ;; Verify we can write deps.edn using fs/file with Path object
          (let [deps-edn-file (fs/file found-lib-dir "deps.edn")
                deps-content {:paths ["lib/test.jar"]}]
            (spit deps-edn-file (pr-str deps-content))
            (is (fs/exists? deps-edn-file))
            (is (= deps-content (edn/read-string (slurp deps-edn-file))))))

        (finally
          ;; Cleanup
          (fs/delete-tree test-plugin-dir))))))
