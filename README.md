# Datrain

Android app to search trains in France, Île-de-France included: connections, delays and estimated train position on a map. No account or API key.

## How it works

1. Every evening, `.github/workflows/timetables.yml` runs `tools/build_timetable.py`, which reduces the SNCF and IDFM timetables to two small files published in the `timetables` release.
2. The app reads `manifest.json` from GitHub Pages, downloads only the changed files, combines them and searches on the phone, offline included.
3. Real-time data comes from [Train Nomade](https://www.data.gouv.fr/dataservices/api-train-nomade-departs-et-arrivees-des-gares-sncf-temps-reel-et-horaires-prevus) and the SNCF GTFS-RT feeds, only when a journey needs it.

## Development

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

To try the app without GitHub:

```sh
python3 tools/build_timetable.py --sncf SNCF_GTFS_DIR --idfm IDFM_GTFS_DIR --out publication --base-url http://localhost:8765
(cd publication && python3 -m http.server 8765 --bind 127.0.0.1) &
adb reverse tcp:8765 tcp:8765
./gradlew :app:installDebug -PdatrainManifestUrl=http://localhost:8765/manifest.json
```

Add `-PdatrainMeasure=true` to measure speeds: the normal debug build is much slower.

## Licences

The code is under GPL 3. Data has its own licences, see [data-licenses/SOURCES.md](data-licenses/SOURCES.md).
