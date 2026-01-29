(ns plugin-dev-tools.build-test
  (:require [clojure.test :refer :all]
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
