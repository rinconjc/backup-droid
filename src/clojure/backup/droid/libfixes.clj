(ns backup.droid.libfixes
  (:gen-class
   :name DatatypeConverter
   :methods [#^{:static true} [printBase64Binary [String] "[B"]]))

(defn -printBase64Binary [input]
  (println "base64binary:" input)
  (byte-array 10))
