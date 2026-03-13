(ns plugin-dev-tools.run-ide-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(defn- write-script! [path content]
  (let [path (str path)]
    (spit path content)
    (fs/set-posix-file-permissions path "rwxr-xr-x")
    path))

(defn- run-script [{:keys [params-json expected-command]}]
  (let [tmp-dir (fs/create-temp-dir {:prefix "run-ide-test-"})
        bin-dir (fs/create-dirs (fs/path tmp-dir "bin"))
        work-dir (fs/create-dirs (fs/path tmp-dir "work"))
        clojure-bin (fs/path bin-dir "clojure")
        java-bin (fs/path bin-dir "java")
        launch-log (fs/path tmp-dir "launch.log")
        script-path (str (fs/absolutize "scripts/run-ide"))
        original-path (System/getenv "PATH")]
    (try
      (write-script!
        clojure-bin
        (str "#!/bin/sh\n"
             "cat <<'JSON'\n"
             (str/replace params-json "__CWD__" (str work-dir)) "\n"
             "JSON\n"))
      (write-script!
        java-bin
        (str "#!/bin/sh\n"
             "if [ \"$1\" = \"-XshowSettings:properties\" ]; then\n"
             "  echo '    java.specification.version = 21' >&2\n"
             "  echo '    java.vendor = Test Vendor' >&2\n"
             "  echo '    java.runtime.name = Test Runtime' >&2\n"
             "  exit 0\n"
             "fi\n"
             "printf '%s\n' \"$PWD|$*\" > \"" launch-log "\"\n"))
      (let [result (shell/sh "/bin/bash" script-path
                             :dir (str tmp-dir)
                             :env (assoc (into {} (System/getenv))
                                         "PATH" (str bin-dir ":" original-path)))]
        (assoc result
               :launch-log (when (fs/exists? launch-log)
                             (slurp (str launch-log)))
               :expected-command (str/replace expected-command "__CWD__" (str work-dir))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest run-ide-script-works-under-bash-3-without-mapfile
  (let [{:keys [exit out launch-log expected-command] :as result}
        (run-script {:params-json "{\"version\":1,\"cwd\":\"__CWD__\",\"javaPath\":\"java\",\"javaRequirements\":{\"major\":21,\"jbr\":false},\"mainClass\":\"com.example.Main\",\"vmArgs\":[\"-Xmx1g\",\"-Dfoo=bar\"],\"classpathEntries\":[\"/tmp/a.jar\",\"/tmp/b.jar\"],\"appArgs\":[\"arg1\",\"arg two\"]}"
                     :expected-command "__CWD__|-Xmx1g -Dfoo=bar -classpath /tmp/a.jar:/tmp/b.jar com.example.Main arg1 arg two"})]
    (is (zero? exit) (pr-str result))
    (is (str/blank? out))
    (when (zero? exit)
      (is (str/includes? launch-log expected-command)))))

(deftest run-ide-script-handles-empty-app-args-under-bash-3
  (let [{:keys [exit out launch-log expected-command] :as result}
        (run-script {:params-json "{\"version\":1,\"cwd\":\"__CWD__\",\"javaPath\":\"java\",\"javaRequirements\":{\"major\":21,\"jbr\":false},\"mainClass\":\"com.example.Main\",\"vmArgs\":[\"-Xmx1g\"],\"classpathEntries\":[\"/tmp/a.jar\"],\"appArgs\":[]}"
                     :expected-command "__CWD__|-Xmx1g -classpath /tmp/a.jar com.example.Main"})]
    (is (zero? exit) (pr-str result))
    (is (str/blank? out))
    (when (zero? exit)
      (is (str/includes? launch-log expected-command)))))
