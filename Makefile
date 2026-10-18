.PHONY: cljs release test uberjar docker run-jar repl-jar session-key report

# Dev: shadow-cljs server (watch builds from its UI at http://localhost:9630, nREPL on 9000)
cljs:
	pnpm exec shadow-cljs server

# Production CLJS bundle -> resources/public/js/main
release:
	TIMBRE_LEVEL=:warn pnpm exec shadow-cljs release main

# Headless end-to-end tests (no browser needed)
test:
	clojure -M:dev:test:run-tests

# Production uberjar -> target/app.jar (includes the release bundle)
uberjar: release
	clojure -T:build uber

# Run the uberjar exactly like production: config/prod.edn, with values from your environment
# (startup names any missing variable; see "Running the jar locally" in the README)
run-jar:
	java -jar target/app.jar

# REPL into a running jar (e.g. to add users) on $NREPL_PORT, using the nREPL client inside the jar
repl-jar:
	java -cp target/app.jar clojure.main -m nrepl.cmdline --connect --host 127.0.0.1 --port $${NREPL_PORT:?set NREPL_PORT to the port the server uses}

# A new random SESSION_KEY (keep it stable: changing it logs everybody out)
session-key:
	@openssl rand -hex 16

docker:
	docker build -t rad-statecharts-template .

# CLJS bundle size report -> report.html
report:
	pnpm exec shadow-cljs run shadow.cljs.build-report main report.html
