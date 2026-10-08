# Built-in catalog and source

Brio 0.3 uses the public Cinemeta catalog when no personal TMDB token is set.
It maps catalog titles to TMDB IDs for the built-in Vixsrc source. Search,
seasons, episodes and Next episode work through the same public catalog.

Leaving the custom provider field blank enables Vixsrc. The adapter requests
the provider's current movie/episode API, immediately fetches the returned
player page, extracts the public HLS playlist parameters and validates the
playlist before offering it to Media3. It forwards Referer and User-Agent.
It does not execute page scripts or display the provider's web ads.

Availability is determined by the third-party provider. Signed links can
expire; pressing Play again resolves fresh links. There is no claim of
affiliation with ClipBox, Vixsrc, Netflix or other services. This code does
not grant subscription access or circumvent DRM.

## Validation

A live movie resolution returned an HTTP 200 HLS master playlist with two
variants during implementation. This verifies discovery and playlist
availability from the development network, not complete device playback.
Device installation, decoding, remote controls and regional availability
still require testing. Automated fixtures cover API/episode paths, header
forwarding, token encoding, invalid playlist rejection, public catalog
mapping and episode selection.

## References

- Official public catalog listing: https://github.com/Stremio/stremio-official-addons
- Add-on protocol: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
- Current provider page/API observed at https://vixsrc.to/
- Historical integration reference: https://github.com/yoruix/nuvio-providers
  (its older direct-page extraction no longer matches the current landing page).

---

# Source provider contract

Configure an HTTPS endpoint in Brio Settings. Requests use a TMDB ID and content type:

```http
GET /streams?id=550&type=movie
GET /streams?id=1399&type=tv&season=1&episode=2
```

Respond with JSON. All stream URLs must be HTTPS. Providers may resolve their own authorized sources before returning the playback URL. Do not return account credentials in the response. Brio never sends its TMDB or Real-Debrid tokens to the provider.

```json
{
  "streams": [
    {
      "name": "My media server",
      "quality": "1080p",
      "url": "https://media.example/episode/master.m3u8",
      "headers": {"Referer": "https://media.example/"},
      "introStartMs": 30000,
      "introEndMs": 115000,
      "creditsStartMs": 2500000,
      "creditsEndMs": 2600000
    }
  ]
}
```

`name`, `quality`, `headers`, `requiresDebrid`, and the timing markers are optional. Markers use milliseconds relative to the actual supplied stream, not the episode's nominal runtime. Omit unknown values. Invalid or insecure URLs are excluded. A provider can return `{"streams":[]}` when no sources are available. At most 30 streams are displayed.

- **Skip intro** appears between intro start (default 0) and intro end. It seeks to intro end.
- During marked credits, movies show **Skip credits**, seeking to credits end, or to the known stream duration if no credits end is specified.
- For a TV episode with a known following episode, **Next episode** takes the credits action's place. It resolves and plays the next episode through the configured provider; it is also accessible while player controls are visible and after playback ends.
- **Skip 90 seconds** is a manual fallback when no intro segment is currently active. It is clearly separate from accurate marker-based skipping.
- The first returned source is used for Next episode; ordinary playback lets the viewer choose a source. Errors return the viewer to source selection.

This contract keeps content discovery independent from provider availability. Provider timestamps cannot be transferred to a different encoding or edited version without checking their accuracy.

For a host link that needs Real-Debrid, return `"requiresDebrid": true` in the stream object. Brio resolves it with the viewer's saved token before playing, including for Next episode. Tokens are sent only to Real-Debrid, never to your provider. Leave the flag absent or false for directly playable streams. Timing markers must still match the resolved media file.


## Stremio-compatible add-ons

Settings also accepts a complete HTTPS (or `stremio://`) install URL ending in
`/manifest.json`. Brio reads the manifest and maps the selected TMDB title to
its IMDb ID. Movie requests use `/stream/movie/tt12345.json`; episode requests
use `/stream/series/tt12345:season:episode.json`. Configured path segments
are preserved. The same adapter is used by Next episode.

Only direct HTTPS `url` entries are supported. Request headers from
`behaviorHints.proxyHeaders.request` are forwarded to Media3. Torrent info
hashes, external browser pages, and YouTube IDs are not playable sources.
No source add-on is installed by default. This integration does not grant
access to subscription services or guarantee a particular title is available.
A TMDB token is required for IMDb mapping; Real-Debrid configuration, if needed
by an add-on, belongs in that add-on's configured manifest URL.
Add-on subtitle responses and automatic intro timestamps are not implemented.

Protocol: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
