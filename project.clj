(defproject backup-droid/backup-droid "0.1.0-SNAPSHOT"
  :description "Backs up files to a remote storage"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :global-vars {*warn-on-reflection* true}

  :source-paths ["src/clojure" "src"]
  :java-source-paths ["src/java"]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :plugins [[lein-droid "0.4.3"]]

  :dependencies [[org.clojure-android/clojure "1.7.0-r4"]
                 [neko/neko "4.0.0-alpha5"]
                 [org.clojure-android/data.json "0.2.6-SNAPSHOT"]
                 [clj-http-lite "0.3.0"]]
  :profiles {:default [:dev]

             :dev
             [:android-common :android-user
              {:dependencies [[org.clojure/tools.nrepl "0.2.12"]]
               :target-path "target/debug"
               :android {:aot :all-with-unused
                         :rename-manifest-package "backup.droid.debug"
                         :manifest-options {:app-name "backup-droid (debug)"}}}]
             :release
             [:android-common
              {:target-path "target/release"
               :android
               {
                :keystore-path "/home/julio/android-apps.keystore"
                :key-alias "generic"
                ;;:sigalg "RSA"
                :ignore-log-priority [:debug :verbose]
                :aot :all
                :build-type :release}}]}

  :android {;; Specify the path to the Android SDK directory.
            :sdk-path "/opt/android-sdk-linux/"

            ;; Try increasing this value if dexer fails with
            ;; OutOfMemoryException. Set the value according to your
            ;; available RAM.
            :dex-opts ["-JXmx4096M" "--incremental"]

            :target-version "15"
            :aot-exclude-ns ["clojure.parallel" "clojure.core.reducers"
                             "cider.nrepl" "cider-nrepl.plugin"
                             "cider.nrepl.middleware.util.java.parser"
                             #"cljs-tooling\..+"]})
