# AGENTS.md

This file is the shared source of truth for coding agents working in this repository.

## Workflow Preferences

- Do not start implementing broad feature work until the design or approach is confirmed. First present a brief plan with affected files, approach, and trade-offs.
- If a first implementation looks complex, proactively suggest a simpler alternative before coding.
- After completing a feature, check for and remove dead code or unused parameters from the old approach.
- When the user explicitly asks for a concrete edit, cleanup, or maintenance task, make a reasonable scoped change and verify it.
- Do not modify code under the existing `io.legado.app` package namespace when working on the E-Ink migration unless it is a config-level change (Gradle, version catalog, Manifest). New E-Ink work goes under `io.legado.app.eink` (in `:app`) or the `:modules:eink` library.

## Build & Commit Hygiene

- After staging files, always run `git status` before committing to verify no unrelated files (for example `.zcode/`, tool config, temp scripts, or extracted backup data) are included.
- Never commit directly to `master`; work on `develop` or a feature branch.
- Run a build check (`./gradlew assembleDebug`) before committing to catch compilation errors. On this machine set `GRADLE_USER_HOME=D:\Projects\AndroidProjects\.gradle` to avoid a KSP cross-drive root mismatch between the C: default cache and the D: project.
- Do not commit changes to commented-out mirror repositories in `settings.gradle`; those are for local use only.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (ProGuard enabled)
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug

# Build only the E-Ink component library module
./gradlew :modules:eink:assembleDebug
```

The project uses Groovy DSL (`build.gradle`) with a version catalog at `gradle/libs.versions.toml`. AGP 8.13.2, Kotlin 2.3.0, Java 17 toolchain. KSP is used for Room. The Compose compiler is the Kotlin 2.x standalone plugin `org.jetbrains.kotlin.plugin.compose`.

## Project Configuration

- **Package / namespace**: `io.legado.app`
- **Compile SDK**: 36 / **Min SDK**: 21 / **Target SDK**: 36
- **Java target**: 17
- **Versioning**: `versionCode = 10000 + gitCommitCount`, `versionName = 3.<yy.MMddHH>`
- **Build variants**: debug (`.debug` suffix) and release (`.release` suffix). Debug applicationId is `io.legado.app.debug`.
- **ProGuard**: enabled for release (`minifyEnabled` + `shrinkResources`). Rules in `proguard-rules.pro` and `cronet-proguard-rules.pro`.
- **Room schemas**: exported to `app/schemas/`.
- **Signing**: release signing requires `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` project properties.

## Technology Stack

| Purpose | Library / Approach |
|---------|--------------------|
| UI (legacy) | Android View system — ViewBinding, Fragment, ViewPager, RecyclerView, Material components |
| UI (E-Ink, new) | Jetpack Compose (Foundation / UI / Runtime only — **no Material3**), see E-Ink spec |
| Architecture (E-Ink, new) | MVVM + UDF with Compose, single Activity, screen-level `UiState` |
| Async | Kotlin Coroutines + `Coroutine` helper in `help/coroutine/` |
| Database | Room (KSP), single `AppDatabase` with 21 DAOs |
| JSON | Gson (`io.legado.app.utils.GSON`) |
| Preferences | SharedPreferences via `AppConfig` / `ReadBookConfig` / `LocalConfig` (not DataStore) |
| Network | OkHttp + Cronet |
| HTML / rule parsing | Jsoup + custom `analyzeRule/` engine + Mozilla Rhino JS engine (`:modules:rhino`) |
| Local book parsing | Custom parsers for epub/umd/txt/pdf/mobi (`:modules:book`, `model/localBook/`) |
| Image loading | Glide |
| EventBus | LiveEventBus |
| Chinese conversion | hutool + custom dict |

## Architecture

The app is **Legado (开源阅读)** — a free, open-source novel reader. The original codebase is a mature View-based application; a new E-Ink专用 version is being built alongside it under `io.legado.app.eink`.

### Module Structure

```
:app                    - Main application (View-based UI + all data/business/model code)
:modules:eink           - E-Ink Compose Design System library (theme/components/modifiers/refresh)
:modules:book           - epub/umd parsing library (me.ag2s)
:modules:rhino          - Mozilla Rhino JS engine wrapper (com.script)
```

### Package Structure (`app/src/main/java/io/legado/app/`)

```
io.legado.app/
  App.kt                         - Application entry; calls DefaultData.upVersion() on create
  constant/                      - AppConst, PreferKey, PageAnim, Theme, EventLog, AppLog
  data/                          - Room database + entities + DAOs (shared by all UI)
    AppDatabase.kt               - Single RoomDatabase; `val appDb by lazy { ... }`
    dao/                         - 21 DAOs (BookDao, BookSourceDao, ReplaceRuleDao, ...)
    entities/                    - 27 entities (Book, BookSource, BookGroup, RssSource, ...)
  help/                          - Business logic layer between UI and data
    config/                      - AppConfig (global prefs), ReadBookConfig, ThemeConfig, LocalConfig
    DefaultData.kt               - Default data import on version upgrade
    source/                      - SourceHelp, BookSourceExtensions
    book/                        - BookHelp, ContentHelp, ContentProcessor
    storage/                     - Backup, Restore, BackupAES
    coroutine/ http/ glide/ rhino/ update/ exoplayer/
  model/                         - Core reading/scraping models
    ReadBook.kt                  - Global reading state machine
    analyzeRule/                 - Rule parsing engine (AnalyzeRule, AnalyzeByJSoup/XPath/JSonPath/Regex)
    webBook/                     - Online book operations (WebBook, BookList, BookContent, SearchModel)
    localBook/                   - Local book parsing (LocalBook, EpubFile, TextFile, ...)
    rss/                         - RSS parsing
  base/                          - BaseActivity, BaseFragment, BaseViewModel, VMBaseActivity, adapters
  ui/                            - All screens (View-based, 40+ Activity/Fragment)
    main/                        - MainActivity (4 tabs: bookshelf/explore/rss/my)
    book/read/                   - Reading screen (core)
      page/ReadView.kt           - Page-turn controller (FrameLayout with 3 PageViews)
      page/delegate/             - Page animation strategies (cover/slide/simulation/scroll/noAnim)
      page/provider/             - Text layout engine (ChapterProvider, TextPageFactory, TextMeasure)
      page/entities/             - TextPage, TextChapter, TextParagraph, ...
    book/source/ book/search/ book/toc/ book/info/ book/manage/
    rss/ replace/ config/ association/ about/
  eink/                          - E-Ink专用 version (Compose, under construction)
    EinkMainActivity.kt          - Single Activity entry for E-Ink UI
    EinkApp.kt                   - Root Composable + screen state router
    navigation/EinkScreen.kt     - sealed interface for screen routing
    bookshelf/ reader/ toc/ search/ booksource/ settings/
```

### Key Patterns (Existing View code)

- **Activity + Fragment + ViewModel**: Each screen = `XxxActivity` + `XxxViewModel(appCtx)` + Adapter. ViewModels extend `BaseViewModel` (which extends `AndroidViewModel`).
- **Global state**: `ReadBook` is a global singleton holding the current reading context (book, chapter, page, config). `appDb` is a lazy singleton for the Room database. Both are accessible from anywhere in the `io.legado.app` package.
- **SharedPreferences-backed config**: `AppConfig`, `ReadBookConfig`, `LocalConfig` are all `SharedPreferences` wrappers (not DataStore). E-Ink mode is `AppConfig.isEInkMode` (driven by `themeMode == "3"`).
- **Page delegate strategy**: `ReadView.upPageAnim()` selects a `PageDelegate` subclass based on `PageAnim` constant. `NoAnimPageDelegate` (PageAnim=4) is the no-animation path already suitable for E-Ink.
- **Rule engine**: Book sources define scraping rules executed via `analyzeRule/` (JSoup/XPath/JSONPath/Regex) + Rhino JS. This is the heart of the app's content acquisition.

### Key Patterns (New E-Ink Compose code — UDF)

Architecture patterns reference `D:\Projects\AndroidProjects\JBusDriver` (a mature Compose UDF project). See `app/src/main/java/io/legado/app/eink/arch/` for conventions.

- **Single Activity**: `EinkMainActivity` hosts all Compose UI for the E-Ink version.
- **Route + Stateless Screen split**: Each screen = `XxxRoute` (ViewModel-aware, `collectAsStateWithLifecycle`, handles one-shot events) + `XxxScreen` (pure, receives `state` + `onXxx` callbacks, no ViewModel dependency — Preview/test friendly).
- **UiState flat boolean flags**: Use `data class XxxUiState(...)` with flat boolean flags (`isLoading`, `isRefreshing`, `isLoadingMore`) rather than sealed classes — E-Ink UI can simultaneously show content + background refresh. Derived properties use `val ... get()` (don't duplicate). `error: Int?` holds a string resource id (ViewModel never holds localized strings).
- **@Immutable UiModel**: Data models rendered by Compose are annotated `@Immutable` (via `eink/arch/EinkUiState.kt::EinkImmutable`) to help Compose stability inference and minimize recomposition.
- **StateReducer pure functions**: State transitions are `internal fun XxxUiState.applyXxx(...): XxxUiState` in `XxxStateReducers.kt` files — testable, no side effects.
- **One-shot events via SharedFlow**: Navigation, Snackbar, Toast go through `MutableSharedFlow<UserMessage>` (see `arch/UserMessage.kt`), never as boolean flags inside UiState. `UserMessage` carries `@StringRes` ids for i18n safety.
- **State hoisting**: E-Ink components are stateless; state lives in ViewModels. Only ephemeral UI-local state (dialog visibility, pager position) stays in `remember`/`rememberSaveable`.
- **No animation**: All E-Ink UI follows the E-Ink Design System spec — zero motion, no ripple, no shadow, static transitions only.
- **Data reuse**: E-Ink ViewModels directly use `appDb`, `ReadBook`, `ReadBookConfig`, `WebBook`, `TextPageFactory` — no data layer duplication.
- **Error rendering priority**: `when { isLoading && items.isEmpty() -> Loading; error != null && items.isEmpty() -> ErrorView; else -> Content }` — errors never clear already-loaded content (E-Ink redraws are expensive).

## E-Ink Design System

The E-Ink version follows `docs/E-Ink Android Design System & Compose UI Engineering Specification.md`. Core rules:

- **No Motion**: Zero animation. No `androidx.compose.animation.*`. Page transitions are immediate replacement (`when(screen)`).
- **No Material3**: Use only Compose Foundation / UI / Runtime. Components are custom `EInkXxx`.
- **No Ripple / No Shadow**: `LocalIndication provides NoIndication` at root. Visual hierarchy via spacing, typography, divider, border — never elevation.
- **Reading First**: The reader is the core. Fixed page, stable layout, tap/swipe to page (immediate, no animation).
- **Refresh awareness**: Compose recomposition ≠ screen refresh. A `RefreshController` abstraction mediates UI state changes and hardware refresh (NONE/PARTIAL/FULL).
- **Component naming**: All components use `EInkXxx` prefix (`EInkButton`, `EInkText`, `EInkDialog`, ...).

Refer to `D:\Projects\AndroidProjects\eink-compose` for reference implementations of theme, modifiers, and components that were adapted into `:modules:eink`.

## Code Quality Rules

- ViewModels must not expose callbacks to the UI; expose state as `StateFlow<UiState>` and one-shot events as `SharedFlow`/`Channel`.
- Follow Unidirectional Data Flow (UDF) in new Compose screens: screen-level state is a single immutable `UiState` data class exposed via `StateFlow`; UI renders `UI = f(state)` while expressing intents through ViewModel methods.
- Keep only ephemeral UI-local state in composables via `remember`/`rememberSaveable` (e.g. dialog visibility, pager position). State that survives navigation or drives business logic belongs in the ViewModel.
- Data-layer `suspend` functions should be main-safe (Room, OkHttp, appDb are already main-safe). Call from `viewModelScope.launch` on Main without extra `withContext`.
- After any Gson or ProGuard/R8 changes, verify debug and release behavior for representative JSON payloads (book sources, backups).
- When removing or renaming a serialized data field, add backward-compatible `@SerializedName` aliases where Gson compatibility matters (backup import/export, book source JSON).
- ProGuard/R8 keep rules must cover all Gson model classes; add `@Keep` or rules proactively for entities and rule-related data classes.
- When refactoring multiple files, keep changes minimal and targeted; do not redesign UI the user did not ask for.

## Data Flow

1. **Content acquisition**: Book sources (`BookSource`) define rules. `WebBook` → `analyzeRule/` engine fetches and parses web pages (or local files via `localBook/`) into `Book` + `BookChapter` + content.
2. **Layout**: `TextPageFactory` + `ChapterProvider` paginate chapter content into `TextPage` objects sized to the display.
3. **Rendering**: `ReadView` manages prev/cur/next `PageView`, each rendering a `TextPage` via `ContentTextView`. The active `PageDelegate` controls page-turn animation (or none).
4. **Persistence**: `appDb` stores books, chapters, bookmarks, sources, rules, search history, cache. `ReadBookConfig` persists reading preferences. `Backup`/`Restore` handle full data export/import (Gson JSON, AES-encrypted).

## Global State

- `appDb`: Lazy singleton Room database (`io.legado.app.data.appDb`). Accessible from anywhere in `io.legado.app.*`.
- `ReadBook`: Global reading state machine (`io.legado.app.model.ReadBook`). Holds current book, chapter, page index, and reading config during an active reading session.
- `AppConfig`: SharedPreferences-backed global app configuration (`io.legado.app.help.config.AppConfig`).
- `ReadBookConfig`: Reading-specific configuration with E-Ink variants (`textColorEInk`, `bgStrEInk`, `pageAnimEInk`, etc.).
- `DefaultData`: Imports default book sources, TTS, rules, and books on first launch / version upgrade.

## Testing

```bash
# Run unit tests
./gradlew test

# Run Android instrumented tests
./gradlew connectedAndroidTest
```

Test files are in `app/src/test/` (unit) and `app/src/androidTest/` (instrumented). Connected tests require a running emulator or device.

## Code Review Notes

- The original Legado codebase is upstream `gedoor/legado`. Changes here should remain compatible with upstream merge where feasible unless intentionally diverging for the E-Ink version.
- The E-Ink migration must not break existing View-based UI. New Compose code is additive under `io.legado.app.eink` / `:modules:eink`.
- Large files to be aware of: `ReadBook.kt`, `ReadView.kt`, `ContentTextView.kt`, `AppDatabase.kt`, `BookSource.kt`. Prefer small extraction when touching these.
- The `ui/book/read/page/delegate/` directory is the page-animation strategy layer — `NoAnimPageDelegate` is the E-Ink-relevant path.
- Default data files live in `app/src/main/assets/defaultData/`. These are imported by `DefaultData.kt` on version upgrades. The `legado` tag in `bookSourceGroup` / `customTag` marks default-imported data for clean re-import.
