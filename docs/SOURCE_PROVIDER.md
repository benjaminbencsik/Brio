# Playback sources

Brio keeps its movie and TV catalog separate from video playback. A title in the
catalog is not a promise that a playable source exists. The built-in Vixsrc
resolver is checked when you press Play. Under **Settings → Sources**, you can
add up to six more HTTPS source URLs. Brio queries them alongside the built-in
source and combines distinct direct video links into the server picker. If one
source fails, results from the others can still appear. Availability depends on
the source, title, network, and region. Use only sources you are authorized to
access.

Add one source URL at a time, then press **Save settings**. To remove a source,
press **Remove** beside its URL and save. Existing single-source settings carry
forward automatically. Your URLs are encrypted on your device; they are not
included in device library sync. No extra sources are installed automatically.

## Stremio-compatible add-ons

Paste a full HTTPS manifest URL ending in `/manifest.json`, or a `stremio://`
install URL. Brio maps the selected title to its IMDb ID. Movies are requested
at `/stream/movie/tt12345.json`, and episodes at
`/stream/series/tt12345:season:episode.json`. Only direct HTTPS `url` entries
are playable. Brio forwards `behaviorHints.proxyHeaders.request` headers to
Media3. Torrent hashes, YouTube IDs, and browser-only links are not supported.
An IMDb ID is needed; when the public catalog lacks one, a TMDB token can look
it up. The add-on may require its own configuration in the manifest URL.

Protocol: https://stremio.github.io/stremio-addon-sdk/protocol.html

## Brio JSON source endpoints

You can also add an HTTPS endpoint that responds to:

```http
GET /streams?id=550&type=movie
GET /streams?id=1399&type=tv&season=1&episode=2
```

The endpoint may live at any path; Brio appends these query parameters to the
URL you enter. Return JSON with direct HTTPS streams:

```json
{
  "streams": [
    {
      "name": "My media server",
      "quality": "1080p",
      "url": "https://media.example/episode/master.m3u8",
      "headers": { "Referer": "https://media.example/" },
      "introStartMs": 30000,
      "introEndMs": 115000,
      "creditsStartMs": 2500000,
      "creditsEndMs": 2600000
    }
  ]
}
```

`name`, `quality`, `headers`, `requiresDebrid`, and the timing markers are
optional. Timing markers are milliseconds measured against that exact video.
Return `{"streams":[]}` when no video is available. Set `requiresDebrid` to
`true` for a link that needs the viewer's configured Real-Debrid account. Brio
sends its token only to Real-Debrid, not to this endpoint. The source endpoint
receives the catalog ID, content type, and season/episode numbers; it does not
receive the user's TMDB or Real-Debrid tokens.

## Player behavior

- MP4, HLS, and DASH direct video links play in Media3; a link can expire, so
  press Play again to resolve fresh links.
- **Skip intro** appears when a source supplies a valid intro interval.
- **Skip credits** and **Next episode** use episode metadata and any supplied
  timing markers. **Skip 90 seconds** is a manual action when no marker is known.
- Errors allow the viewer to choose another returned source. Selecting the next
  episode queries all configured sources again.

Brio does not bypass DRM or provide subscriptions to other services. Playback
on an actual phone, tablet, or TV still depends on source availability and
device codec support.
