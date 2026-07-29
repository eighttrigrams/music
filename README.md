# Music

A one-person feed of YouTube videos. One video per post, plus an optional note.

Every post is public. Reading the feed needs no login; posting and deleting do.

**Posts are immutable.** The video, the title and the note can be made and
deleted, never edited — there is no edit affordance for any of them.

What *is* editable is a separate **annotation layer** the owner keeps beside a
post: a private **description**, and any number of **entities** the post is
assigned to. `PUT /api/videos/:id` writes that layer and only that layer. See
[The annotation layer](#the-annotation-layer).

A post is made by pasting whatever is at hand: a watch URL, a `youtu.be` share
link, an `/embed/`, `/shorts/` or `/live/` URL, or the bare 11-character id. The
server resolves the id and asks YouTube's public oEmbed endpoint for the title,
so a post names itself.

Only what identifies the video is kept — the pasted URL is never stored:

- a `t=` **start offset is preserved** (`t=421`, `t=421s`, `t=7m1s`, `t=1h2m3s`,
  `#t=`, and an embed's `start=` all land as seconds). The card shows it as a
  `7:01` badge, the embed starts there, and the outbound link carries `&t=421`.
- share-tracking params like `si=` are **dropped**.

Clicking a post's header expands its player in place, so several can be open at
once without leaving the page. The search box narrows the feed over title and
note.

The ☾/☀ button switches between light and dark. As in tracker, the palette lives
entirely in `css/base.css` keyed on `html.dark-mode`, and the button only adds or
removes that class. The choice is remembered in `localStorage`; on a first visit
it follows the operating system.

## The annotation layer

**Categories** hold **entities**: an "artist" category holds "Caroline Polachek",
a "manufacturer" holds "Roland". They are managed on a Categories page reachable
from the top bar. A post can be assigned any number of entities and given a
description, from an Edit button on its card; the card then shows the description
as a private aside and the entities as chips. A filter menu on the right of the
search box narrows the feed to the posts assigned to *any* of the checked
entities, and the text search then applies within that subset.

The line between public and private runs *through* this layer, not around it. The
**vocabulary and the filtering are part of the public feed**: anyone may read the
categories and narrow the feed by an entity, filter menu, count badge and all.
The **annotation on a post** stays the owner's: an anonymous visitor gets no
descriptions and no chips, and no Categories page or Edit button to make them
with. So a visitor can narrow the feed to an entity and still not be told which
posts carry it. That asymmetry is the intended design.

The private half is enforced by the server, not by the client: an anonymous
`GET /api/videos` response simply does not carry the `description` or `entities`
keys — filtered or not — so there is nothing for the UI to hide.
`GET /api/categories` and the `entities=` filter answer anybody; the writes are
what stay gated. "Authenticated" here means a valid Bearer token or dev
skip-logins — note `wrap-auth` only gates *mutating* requests, so read visibility
is decided in the handler and db layers. A machine token (`:machine? true`)
verifies like any other and therefore sees everything, which is intended.

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

Beyond the feed's `GET`/`POST`/`DELETE /api/videos`, the annotation layer adds:

- `PUT /api/videos/:id` — `{:description :entity-ids}`, both replaced wholesale.
  Nothing else about the post can be written.
- `GET /api/videos?entities=1,2,5` — restrict to posts assigned to any of them,
  ANDed with `?search`. Public.
- `GET /api/categories` — categories with their entities nested, both
  alphabetical. Public; the writes below are not.
- `POST /api/categories` `{:name}`, `DELETE /api/categories/:id`
- `POST /api/categories/:id/entities` `{:name}`, `DELETE /api/entities/:id`

Deletes clean up after themselves explicitly — foreign keys are not enforced on
this connection, so `ON DELETE CASCADE` would be a promise nothing keeps.
Dropping a video, an entity or a category takes the `video_entities` rows it
orphans with it.

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
  URL→id resolution, the `t=` offset parsing and the oEmbed title lookup.
- `src/cljs/et/mu/ui` — reagent SPA (`core`, `state`, `views/videos`,
  `views/categories`).
- `resources/public/music` — `index.html`, `styles.css`, `css/` (teal theme in
  `base.css`, app layout in `music.css`, phone rules last in `mobile.css`).
