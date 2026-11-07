# Imperial Reader

Imperial Reader is an Android app for discovering and reading manga, manhwa, manhua, comics, EPUB books, and web novels. It brings online catalogs, downloaded chapters, and your own local files together in one library.

> Online catalogs are third-party sites and services. Their availability, content, and site layouts can change; a source that stops working may need to be disabled or replaced.

## Get started quickly

### Install the app

1. Open the [GitHub Releases](https://github.com/Kvngz3n0/ImperialreaderApp/releases) page and download the APK from the latest release.
2. Open the APK on your Android device and approve installation from that source if Android asks.
3. Launch **Imperial Reader**. The app needs internet access to browse online catalogs. Notification permission is optional and may be requested on Android 13 and newer.

The app supports Android 8.0 (API 26) and later.

### Find and read something

1. Open **Browse**. The **Sources** tab lists manga and comic sites; the **Extensions** tab lists book and web-novel catalogs.
2. Open a source or catalog, browse popular titles, or use search. In a manga source, choose **Latest** to browse recent updates.
3. Open a title. Add it to your library, then tap **Start** or a chapter to read.
4. Use the **Library**, **Updates**, and **History** tabs to return to saved titles, check for new chapters, and continue recent reading.

For manga and comics, tap the reader to show controls. Choose left-to-right, right-to-left, or vertical/webtoon mode in the reader; your default mode and other reading preferences are in **More → Settings**.

### Read your own files

1. In **More → Storage folder**, choose a folder and grant access. Imperial Reader creates `local/`, `downloads/`, and `autobackup/` inside it.
2. Put local files under `local/<Title>/`. Supported chapters include image folders, CBZ/ZIP/CBR/RAR archives, PDF, EPUB, PSD, PSB, and CLIP files.
3. Choose **More → Scan local library** to import or refresh your local titles.

PDFs open in a paged reader with page navigation and saved progress. You can also browse Project Gutenberg and Internet Archive for EPUBs, or browse supported web-novel catalogs including NovelArrow. Downloaded web-novel chapters can be packaged as an EPUB. EPUB reading includes adjustable text size, dark/sepia/light themes, text-to-speech, and a seekable chapter progress bar.

## What it can do

### Discover and search

- Browse manga, manhwa, manhua, webcomics, and western comics from built-in sources.
- Browse online EPUB and web-novel catalogs, including Project Gutenberg, Internet Archive, Royal Road, NovelArrow, and supported web-novel sites.
- Search across online catalogs from **Browse** or from the library's global search. Global search preserves same-title results from separate catalogs, combines manga sources when requested, and offers optional genre/tag and artist filters.
- Add a custom manga site by name and URL. Imperial Reader attempts to detect supported site engines when you first open it.
- Enable or disable sources and open a source in the in-app WebView. The WebView can help with sites that require a Cloudflare challenge.
- Adult (18+) sources and ratings are hidden by default and can be enabled in Settings after age confirmation.

### Organize your library

- Save titles to your library and group them into custom categories.
- Search, sort, and filter library entries, including by unread chapters, bookmarks, and downloaded chapters.
- Select multiple titles to move them between categories, remove them, or queue reviewed bulk migration matches. Manga, web-novel, and catalog-backed book entries use their compatible migration/import paths.
- See unread and downloaded/local badges, with display options in Settings.
- See series publication states as ongoing, completed, hiatus, or axed when a source provides status information.
- Refresh a title's chapter list or update the library for new chapters.

### Read, track, and download

- Read online or downloaded manga/comic chapters in left-to-right, right-to-left, or vertical/webtoon layouts.
- Resume reading progress, move between chapters, and optionally use screen-edge taps or volume keys for page navigation.
- Use double-tap zoom in the image reader. Direct MP4 pages play with native playback controls; MP4 chapter downloads are saved as video files.
- Bookmark chapters, mark chapters read or unread, and see reading history. Incognito mode disables reading-history entries.
- Queue chapter downloads, follow the queue in **More**, and read downloaded chapters offline.
- Browse and read EPUBs and web novels, seek between EPUB sections, resume saved chapter/page progress, adjust text size and theme, and listen with device text-to-speech.
- Optionally show only downloaded titles in the library.

### Local files and backups

- Import local image folders and CBZ, ZIP, CBR/RAR (RAR 2–4), PDF, EPUB, PSD, PSB, and CLIP files as library titles. Photoshop and CLIP artwork is rendered from supported previews or decoded composites.
- Read local titles from the library and refresh the scan when files change.
- Choose where downloads and local files are stored; without a selected folder, the app uses private app storage.
- Export or restore a JSON backup containing library titles, categories, chapter read/bookmark/progress state, and history. Restore merges with the current library rather than deleting it.
- Run restores in the background and monitor progress or completion in the Android notification.
- Create automatic backups manually or let the app run them periodically; it keeps a rolling set of the five most recent automatic backups.

## Settings

Open **More → Settings** to configure:

- Default manga/comic reading direction, screen-on behavior, fullscreen mode, edge taps, and volume-key navigation.
- Choose Imperial, Ocean, Forest, Rose, or Android device colors; set the app to follow the system, light, or dark appearance. Theme colors use Material color roles across app screens without tinting cover artwork or reader pages.
- App lock, library grid columns, unread/download badges, and automatic library updates.
- Download concurrency, automatic app-update checks, automatic cover refresh, global-search concurrency/timeouts, and optional skipping of duplicate chapters and gaidens.
- Cover extraction for local entries.
- Visibility of adult sources and ratings.
- Clearing cached images/archive data or saved site cookies.

The EPUB reader has its own text-size controls, light/dark/sepia theme selector, seekable chapter progress, and text-to-speech button. WEBTOON episodes can include audio when the source provides it; entries without audio remain image-only.

## Build from source

### Requirements

- Android SDK platform 34 and Android build tools 34.0.0.
- JDK 17.
- Gradle 8.2.

### Build a debug APK

From the repository root:

```sh
cd android/ImperialReader
gradle assembleDebug
```

The APK is written to `android/ImperialReader/app/build/outputs/apk/debug/`. A GitHub Actions workflow also builds a debug APK when Android project files are pushed to `main` or `master`, or when manually dispatched; the uploaded workflow artifact is retained for 14 days.

To build a release APK, run `gradle assembleRelease` from `android/ImperialReader`. Without a configured signing key, Gradle produces an unsigned release APK.

## Repository layout

- `android/ImperialReader/` — Android application source and Gradle build.
- `.github/workflows/build-imperialreader-apk.yml` — repository APK build workflow.
- `artifacts/`, `lib/`, and `scripts/` — additional workspace artifacts and tooling.

## Notes

- The app depends on third-party websites and public catalog APIs. Site changes, network availability, regional restrictions, or anti-bot checks can affect browsing and downloads.
- Imperial Reader does not run Mihon/Keiyoushi APK extensions; its **Extensions** tab contains its built-in book and web-novel catalogs. QiManga is present but its live API responses could not be verified because the service returned HTTP 403.
- Application appearance is resource-based and separate from EPUB/PDF reader appearance. Cover artwork and comic pages are rendered without application theme tinting.
- Use custom sources only with sites and material you are authorized to access. Adult sources are intended only for adults 18 and older.
- Imperial Reader is an independent reader app and is not affiliated with the third-party catalogs it can access.
