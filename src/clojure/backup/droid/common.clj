(ns backup.droid.common
  (:import [android.widget EditText RadioGroup RadioButton]))

(defmulti get-value class)

(defmethod get-value EditText [^EditText v]
  (-> v .getText .toString))

(defmethod get-value RadioGroup [^RadioGroup rg]
  (let [id (.getCheckedRadioButtonId rg)]
    (if (not= -1 id)
      (.getText ^RadioButton (.findViewById rg id)))))

(defmulti set-value (fn [view value] (class view)))

(defmethod set-value EditText [^EditText view ^String v]
  (.setText view v))

(defmethod set-value RadioGroup [^RadioGroup rg ^String v]
  (if-let [id (and v (some #(let [^RadioButton b (.getChildAt rg %)] (if (= v (.getText b)) (.getId b)))
                     (range 0 (.getChildCount rg))))]
    (.check rg id)))
