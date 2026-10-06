(ns plugin-dev-tools.managed-ide-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer :all]
            [plugin-dev-tools.build :as build]))

(deftest canonical-path-accepts-files-strings-and-nio-paths
  (let [expected (.getCanonicalPath (fs/file "."))]
    (doseq [path ["." (fs/file ".") (fs/path ".")]]
      (is (= expected (#'build/canonical-path path))))))

(deftest managed-sandbox-resolves-socket-path-on-unix
  (doseq [os ["macOS" "Linux"]
          args [{} {:project-path "/tmp/other-project"}
                {:sandbox-dir "/tmp/ide-check/sandbox"}
                {:sandbox-dir (fs/path "/tmp/ide-check/sandbox")}]]
    (is (= "/tmp/ide-check/sandbox"
           (#'build/resolve-sandbox-dir args true "/tmp/ide-check" os)))))

(deftest managed-sandbox-retains-validation
  (doseq [os ["macOS" "Linux"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                         #"must be exactly <checkout>/sandbox"
                         (#'build/resolve-sandbox-dir
                           {:sandbox-dir "/tmp/other-sandbox"} true "/tmp/ide-check" os)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                         #"avoid IntelliJ's /tmp redirection"
                         (#'build/resolve-sandbox-dir
                           {} true (str "/tmp/" (apply str (repeat 100 "x"))) os)))))
