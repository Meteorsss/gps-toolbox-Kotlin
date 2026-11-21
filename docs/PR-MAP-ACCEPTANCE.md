# Map and track insights: manual acceptance

- This feature remains in an unmerged PR. Repository stays public; no user coordinates or screenshots committed.
- Upgrade signed preview over 0.1.2; verify old tracks and places remain. Export important data before upgrade. Do not downgrade database v2 to v1.
- Decline online consent: local map shows track and location without roads. Other pages continue functioning.
- Allow online: pan/zoom on mobile data and Wi-Fi; check fonts, attribution, loaded streets, and coordinate alignment in your actual area. WGS84 positions are never transformed to GCJ-02 for this basemap.
- Walk in an open area; start map recording, drag to stop following, recenter, pause, resume, finish, inspect full track and gaps. Leave map/app and turn screen off; recording must continue via foreground service.
- Enable airplane mode mid-recording: GPS and recording should continue; map may use cached tiles or show load error. Restore network and retry.
- Check old track altitude metrics show unknown when vertical accuracy unavailable. Flat-ground walk should not create large climb; hill ascent/descent should be treated as filtered GNSS estimates, not ground truth.
- Check MSL vs ellipsoid label, current speed vs overall/moving averages, highest speed and raw point validity. Unknown time must not be silently counted as stationary.
- Verify location permission revocation, orientation change, background/foreground and closing the map do not crash or restart recording.

- While recording, leave map detailed statistics open; verify values refresh without reopening, including after returning from background and finishing recording.
- From a historical track, recenter to current location; rotate or change basemap, and verify live-follow mode remains selected without auto-fitting the old track again.

## Cloud verification and preview delivery (2026-11-21)

- Signed preview build #12: https://github.com/Meteorsss/gps-toolbox/actions/runs/44849840678
- APK source commit: `cfe21c2a6cd1550f76aa4d90f60e301f77b1edf1`.
- 71 unit tests, 13 Android 16 instrumentation tests, release lint and APK signature/permission verification passed. No tests failed or were skipped.
- Regressions include live map statistics updating while open, pause/background/resume and final persisted statistics, recentered live mode across recreation, and retained history-map camera zoom.
- Artifact: `StarTrace-PR-preview-apk`; install `StarTrace-0.2.0-pr-build-12.apk`, not a PR debug APK. Existing release signing certificate is retained.
- APK SHA-256: `5a1cfe3ec5a3fd7199e16642c4e924ebbc0f82efe2507df7e2c010a017a15780`.
- All fixtures and screenshots use synthetic data. Cloud emulator results do not replace Redmi K70 / HyperOS 3 field acceptance, mobile-network availability or battery testing.
- PR stays open and unmerged, main unchanged, repository public, no new Release.

## ARM64 Pre-release requested by owner (2026-11-21)

- The owner explicitly requested publishing this version as a GitHub Pre-release, like the preceding three releases. The earlier no-new-Release restriction is superseded for this delivery only.
- Release APK includes only `arm64-v8a`; cloud verification checks every bundled native library is ELF64 AArch64, with matching APK badging and fixed signing certificate.
- Debug builds retain emulator-compatible ABIs for Android 16 tests and are not uploaded as Release assets.
- Publishing targets the verified feature-branch commit; the PR remains unmerged, main unchanged and repository public.

## KML export and centered speed (0.2.1-pr)

- Confirm the dashboard speed title and value/unit row are centered in both themes and after recreation.
- Finished-track actions and details include KML, GPX and CSV. Recording must be stopped before exporting.
- Save a KML through DocumentsUI, rotate while the picker is open, and verify `.kml`/MIME/selected track are retained. Cancel without writing or changing the source.
- Import a synthetic KML in Google Earth / My Maps and verify WGS84 alignment, separate sections, singleton points and no invented altitude. Do not upload real user tracks for testing.
- Google Earth and My Maps import limitations are documented in KML-IMPORT.md; no direct Google Maps app import or automatic cloud upload is claimed.

## 0.2.1-pr cloud verification (2026-11-21)

- Signed ARM64 build #18: https://github.com/Meteorsss/gps-toolbox/actions/runs/44864744525
- APK source: `3d8b2c5292edd8f3e7c0fb2be1fb75fd227a5378`; 82 unit tests and 15 Android 16 debug instrumentation tests passed without failures or skipped tests.
- KML tests cover XML/UTF-8 escaping, longitude-first coordinates, ground clamping, no invented altitude, singletons, pause/rejected/mock/gap/dateline breaks, full 6001-point export, MIME/filename, save after recreation, cancellation and unchanged source/GPX/CSV.
- Centered-speed layout assertions and cloud screenshots passed in both themes and after recreation.
- Actual synthetic KML produced through the save callback was downloaded and parsed: two LineString sections, one Point section, no mock coordinates. Google-account import and Redmi K70 field acceptance are not claimed.
- ARM64-only ELF64 AArch64 verification, existing release certificate continuity, versionCode=18 and APK SHA-256 verified.
- APK SHA-256: `67dcb077ee2c5d845a42b52133e6639f78ecd0c4098b36625f0f5c7e1d2797ce`.
- Owner requested another public Pre-release; PR remains unmerged and main unchanged.

## 0.2.2-pr manual acceptance

- Re-export KML and import in Earth: named start/end placemarks must match the first/last valid coordinates across gaps; a singleton has a combined marker; a closed loop may have overlapping start/end markers. Old exported files are not rewritten.
- Set each 1/2/3/5/10/15-second interval before recording and change mid-recording. Check saved point timestamps, pause/resume, no fabricated points, no bridge across rejected callbacks, export count and persistent preference. Longer intervals may undersample bends/peaks.
- Select DARK/LIGHT/SYSTEM, switch system appearance with main/map open, recreate and return from background. Manual modes stay fixed; system mode follows; recording service must not stop.
- Large speed view keeps current value centered above live max/average/distance. Pause freezes recording duration, new recording resets statistics, and stop/no recording shows unknown instead of stale previous values.

## 0.2.2-pr cloud verification (2026-11-21)

- Signed ARM64 build #21: https://github.com/Meteorsss/gps-toolbox/actions/runs/44876307681
- APK source `56999fc29605c9fc832a62eb263b8380b507004b`; 93 unit tests and 19 Android 16 debug instrumentation tests passed with no failures or skipped tests.
- KML synthetic file generated by the real save callback has explicit `track-start` and `track-end` placemarks at first/last valid WGS84 coordinates, with the original two line sections and singleton section intact. Empty/singleton/rejected-edge cases covered.
- Cadence tests cover all six intervals, monotonic timestamps, real production recording handler with persisted points, mid-recording cadence change, skipped poor-quality callback break and paused recording.
- Theme tests cover legacy migration, all three modes through recreation, real system night-mode changes, fixed manual modes and shared map theme.
- Large speed-view assertions cover current speed centering, max/average/distance, pause state, updates and clearing stale values without a recorder; screenshot uses synthetic measurements only.
- Downloaded APK SHA-256 `5b3c0c8cfba2fab4b21394ceffa17cd2d7e1ded0b78f8cf6ec7ea5ba2e89b9a6`; ARM64-only ELF64 AArch64 and fixed certificate matching previous releases verified; versionCode=21, versionName=0.2.2-pr.
- No actual user Google account import or Redmi K70 field validation claimed; old KML must be re-exported to gain endpoints. PR remains unmerged and main unchanged; owner requested a public Pre-release.

## Final 0.2.2-pr signing and theme-contrast verification

- Signed ARM64 build #26: https://github.com/Meteorsss/gps-toolbox/actions/runs/44880210016
- Source `1ddb647ffa02b98e055705a82ae2c01b73a38487`. Native manual light/dark dialogs and system-bar appearance now match the selected mode; regression awaits actual system configuration and a composed screenshot frame.
- 93 unit tests and 19 Android 16 instrumentation tests passed, zero failed/skipped; downloaded KML endpoints and screenshots checked. APK only contains ELF64 AArch64 native libraries and matches the previous release signing certificate.
- APK SHA-256 `1a146a33247b1e7052311e9142e3b6694c123dd79409e88e70e6bf59f2af8c6a`. This is the final 0.2.2-pr delivery; earlier #21/#24 builds are superseded, not published as new releases. User field/import testing remains required.

## 0.2.3-pr independent speedometer acceptance

- Enter large speed view with no track recording: it auto-measures valid foreground fixes, shows current km/h and m/s plus independent max/average/distance, and creates no DB track or foreground service.
- Back to tools or switch tabs: temporary values clear. Re-enter: begin at zero, never reuse old positions or a track's statistics. Leaving does not stop or alter any separately running track.
- Rotate/recreate/change theme while staying in speed view: preserve session. Background: pause foreground duration and break position continuity; return without bridging background movement.
- Duplicate/stale/mock/low-accuracy/outlier positions never fabricate distance or maximum. No valid fix still displays waiting, not a demand to start track recording.
- Release assets must be exactly StarTrace.apk and README.md. Reports stay in Actions; all code still goes through PR, main unchanged.

## 0.2.3-pr independent speed-session verification (2026-11-21)

- Signed ARM64 build #31: https://github.com/Meteorsss/gps-toolbox/actions/runs/44885311076; source `d9347bef70980b29a4a5828608f41376c6d0e6a9`.
- 107 unit tests and 23 Android 16 debug instrumentation tests passed with no failures or skipped tests. Four new device regressions use synthetic input through the production GNSS callback; the emulator platform provider is isolated in these fixtures, without bypassing validity/jump filters.
- Verified automatic max/average/distance without a track service or new database track; current km/h and m/s conversion; back/tab exit clearing; re-entry at zero; recreation/theme retention; foreground-only duration and no background movement bridge; mock/poor/duplicate/stale filtering.
- Downloaded screenshot confirms independent live statistics with no recorder active. No Redmi K70 field measurement is claimed.
- Signed APK versionName=0.2.3-pr, versionCode=31; native libraries only ELF64 AArch64; fixed certificate matches previous release; cloud and local APK SHA-256 `007a2f8ad13831e6e60d9ceb44c9973e04e6076dc879c18a75c3281406574f34` match.
- Publishable artifact contains exactly StarTrace.apk and README.md; only those two attachments may be uploaded. Reports/screenshots remain in Actions. Earlier failed fixture build #29 is not published.
- PR remains unmerged, main unchanged, repository public; release marked Pre-release.
