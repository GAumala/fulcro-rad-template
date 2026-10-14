.PHONY: cljs release test uberjar docker run-jar report

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

# Run the uberjar like production would, with Datomic persisted under ./data
run-jar:
	PORT=3000 DATOMIC_STORAGE_DIR=$(CURDIR)/data java -jar target/app.jar

docker:
	docker build -t rad-statecharts-template .

# CLJS bundle size report -> report.html
report:
	pnpm exec shadow-cljs run shadow.cljs.build-report main report.html
