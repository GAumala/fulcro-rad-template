.PHONY: cljs release test uberjar docker run-jar session-key report

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
# The first run creates the login from ADMIN_EMAIL/ADMIN_PASSWORD, e.g.:
#   SESSION_KEY=... ADMIN_EMAIL=me@example.com ADMIN_PASSWORD=... make run-jar
run-jar:
	PORT=3000 DATOMIC_STORAGE_DIR=$(CURDIR)/data SECURE_COOKIES=false java -jar target/app.jar

# A new random SESSION_KEY (keep it stable: changing it logs everybody out)
session-key:
	@openssl rand -hex 16

docker:
	docker build -t rad-statecharts-template .

# CLJS bundle size report -> report.html
report:
	pnpm exec shadow-cljs run shadow.cljs.build-report main report.html
