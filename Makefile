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

# Run the uberjar like production would, with Datomic persisted under ./data.
# Needs SESSION_KEY (see `make session-key`). Plain HTTP locally, so cookies can't be Secure-only.
run-jar:
	PORT=3000 NREPL_PORT=7888 DATOMIC_STORAGE_DIR=$(CURDIR)/data SECURE_COOKIES=false java -jar target/app.jar

# REPL into the server started by run-jar (e.g. to add users), using the nREPL client inside the jar
repl-jar:
	java -cp target/app.jar clojure.main -m nrepl.cmdline --connect --host 127.0.0.1 --port 7888

# A new random SESSION_KEY (keep it stable: changing it logs everybody out)
session-key:
	@openssl rand -hex 16

docker:
	docker build -t rad-statecharts-template .

# CLJS bundle size report -> report.html
report:
	pnpm exec shadow-cljs run shadow.cljs.build-report main report.html
