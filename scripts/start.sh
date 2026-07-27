#!/bin/bash
set -e

if [ ! -f config.edn ]; then
  echo "Creating default config.edn..."
  cat > config.edn << 'EOF'
{:db {:type :sqlite-file
      :path "data/music.db"}
 :port #long #or [#env PORT 3160]
 :nrepl-port 7900
 :dangerously-skip-logins? true}
EOF
fi

# Mark which environment owns the dev server so stop.sh refuses a cross-env
# stop (host vs container port-forward proxy). See tracker's scripts for the
# full rationale.
if [ -f /.dockerenv ]; then
  echo container > .dev-server.lock
else
  echo host > .dev-server.lock
fi

if [ ! -d node_modules ]; then
  echo "Installing npm dependencies..."
  npm install
fi

# SHADOW=false to skip hot reload and run a release build instead.
if [ "${SHADOW:-true}" = "true" ]; then
  echo "Starting shadow-cljs watch..."
  npx shadow-cljs watch app &
  echo $! > .shadow-cljs.pid
  for _ in $(seq 1 60); do
    if grep -q "shadow.cljs.devtools.client" resources/public/music/js/main.js 2>/dev/null; then
      break
    fi
    sleep 1
  done
else
  echo "Building ClojureScript..."
  npx shadow-cljs release app
fi

echo "Starting server in development mode..."
DEV=true clojure -X:run
