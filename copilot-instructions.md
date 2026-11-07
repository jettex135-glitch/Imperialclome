# Imperial Reader – instructions for Copilot

## Scope rule (most important)
Make ONLY the change that was asked for. Preserve every other working feature and the existing UI.
Do not redesign, rename, reformat, reorder, "clean up", refactor or upgrade anything that was not named in the request.
Reuse existing screens, adapters, layouts and flows instead of creating new ones.
If a request seems to need a change outside the named files, STOP and ask first. Never "fix" unrelated code you notice; mention it instead.

## Before editing
1. List the exact files you plan to touch and why. Keep that list as short as possible.
2. Read the file's existing patterns first and copy them (naming, error handling, coroutines, Jsoup usage).
3. Prefer adding a new file over editing a shared one.

## Do NOT touch unless the request names them
- `data/db/*` (Room entities, DAOs, AppDatabase) – no schema changes, no new columns, no migrations
- `data/backup/*`, `data/download/*`, `data/update/*`, `data/storage/*`
- `data/network/*` (OkHttp client, CloudflareInterceptor)
- `ui/theme/*`, `res/layout/*`, `res/values/*`, `AndroidManifest.xml`, `app/build.gradle`, `.github/workflows/*`
- Existing source files in `data/source/` other than the one being worked on
- Version numbers, dependencies, ProGuard rules

## Sources
- Every site is a class implementing `Source` (see `data/source/Source.kt`). To add a site, add ONE new class/file and ONE line in `SourceManager.builtin`.
- Keep the `Source` interface backward compatible: new members need a default value.
- Anime sources extend `AnimeHttpSource` in `data/source/AnimeSources.kt`: `SManga` = anime, `SChapter` = episode, episodes are returned newest first, `loadEpisode()` returns one playable URL (.m3u8 or .mp4) plus its headers.
- Source ids are permanent (they are stored in the user's library). Never change an existing `id`.
- Never invent CSS selectors or API endpoints. If you cannot verify a site's markup or endpoint, say so and leave a clearly named constant for the user to fill in.

## Anime wording
Only when `source.isAnime` is true: "chapters" -> "episodes", "Start/Read again" -> "Watch/Watch again". Manga, comic and novel sources must look and behave exactly as before.

## Reader and anime player
- Manga/comic video pages are handled in `ui/reader/PageAdapter.kt` (VideoView): `.mp4` loops, `.m3u8` plays once. Leave that as is.
- Anime episodes are routed by `ReaderActivity.loadChapter` to `ui/player/AnimePlayerActivity.kt` (Media3/ExoPlayer: SUB/DUB toggle, server picker, prev/next episode, progress in seconds in `lastPageRead`). Player changes go in that file only.
- Media3 is used by the anime player only. Do not add other player libraries or move manga/novel reading onto it.
- Sub/dub servers come from `Source.getVideoOptions()`; sources that do not override it fall back to one default option.
- Stream discovery order in `AnimeHttpSource.resolveEmbed`: plain HTML, unpacked JS, nested embeds, then `WebViewStream` (hidden WebView that watches requests for .m3u8/.mp4 and can click a server button such as "DUB-1"). Keep that order.
- Anime downloads: HLS episodes are merged into one file by `data/download/HlsDownloader.kt` (called from `DownloadManager.download` only when `source.isAnime`); the player plays that file when `chapter.downloaded`. Image/manga downloads must stay on the existing path. Anime sub/dub downloads: `DownloadManager.enqueue(..., dub)`; files are `001.*` = sub, `002.*` = dub; the Sub/Dub prompt (`withAudioChoice` in `MangaDetailsActivity`) appears for anime sources only.

## Large archives (existing rule)
Local CBR/RAR: stage to a cache file and open Junrar with `Archive(File)`, never `Archive(InputStream)`. No arbitrary size caps.

## Done checklist
- `cd android/ImperialReader && gradle assembleDebug` succeeds
- The diff contains only the files you listed up front
- No unrelated formatting/import-order changes
- Summarise what changed and what you deliberately did NOT change
