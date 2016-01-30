(ns backup.droid.main
  (:require [neko.activity :refer [defactivity set-content-view! simple-fragment]]
            [neko.debug :refer [*a]]
            [neko.notify :refer [toast fire notification]]
            [neko.resource :as res]
            [neko.find-view :refer [find-view find-views]]
            [neko.threading :refer [on-ui on-ui-thread?]]
            [neko.ui.adapters :refer [ref-adapter]]
            [neko.data.shared-prefs :refer [defpreferences]]
            [neko.ui :refer [config]]
            [neko.ui.menu :refer [make-menu]]
            [neko.listeners.view :refer [on-create-context-menu]]
            [clojure.data.json :as json]
            [clj-http.lite.client :as http]
            [neko.log :as log]
            [neko.ui.mapping :refer [defelement]]
            [backup.droid.common :refer [get-value set-value]]
            [neko.intent :refer [intent]]
            [clojure.string :as s])
  (:import [android.widget EditText TextView ListView AdapterView LinearLayout]
           (android.os Environment Handler) (java.io File)
           java.util.HashMap
           android.util.Base64
           [android.content Intent Context BroadcastReceiver IntentFilter]
           [android.net.wifi WifiManager]
           (android.view View Menu MenuItem Gravity)
           (android.app Activity Fragment FragmentTransaction IntentService)
           neko.App))

;; We execute this function to import all subclasses of R class. This gives us
;; access to all application resources.
(res/import-all)

(declare edit-profile-fragment)
(declare list-profiles-fragment)
(declare run-profile-fragment)
(declare save-profile)
(declare sync-files)

(defelement :radio-group :classname android.widget.RadioGroup :inherits :linear-layout)
(defelement :radio-button :classname android.widget.RadioButton :inherits :button)

(defpreferences prefs "default-prefs")
(def profiles (atom (if-let [p (:profiles @prefs)]
                      (json/read-str p) {})))
(gen-class
 :name backup.droid.AutoBackupService
 :extends android.app.IntentService
 :prefix "service-"
 :init "init"
 :state "state"
 :constructors {[] [String]})

(gen-class
 :name backup.droid.MainReceiver
 :extends android.content.BroadcastReceiver
 :prefix "receiver-")

(defn receiver-onReceive [this ^Context ctx ^Intent i]
  (condp = (.getAction i)
    Intent/ACTION_BOOT_COMPLETED
    (when (some #(= "Automatic" (% "mode")) (vals @profiles))
      (log/i "handling boot completed...")
      (.startService App/instance (intent App/instance '.AutoBackupService {:action "register"})))
    WifiManager/NETWORK_STATE_CHANGED_ACTION
    (when (-> i (.getParcelableExtra WifiManager/EXTRA_NETWORK_INFO) .isConnected)
      (.startService ctx (intent ctx '.AutoBackupService {:action "sync"})))
    (log/w "no handler for intent:" i)))

;; (proxy [BroadcastReceiver] []
;;   (onReceive [^Context ctx ^Intent i]
;;     (log/d "intent received:" i " profiles:" @profiles)
;;     (if (-> i (.getParcelableExtra WifiManager/EXTRA_NETWORK_INFO) .isConnected)
;;       (.startService ctx (intent ctx '.AutoBackupService {:action "sync"})))))
(def bcast-receiver
  (delay (backup.droid.MainReceiver.)))

(defn service-init[]
  (let [state (atom {})]
    [["auto-backup-service"] state]))

(defn- notify-backup [{:keys[status count]} name]
  (when (> count 0)
    (fire :backup-notify
          (notification :ticker-text "New files backed up"
                        :content-title (str "backup profile " name)
                        :content-text (str count " files were backed up")
                        :action [:activity "backup.droid.MainActivity"]))))

(defn service-onHandleIntent [this ^Intent i]
  (log/d "service handling intent " i)
  (case (-> i .getExtras (.getString "action"))
    "register"
    (.registerReceiver
     App/instance @bcast-receiver
     (doto (IntentFilter. WifiManager/NETWORK_STATE_CHANGED_ACTION)
       (.addAction Intent/ACTION_CAMERA_BUTTON)) nil (Handler.))
    "sync"
    (doseq [p (vals @profiles) :when (= (p "mode") "Automatic")]
      (sync-files p (fn [& x] (log/i (s/join x))) #(notify-backup % (p "name"))))
    (log/w "intent not handled:" i)))

(add-watch profiles :watch
           (fn[k r o n]
             (swap! prefs assoc :profiles (json/write-str n))
             (case (for [p [o n]] (some #(= (% "mode") "Automatic") (vals p)))
               [nil true] (.startService App/instance (intent App/instance '.AutoBackupService {:action "register"}))
               [true nil] (.stopService App/instance (intent App/instance  '.AutoBackupService {}))
               nil)))

(def show!
  (let [current (atom nil)]
    (fn[^Fragment fragment]
      (let [fm (.getFragmentManager ^Activity (*a))
            ft (.beginTransaction fm)]
        (if @current
          (.hide ft @current))
        (if (.isAdded fragment)
          (.show ft fragment)
          (.add ft ^int (neko.-utils/int-id ::main-container) fragment))
        (.commit ft)
        (reset! current fragment)))))

(defn external-media-available? []
  (let [state (Environment/getExternalStorageState)]
    (or (= state Environment/MEDIA_MOUNTED) (= state Environment/MEDIA_MOUNTED_READ_ONLY))))

(defn sync-files [{:strs[backup-server remote-dir backup-dir]} logger done]
  (logger "syncing with server:" backup-server ", remote-dir:" remote-dir ", local-dir:" backup-dir "\n")
  (let [files-info (map (fn[^File f] {:path (.getName f)
                                      :tstamp (.lastModified f)
                                      :size (.length f)}) (-> ^String backup-dir File. .listFiles))
        result (atom nil)]
    (future
      (try
        (let [{:keys [status body]} (http/post (str backup-server "/sync/" remote-dir)
                                               {:headers {"Content-Type" "application/json"}
                                                :body (json/write-str files-info)})]
          (if (not= status 200)
            (logger "failed sync req:" status "," body)
            (let [fs (json/read-str body)]
              (logger (count fs) "files require backup.")
              (doseq [fname fs :let [f (File. (str backup-dir "/" fname))]]
                (logger "sending " fname)
                (let [{:keys[status body]}
                      (http/put (str backup-server "/file/" remote-dir "/" fname)
                                {:headers {"Content-Type" "application/octet-stream"}
                                 :query-params {"tstamp" (str (.lastModified f))}
                                 :body f
                                 :chunk-size 0})]
                  (if (not= status 200)
                    (logger "failed sending " fname ", error:" status "\n" body))))
              (reset! result {:status :success :count (count fs)})
              (logger "\nBackup complete."))))
        (catch Exception e
          (log/e "sync failure:" :exception e)
          (logger "Failed sending files:" e)
          (reset! result {:status :failure :error e}))
        (finally (done @result))))))

(defn run-backup [name]
  (let [_ (show! @run-profile-fragment)
        [^TextView run-log ^View close-btn] (find-views ^Fragment @run-profile-fragment ::run-log ::close-btn)
        logger (fn[& more] (on-ui (doto run-log
                                    (.append (clojure.string/join " " more))
                                    (.append "\n"))))
        done (fn[_] (on-ui (.setVisibility close-btn View/VISIBLE)))]
    (config run-log :text "")
    (logger "Running backup profile:" name "...")
    (try
      (sync-files (get @profiles name) logger done)
      (catch Exception e
        (log/e "sync failure:" :exception e)
        (logger "failed backup due to " e)))))

(defn value [^View view]
  (log/d "getting value from view " view)
  (into {} (for [id (.keySet ^HashMap (.getTag view))]
             [(name id) (get-value (find-view view id))])))

(defn value! [^View view data]
  (log/d "editing profile " data " on view" view)
  (doseq [id (.keySet ^HashMap (.getTag view))]
    (log/d "fill id:" id " with " (data (name id) ""))
    (set-value (find-view view id) (data (name id) ""))))

(defn edit-profile [name]
  (log/d "edit profile " name)
  (show! @edit-profile-fragment)
  (value! (.getView ^Fragment @edit-profile-fragment) (@profiles name {})))

(defn- mk-list-profiles-fragment [activity]
  (simple-fragment
   activity
   [:linear-layout {:orientation :vertical :id-holder true
                    :layout-width :fill :layout-height :wrap}
    [:list-view
     {:layout-width :fill
      :adapter (ref-adapter
                (fn[_] [:linear-layout {:id-holder true :orientation :horizontal
                                        :layout-width :fill :padding 30}
                        [:text-view {:id ::caption :layout-width :fill :layout-weight 1}]])
                (fn[position view _ data]
                  (let [v (find-view view ::caption)]
                    (config v :text data)))
                profiles
                #(or (keys %) []))
      :on-item-click (fn [^AdapterView v _ position _]
                       (run-backup (.getItemAtPosition v position)))
      :on-create-context-menu
      (fn [menu ^View v ^android.widget.AdapterView$AdapterContextMenuInfo info]
        (let [name (.getItemAtPosition ^ListView v (.-position info))]
          (make-menu menu [[:item {:title "Run"
                                   :on-click (fn[_] (run-backup name))}]
                           [:item {:title "Edit"
                                   :on-click (fn [_] (edit-profile name))}]
                           [:item {:title "Delete"
                                   :on-click (fn[_] (swap! profiles dissoc name))}]])))}]]))

(defn- mk-edit-profile-fragment [activity]
  (simple-fragment
   activity [:linear-layout {:orientation :vertical :layout-width :fill
                             :layout-height :wrap :id-holder true}
             [:text-view {:text "Profile Name:"}]
             [:edit-text {:id ::name :hint "Unique name" :layout-width :fill}]
             [:text-view {:text "Backup server:"}]
             [:edit-text {:id           ::backup-server
                          :hint         "http://yourserver/"
                          :layout-width :fill}]
             [:text-view {:text "Remote Directory:"}]
             [:edit-text {:id           ::remote-dir
                          :hint         "backup destination"
                          :layout-width :fill}]
             [:text-view {:text "Local Directory:"}]
             [:edit-text {:id ::backup-dir
                          :hint "local directory"
                          :layout-width :fill}]
             [:text-view {:text "Backup Mode:"}]
             [:radio-group {:id ::mode :layout-width :fill
                            :orientation LinearLayout/HORIZONTAL}
              [:radio-button {:text "Manual"}]
              [:radio-button {:text "Automatic"}]]

             [:linear-layout {:orientation :horizontal
                              :layout-width :fill :layout-height :wrap
                              :gravity Gravity/CENTER}
              [:button {:text "Save"
                        :on-click (fn [_] (save-profile))}]
              [:button {:text "Cancel"
                        :on-click (fn [_] (show! @list-profiles-fragment))}]]]))

(defn- mk-run-profile-fragment [activity]
  (simple-fragment activity [:linear-layout {:id-holder true :layout-width :fill
                                             :layout-height :fill }
                             [:scroll-view {:layout-width :fill
                                            :layout-height :wrap}
                              [:linear-layout {:layout-width :fill :orientation :vertical
                                               :layout-height :wrap}
                               [:text-view {:id ::run-log :layout-width :fill
                                            :layout-height :fill :layout-weight 1}]
                               [:button {:id ::close-btn
                                         :text "Close" :visibility View/INVISIBLE
                                         :on-click (fn[_] (show! @list-profiles-fragment))}]]]]))

(defn save-profile []
  (let [profile (value (.getView ^Fragment @edit-profile-fragment))]
    (swap! profiles assoc (profile "name") profile)
    (show! @list-profiles-fragment)))

(defactivity backup.droid.MainActivity
  :key :main
  (onCreate [this bundle]
            (.superOnCreate this bundle)
            (neko.debug/keep-screen-on this)
            (def list-profiles-fragment (delay (mk-list-profiles-fragment this)))
            (def edit-profile-fragment (delay (mk-edit-profile-fragment this)))
            (def run-profile-fragment (delay (mk-run-profile-fragment this)))
            (on-ui
             (set-content-view! (*a)
                                [:frame-layout {:id ::main-container}])
             (show! @list-profiles-fragment)))
  (onCreateOptionsMenu [this ^Menu menu]
                       (-> menu (.add "Add Profile")
                           (.setOnMenuItemClickListener
                            (reify
                              android.view.MenuItem$OnMenuItemClickListener
                              (onMenuItemClick [_ item]
                                (edit-profile nil)
                                true))))
                       true)
  (onRestart [this]
             (.superOnRestart this)
             (log/d "restarting activity"))
  (onResume [this]
            (.superOnResume this)
            (log/d "resuming activity"))
  (onPause [this]
           (.superOnPause this)
           (log/d "pausing activity"))
  (onDestroy [this]
             (.superOnDestroy this)
             (log/d "destroying activity")))
