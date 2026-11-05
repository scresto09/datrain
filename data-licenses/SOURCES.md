# Timetable file sources

Each file is published under the licence of its source. The two are never mixed in one file: the app combines them on the user's phone.

| File | Contains information from | Licence |
|---|---|---|
| `sncf.bin.gz` | [Horaires SNCF](https://data.sncf.com/explore/dataset/horaires-sncf/), SNCF Voyageurs | [ODbL 1.0](ODbL-1.0.txt) |
| `idfm.bin.gz` | [Horaires prévus sur les lignes de transport en commun d'Île-de-France (GTFS Datahub)](https://data.iledefrance-mobilites.fr/explore/dataset/offre-horaires-tc-gtfs-idfm/), Île-de-France Mobilités | [Licence Mobilités](https://cloud.fabmob.io/s/eYWWJBdM3fQiFNm) |

## Changes

`tools/build_timetable.py` does all of them:

- `sncf.bin.gz`: all trains and coaches, with stops grouped by station.
- `idfm.bin.gz`: `route_type` 2 lines only (RER, Transilien, TER), with stops grouped by station and official walking times between stations.
- Both: converted to the compact format described in `app/src/main/java/com/scresto/datrain/data/RailTimetable.kt`.

`corrections.json` links two names of one station when they cannot be matched automatically.

Files are provided as is. Datrain is independent from SNCF and Île-de-France Mobilités.
