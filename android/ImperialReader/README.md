# Imperial Reader for Android

Imperial Reader is an Android reader for manga, manhwa, comics, EPUBs, and web novels. It combines online sources and book catalogs with a local library, downloads, reading history, and backups.

The current app version is **V15** (version code 17), with Android 8.0 / API 26 as the minimum supported version.

## Current features

### Browse and search

- Browse manga and comic sources by popular, latest, or search results; add custom WordPress-based sites and enable or disable sources.
- Search the library and available sources with Global Search. Results load progressively, can be retried after temporary source failures, and can be filtered or grouped by title/source.
- Browse built-in EPUB and web-novel catalogs in **Browse → Extensions**. Imperial Reader does not install or execute Mihon/Keiyoushi APK extensions.
- Adult sources and applicable ratings are hidden until enabled in Settings with age confirmation.
- Third-party catalogs can change or require an in-app WebView challenge. Availability is not guaranteed. QiManga is integrated but its live API could not be verified because it returned HTTP 403.

### Library and migration

- Organize entries with categories, filters, sorting, unread/bookmark/download badges, and chapter status/progress.
- Source-provided publication statuses distinguish **Ongoing**, **Completed**, **Hiatus**, and **Axed**.
- Select multiple entries to change categories, remove entries, or review and run compatible bulk migrations. Manga, web novels, and catalog-backed book entries use separate migration/import paths; original entries are kept.
- Refresh chapter lists or update the library for newly published chapters.

### Readers and downloads

- Read comics in left-to-right, right-to-left, or vertical/webtoon modes. The image reader supports double-tap zoom, page seeking, chapter navigation, reader progress, and optional screen-edge or volume-key controls.
- WEBTOON audio is played when an episode provides a supported audio URL; episodes without audio continue as image chapters.
- Direct MP4 pages use native playback controls. Chapter downloads preserve MP4 files as video instead of renaming them as images.
- Download chapters for offline reading. The queue supports pause/resume and exposes progress in **More**.
- Read EPUBs with section navigation, saved progress, a contents list, adjustable text size, light/dark/sepia themes, and text-to-speech. Web-novel chapters can be packaged as EPUBs.
- Read local PDFs with saved page progress.

### Local files and backups

- Scan `local/<Title>/` folders containing image chapters, CBZ/ZIP/CBR/RAR archives, PDF, EPUB, PSD, PSB, and CLIP files.
- PSD/PSB and CLIP pages and covers use available embedded previews or supported image decoding. RAR 2–4 is supported; RAR5 is not.
- Choose a storage folder for local files and downloads. Refresh the local scan after editing files outside the app.
- Export and restore JSON backups containing library and reading data. Restore merges with the current library and reports progress in an Android notification.
- Create automatic backups and configure their schedule in **More** and **Settings**.

### Appearance and settings

- Choose Imperial, Ocean, Forest, Rose, or Android device colors, and set the app to follow the system, light, or dark appearance.
- Configure reader defaults, app lock, library and cover refresh, download and search concurrency, automatic app-update checks, adult source visibility, and cache/cookie management.
- Themes use Material color roles across app screens. Cover artwork and reader pages are not tinted; EPUB and PDF reading appearance remains independently controlled.

## Getting started

1. Install the APK from the [GitHub Releases](https://github.com/Kvngz3n0/ImperialreaderApp/releases) page.
2. Open **Browse** to find a source or book catalog. Add titles to the Library to track reading and updates.
3. Optionally choose **More → Storage folder** and **More → Scan local library** to add files from device storage.
4. Open **More → Settings** to customize reading, appearance, and refresh behavior.

## Build from source

Requirements: JDK 17, Gradle 8.2, Android SDK platform 34, and Android build tools 34.0.0.

```sh
cd android/ImperialReader
gradle :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The repository-level GitHub Actions workflow is `.github/workflows/build-imperialreader-apk.yml`. Release builds require signing configuration; an unsigned APK is not suitable for distribution.

## Notes

- Source websites and public APIs can change, become unavailable, or apply access restrictions. Imperial Reader does not bypass those restrictions.
- Use sources and download features only for content you are authorized to access.
- The project does not include generated build output, dependency caches, local SDK configuration, or signing keys in its source archive.
