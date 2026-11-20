# AI Repair Mission — TestPlugins

> **Mandatory first read:** [AI_PROJECT_REFERENCE_AR.md](./AI_PROJECT_REFERENCE_AR.md). Treat it as the permanent project memory and source of truth for repository layout, plugin status, Arabic subtitle requirements, references, release checks, and historical bugs. Update it when facts change.

> Primary goal: make Viu display real catalog items and make AhwakTV open playable movie/series pages and resolve streams, without breaking QFilm.
> Repository: https://github.com/elhaoy8474-png/TestPlugins
> Source branch: master
> Published branch: builds
> Build workflow: .github/workflows/build.yml

## 0. Mandatory working rules

1. Work directly in this repository. Read the complete source, Gradle configuration, workflow, README/repository metadata, and recent commit/Actions history before editing.
2. Do not assume a successful Kotlin build means a provider works at runtime. Separate compile validation from provider/API/parser/playback validation.
3. Do not fabricate API responses, endpoints, IDs, stream URLs, test results, or success claims. Verify endpoints against current live site behavior and inspect actual response status, headers, content type, and JSON/HTML shape.
4. Do not silently swallow every exception during development. Add useful, privacy-safe diagnostics and handle errors visibly in logs. Never log tokens, cookies, credentials, or full authorization headers.
5. Preserve unrelated working behavior. **QFilm is currently working and must remain unchanged unless a proven shared build/configuration issue requires a minimal fix.** QFilm is movies-only by design.
6. Do not remove AhwakTV features or rewrite it wholesale. Make the smallest evidence-based fix.
7. Use lawful public site functionality only. Do not bypass paywalls, subscriptions, DRM, authentication, geographic restrictions, CAPTCHA, or other access controls. If content requires an account/subscription, report that limitation.
8. Keep changes isolated and understandable. Prefer separate commits for Viu and AhwakTV. Update plugin versions as required by this repository's release process.
9. Never claim playback is verified unless a real playable stream was obtained and the player path was validated. If you cannot run the Android app, state exactly what was and was not tested.
10. Continue through fix → build → inspect logs → fix until the build is green, with a maximum of 8 focused repair iterations. If blocked by missing credentials, app-only testing, region restrictions, or unavailable site responses, stop and provide exact evidence and the remaining manual test.

## 1. Current user-reported symptoms

- QFilm: working. Keep it working; do not alter its source behavior.
- AhwakTV: catalog/search may appear, but tapping a movie or series does not open/play correctly. User reports this regression.
- Viu: plugin installs/builds but displays no catalog content at all.
- Earlier generated Viu implementation was based on assumed web API paths and JSON shapes that have **not** been proven against current live responses. Treat those assumptions as unverified, not as facts.

## 2. Required repository reconnaissance

Inspect at minimum:

- `AhwakTV/src/main/kotlin/**`
- `AhwakTV/build.gradle.kts`
- `Viu/src/main/kotlin/**`
- `Viu/build.gradle.kts`
- `QFilm/src/main/kotlin/**`
- `QFilm/build.gradle.kts`
- `.github/workflows/build.yml`
- repository plugin manifests / generated metadata / versioning scripts
- recent commits and failed or cancelled GitHub Actions logs

Also compare the existing working QFilm implementation and the original/reference plugins supplied by the repository owner:

- https://github.com/mo7AmMeD64/cs-ar
- https://github.com/mo7AmMeD64/ar_aniyomi
- reference repo metadata: https://raw.githubusercontent.com/mo7AmMeD64/cs-ar/main/repo/repo.json

Search those repositories for comparable provider implementations, episode parsing, detail loading, iframe/server extraction, Cloudflare handling, M3U8 handling, request headers, and Viu implementations. Inspect full source files and commit history where useful. Do not copy code blindly; verify compatibility with this repository's CloudStream API version and current target websites.

## 3. AhwakTV investigation and acceptance criteria

Trace the complete user action from a listed SearchResponse/HomePage item through:

1. the URL stored in the SearchResponse;
2. `load(url)` request and HTML response;
3. detection of movie vs series vs episode;
4. season/episode parsing and episode ordering;
5. the URL stored in each Episode;
6. `loadLinks(data, ...)` request;
7. iframe/player/server selection and extractor delegation;
8. returned ExtractorLinks, referers, headers, and subtitle callbacks.

Investigate why tapping a movie or series fails. Do not guess selectors. Fetch representative current pages from the real site and inspect their DOM, redirects, HTTP status, canonical URL, and embedded player markup. Check whether a series detail page redirects to a series landing page or whether the actual playable page is an episode page. Ensure `load()` returns the correct LoadResponse and that each episode's data is the exact URL expected by `loadLinks()`.

Use robust URL resolution for relative, protocol-relative, and absolute links. Handle lazy-loaded images and alternate markup only when observed. For player pages, inspect all actual server tabs/iframes and delegate supported providers to CloudStream's `loadExtractor` where appropriate. If a custom extractor is necessary, implement it only for an observed format and add a clear fallback. Preserve referer requirements.

AhwakTV acceptance:
- [ ] movie item opens a Movie detail page;
- [ ] series item opens a TvSeries detail page with episodes;
- [ ] episode order is numeric and stable (1, 2, 3 ...; no duplicates);
- [ ] tapping an episode returns at least one valid playable link for a genuinely free/public test episode, or a precise documented explanation of a site-side restriction;
- [ ] no regression to catalog/search/posters;
- [ ] no Kotlin compile errors.

## 4. Viu investigation and acceptance criteria

The current Viu source contains guessed endpoints and broadly swallowed exceptions. Audit every request and response; do not assume the API routes or response schema are still valid.

First inspect the live Viu Egypt website in a normal browser-like request flow:
- landing page and region/language selection;
- page scripts/configuration and network/API requests referenced by the current site;
- catalog/home feed;
- search;
- product/detail page;
- series/episode list;
- playback metadata for a free/public item, if available.

Use current official web endpoints discovered from the site itself or reliable maintained open-source reference implementations. Check HTTP status, redirects, response content type, anti-bot responses, required headers, cookies, locale/area IDs, and actual JSON nesting. Do not invent token fields or IDs. If the API requires session initialization, reproduce the legitimate public web flow and persist only the minimum session state needed for requests.

Important areas to audit in current implementation:
- `loadSiteSettings()` must not silently retain incorrect defaults when settings fail.
- `token()` must parse the actual current token response and report safe diagnostic reasons.
- `getMainPage()` must use the actual current catalog endpoint and actual JSON structure; do not return an empty page while hiding the cause.
- `search()` must send the actual expected request body/headers and parse the real response.
- `itemResponse()` must correctly distinguish series and movies and use valid product IDs and image fields.
- `productData()` / `load()` must use the correct detail identifiers and episode structure.
- `loadLinks()` must use the current playback request contract and actual stream response structure. Respect subscription/DRM/region restrictions.
- Ensure plugin registration and metadata are correct and that the provider is actually included in the generated repository index.

Viu acceptance:
- [ ] provider appears in the installed repository and is selectable;
- [ ] home page displays real Viu Egypt catalog items;
- [ ] search returns real matching items;
- [ ] a detail page opens for a real movie;
- [ ] a series detail page exposes correctly ordered episodes;
- [ ] a free/public item provides a playable stream when the official service allows it;
- [ ] captions/subtitles are exposed when present in the official response;
- [ ] errors are diagnosable without leaking secrets;
- [ ] no Kotlin compile errors.

If Viu Egypt requires login or subscription for playback, catalog visibility should still work where publicly available; document the limitation rather than bypassing it.

## 5. Build, test, publish, repeat

After each focused change:

1. Run the repository's actual Gradle build workflow or equivalent local build.
2. Inspect the full failed job log, not just the red status.
3. Fix the specific root cause; do not apply unrelated speculative edits.
4. Re-run the build.
5. Verify generated `.cs3` artifacts and generated `repo.json` / `plugins.json` on the `builds` branch.
6. Confirm Viu is listed in plugin metadata and that artifact filename, version, and file size/hash match the generated files.
7. Confirm QFilm artifact is still present and its source behavior has not been changed.
8. Repeat focused repairs until green or the 8-iteration limit is reached.

A green build only proves compilation/packaging. Runtime acceptance checks above must be reported separately. Do not mark the mission complete merely because Actions succeeded.

## 6. Final report required

At completion, provide:
- root cause for Viu empty catalog;
- root cause for AhwakTV detail/playback failure;
- exact files changed and concise explanation;
- commits/PR links;
- latest Actions run URL and conclusion;
- published artifact links from the `builds` branch;
- checklist showing each acceptance criterion as PASS / FAIL / NOT TESTED;
- any remaining app-only or account/region tests the repository owner must perform.

Do not overwrite QFilm or change its behavior. If an acceptance criterion cannot be verified, label it NOT TESTED and explain why.
