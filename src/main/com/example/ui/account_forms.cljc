(ns com.example.ui.account-forms
  "Sample RAD form and report. Note the requires: forms and reports come from
   `com.fulcrologic.rad.statechart.*`, while the option keys (`fo/`, `ro/`) are the
   standard RAD ones.

   URLs are NOT set here: `fo/route-prefix` / `ro/route` are ignored by statecharts routing.
   Use `:route/segment` in the routing chart (see `com.example.ui.routes`)."
  (:require
    [clojure.string :as str]
    [com.example.model-rad.account :as r.account]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.statechart.control :as control]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.rad.statechart.report :as report]))

(form/defsc-form AccountForm [this props]
  {fo/id             r.account/id
   fo/attributes     [r.account/email
                      r.account/active?]
   fo/default-values {:account/active? true}
   fo/title          "Edit Account"})

(report/defsc-report AccountList [this props]
  {ro/title               "All Accounts"
   ro/source-attribute    :account/all-accounts
   ro/row-pk              r.account/id
   ro/columns             [r.account/email r.account/active?]
   ro/column-headings     {:account/email   "Email"
                           :account/active? "Active?"}
   ro/form-links          {:account/email AccountForm}
   ro/column-formatters   {:account/active? (fn [_report v _row _attr] (if v "Yes" "No"))}
   ro/row-visible?        (fn [{::keys [filter-email]} {:account/keys [email]}]
                            (let [target (some-> filter-email str/trim str/lower-case)]
                              (or (str/blank? target)
                                (str/includes? (str/lower-case (or email "")) target))))
   ro/run-on-mount?       true

   ro/initial-sort-params {:sort-by          :account/email
                           :ascending?       true
                           :sortable-columns #{:account/email}}

   ro/controls            {::new-account   {:type   :button
                                            :local? true
                                            :label  "New Account"
                                            :action (fn [this _] (form/create! this AccountForm))}
                           ::filter-email  {:type        :string
                                            :local?      true
                                            :label       "Filter"
                                            :placeholder "Type part of an email"
                                            :onChange    (fn [this _] (report/filter-rows! this))}
                           ;; Not local: the value is sent to the server as a query param (see `get-all-accounts`)
                           :show-inactive? {:type          :boolean
                                            :default-value false
                                            :label         "Show inactive accounts?"
                                            :onChange      (fn [this _] (control/run! this))}}

   ro/control-layout      {:action-buttons [::new-account]
                           :inputs         [[::filter-email]
                                            [:show-inactive?]]}})
