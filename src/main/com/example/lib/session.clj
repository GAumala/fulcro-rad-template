(ns com.example.lib.session
  "Ring session access for Pathom resolvers and mutations.

   Resolvers read the session from the Ring request in the Pathom env. Mutations can't return a Ring response,
   so to change the session they call `set-session!`, and `ring-middleware/wrap-api` copies the result onto
   the HTTP response (see `with-session-updates`).")

(defn session
  "The current Ring session (a map; empty when there is none)."
  [env]
  (get-in env [:ring/request :session]))

(defn set-session!
  "Replace the session with `new-session` (nil ends it) once the API request completes."
  [env new-session]
  (vreset! (::update env) new-session))

(defn with-session-updates
  "Calls `(run-parser env-additions)` with what the Pathom env needs for `set-session!`, then puts any new
   session on the Ring `response` it returns."
  [run-parser]
  (let [update   (volatile! ::unchanged)
        response (run-parser {::update update})]
    (cond-> response
      (not= ::unchanged @update) (assoc :session @update))))
