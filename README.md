# Music

A one-person feed of YouTube videos. One video per post, plus an optional note.

Reading the feed needs no login — the front page is public. Posting, editing and
deleting do.

A post is made by pasting whatever is at hand: a watch URL, a `youtu.be` share
link, an `/embed/`, `/shorts/` or `/live/` URL, or the bare 11-character id. The
server resolves the id and asks YouTube's public oEmbed endpoint for the title,
so a post names itself. Both title and note stay editable afterwards.

Clicking a post's header expands its player in place, so several can be open at
once without leaving the page. The search box narrows the feed over title and
note.

## Hosting

Runs standalone in dev, and in production inside the [plurama](../plurama)
umbrella at `music.eighttrigrams.net`. Namespace prefix `et.mu`.

## Ports

- `PORT` **3160** — the clojure server
- `SHADOW_PORT` **9806** — shadow-cljs

Both come from `.envrc`.

## Development

```bash
make start   # shadow-cljs watch + the clojure server
make stop
make test
make lint
```

Then open http://localhost:3160. Dev uses `:dangerously-skip-logins? true`, so
there is no login to get past; `scripts/start.sh` writes a default `config.edn`
on first run (it is gitignored).

In production `plurama` mounts music at `music.eighttrigrams.net` by `Host`
header. It calls `et.mu.server/build-app` with music's `:apps :music` sub-config,
so the db path comes from plurama, not from here.

## API

The API documents itself:

```bash
curl localhost:3160/api/describe
```

Every route handler's docstring is its documentation, in the form
`METHOD /path — what it does`. The listing carries `:method` and `:path` as
separate fields.

### Read-only by default for machine callers

A token from `et.mu.auth/create-machine-token` is marked `:machine? true`. Such a
token may read everything, but its writes to `/api/*` are dropped (logged, and
answered `{"dropped":true}`) unless recording mode is on:

```bash
curl localhost:3160/api/recording-mode          # {"recording":false}
curl -X POST localhost:3160/api/recording-mode/toggle
```

Human and browser tokens are unaffected. This lets an agent discover and explore
the API without any risk of changing data.

### Rate limiting

A single global window, outermost in the middleware chain: 180 requests/minute in
production, 720 in dev. Override with `RATE_LIMIT_MAX_REQUESTS` and
`RATE_LIMIT_WINDOW_SECONDS`. Over the limit returns a bare `429`.

## Layout

- `src/clj/et/mu` — ring/compojure backend, next.jdbc + honeysql over SQLite,
  ragtime migrations in `resources/migrations/net/et/mu`. `youtube.clj` holds the
  URL→id resolution and the oEmbed title lookup.
- `src/cljs/et/mu/ui` — reagent SPA (`core`, `state`, `views/videos`).
- `resources/public/music` — `index.html`, `styles.css`, `css/` (teal theme in
  `base.css`, app layout in `music.css`, phone rules last in `mobile.css`).
