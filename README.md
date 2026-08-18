# Music

A one-person feed of YouTube videos. One video per post, plus an optional note.

Every post is public. Reading the feed needs no login; posting and deleting do.

**Posts are immutable.** The video, the title and the note can be made and
deleted, never edited — there is no edit affordance for any of them.

What *is* editable is a separate **annotation layer** the owner keeps beside a
post: a private **description**, and any number of **entities** the post is
assigned to. `PUT /api/videos/:id` writes that layer and only that layer. See
[The annotation layer](#the-annotation-layer).

Beside the feed sits one page that is not public at all: **Projects**, the
owner's own notes in markdown, which unlike a post are there to be rewritten.
See [Projects](#projects).

A post is made by pasting whatever is at hand: a watch URL, a `youtu.be` share
link, an `/embed/`, `/shorts/` or `/live/` URL, or the bare 11-character id. The
server resolves the id and asks YouTube's public oEmbed endpoint for the title,
so a post names itself.

Only what identifies the video is kept — the pasted URL is never stored:

- a `t=` **start offset is preserved** (`t=421`, `t=421s`, `t=7m1s`, `t=1h2m3s`,
  `#t=`, and an embed's `start=` all land as seconds). The card shows it as a
  `7:01` badge and the embed starts there.
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

## Projects

Beside the feed there is one page that is not public at all. A **project** is a
note the owner keeps — a title, a markdown body, and optionally one audio file
played in place between them — on a Projects page reachable from the top bar,
next to Categories.

It is the mirror image of a post. A post is public and **immutable**; a project
is **private and nothing but editable**. So the two affordances the feed refuses
are here: Edit opens the note itself rather than a layer beside it, and there is
no anonymous half of the page to fall back to.

Where the annotation layer is hidden by *shape* — an anonymous `GET /api/videos`
simply lacks the keys — `/api/projects` is refused outright: every route of it,
the GETs included, answers an anonymous caller `401 {"error":"Authentication
required"}`, the same body `wrap-auth` gives. Signing out drops the notes from
the client too, rather than only navigating away from them. An id belonging to
somebody else is a `404` identical to one belonging to nobody, because a `403`
would confirm the note is there and let a stranger count them by walking ids.

Editing is what finally gives `modified_at` something to do. A `PUT` may carry
the `modified_at` it read, and a save landing on a version written in between is
refused with `409` carrying the current row — the editor keeps the draft and
shows what it would have overwritten, and saving again then goes through
deliberately. The guard exists so that nobody's writing disappears unseen, not to
decide which version wins.

The modal is four fifths of the window and can only be left through its own
buttons: ⌘9 saves and closes, Escape closes, and either one asks first when there
are unsaved edits. There is no dismiss-on-backdrop — the nearest miss of a
full-window editor is the backdrop, and a stray click is a poor way to lose
prose.

The body is rendered with `marked`, as in tracker and treina, and written in the
same IJKL CodeMirror as the compose box. A post's note and description stay plain
text: markdown is the projects page's, not the feed's.

### The player

A project may carry an `audio_url`: one audio file — an mp3 sitting on some web
server — played on the overview, between the title and the prose. That is the
reading order it follows: what this note is, then what it sounds like, then what
is said about it. Only the URL is stored; music hosts nothing.

The player is SoundCloud minus the waveform — a play/pause circle, a bar that can
be dragged, clicked or arrowed along, and the position against the length. **No
waveform on purpose:** drawing one means fetching and decoding the whole file
before a note can even be heard, and what is played here is somebody else's file
across the internet. `preload="metadata"` asks for the length and nothing more, so
a page of notes costs a handful of small requests rather than a download each.

Seeking mid-file needs the far end to answer HTTP range requests. Most do; one
that does not still plays from the start, and dragging the bar simply lands back
where it was — a failed seek is not treated as an error. A file that cannot be
played at all replaces the bar with a link to open it directly, which is the
useful offer when it is your own URL that has moved.

The link is **`http(s)` only, and in production `https` only.** Not fussiness:
the production page is served over TLS, so a plain `http` audio file is blocked
by the browser as mixed content, and a link that could never play is better
refused at the door than stored. In dev the page is plain `http` too, so `http`
goes through and a file on a local server can be pointed at. `file:`,
`javascript:`, `data:` and a scheme-less path are refused everywhere — the value
is handed to an `<audio src>`, and that is not a field to park a URL scheme in.
The server decides all of this, not the browser: only the server knows which
deployment it is.

`audio_url` is a field like the others on a `PUT` — left out it keeps its value —
with one difference: **blank is a clear, not an omission.** Emptying the box in
the modal is how the player comes off a note again.

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

The projects page adds a context of its own, and it is the only one where a
`GET` is gated as well as a write — see [Projects](#projects):

- `GET /api/projects`, `GET /api/projects/:id` — the caller's own, newest first.
- `POST /api/projects` `{:title :body :audio_url}` — the title is required, the
  markdown body and the audio URL optional.
- `PUT /api/projects/:id` `{:title :body :audio_url :modified_at}` — a field left
  out keeps its value, except that a blank `audio_url` clears it;
  `modified_at` is the optimistic-concurrency guard and may be left out for
  last-write-wins. A non-blank `audio_url` that is not an `http(s)` URL — or is
  `http` in production — is a `400`.
- `DELETE /api/projects/:id`

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
  `views/categories`, `views/projects`; `markdown.cljs` is the `marked` wrapper
  and `audio.cljs` the project player).
- `resources/public/music` — `index.html`, `styles.css`, `css/` (teal theme in
  `base.css`, app layout in `music.css`, rendered markdown in `markdown.css`,
  phone rules last in `mobile.css`).
