(ns com.example.ui.render.controls
  "Headless control renderers for buttons, text inputs, and boolean toggles.
   Dispatches via `control/render-control` on `[control-type style]`.
   Plain HTML only, no CSS, no React component libraries."
  (:require
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.rad.statechart.control :as control]
    [com.fulcrologic.rad.options-util :refer [?!]]))

(defn- event-value
  "The new value carried by an onChange event. In the browser that's `evt.target.value`; in the JVM, Fulcro's
   headless driver sends `{:target {:value v}}` (also read by `evt/target-value`). A raw value is accepted too,
   which is handy when invoking handlers by hand at the REPL."
  [evt]
  #?(:cljs (evt/target-value evt)
     :clj  (if (map? evt) (evt/target-value evt) evt)))

(defmethod control/render-control [:button :default] [_control-type _style instance control-key]
  (let [controls (control/component-controls instance)
        {:keys [label action disabled? visible?] :or {visible? true}} (get controls control-key)]
    (when (?! visible? instance)
      (dom/button {:data-rad-type    "control"
                   :data-rad-control (str control-key)
                   :key              (str control-key)
                   :disabled         (boolean (?! disabled? instance))
                   :onClick          (fn [_] (when action (action instance)))}
        (?! label instance)))))

(defmethod control/render-control [:string :default] [_control-type _style instance control-key]
  (let [controls (control/component-controls instance)
        {:keys [label onChange visible?] :or {visible? true}} (get controls control-key)
        value    (or (control/current-value instance control-key) "")]
    (when (?! visible? instance)
      (dom/div {:key              (str control-key)
                :data-rad-type    "control"
                :data-rad-control (str control-key)}
        (when label
          (dom/label {:htmlFor (str "control-" (name control-key))}
            (?! label instance)))
        (dom/input {:id       (str "control-" (name control-key))
                    :type     "text"
                    :value    (str value)
                    :onChange (fn [evt]
                                (let [v (event-value evt)]
                                  (control/set-parameter! instance control-key v)
                                  (when onChange (onChange instance v))))})))))

(defmethod control/render-control [:boolean :default] [_control-type _style instance control-key]
  (let [controls (control/component-controls instance)
        {:keys [label onChange visible?] :or {visible? true}} (get controls control-key)
        value    (boolean (control/current-value instance control-key))]
    (when (?! visible? instance)
      (dom/div {:data-rad-type    "control"
                :key              (str control-key)
                :data-rad-control (str control-key)}
        (dom/label {:htmlFor (str "control-" (name control-key))}
          (dom/input {:id       (str "control-" (name control-key))
                      :type     "checkbox"
                      :checked  (boolean value)
                      :onChange (fn [_evt]
                                  (let [new-val (not value)]
                                    (control/set-parameter! instance control-key new-val)
                                    (when onChange (onChange instance new-val))))})
          (when label
            (dom/span nil (?! label instance))))))))
