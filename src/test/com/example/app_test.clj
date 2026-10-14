(ns com.example.app-test
  "End-to-end tests of the app's features (see `test-support` for the setup)."
  (:require
    [clojure.test :refer [use-fixtures]]
    [com.example.test-support :as ts]
    [com.example.ui.account-forms :refer [AccountForm AccountList]]
    [com.fulcrologic.fulcro.headless :as h]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.statechart.form :as form]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [fulcro-spec.core :refer [specification behavior assertions =>]]))

(use-fixtures :once ts/with-backend)

(specification "Account report"
  (let [app (ts/logged-in-app)]
    (scr/route-to! app AccountList)
    (assertions
      "loads active accounts from the server"
      (.contains (ts/main-text app) "tony@example.com") => true
      "hides inactive accounts by default"
      (.contains (ts/main-text app) "sally@example.com") => false)))

(specification "Creating an account"
  (let [app (ts/logged-in-app)]
    (form/create! app AccountForm)
    (h/render-frame! app)
    (h/type-into-labeled! app "Email" "new@example.com")
    (h/click-on-text! app "Save")
    (assertions
      "persists the new account"
      (contains? (ts/db-emails) "new@example.com") => true)))

(specification "Leaving a form with unsaved changes"
  (let [app (ts/logged-in-app)]
    (form/edit! app AccountForm (new-uuid 100))
    (h/render-frame! app)
    (h/type-into-labeled! app "Email" "changed@example.com")
    (scr/route-to! app AccountList)
    (behavior "is denied until the user decides"
      (assertions
        (.contains (ts/page-text app) "You have unsaved changes.") => true))
    (h/click-on-text! app "Discard changes")
    (assertions
      "continues to the requested route when changes are discarded"
      (.contains (ts/page-text app) "All Accounts") => true
      "does not save the discarded edit"
      (contains? (ts/db-emails) "changed@example.com") => false)))
