# Timetable file sources

Each file is published under the licence of its source. The two are never mixed in one file: the app combines them on the user's phone.

| File | Contains information from | Licence |
|---|---|---|
| `sncf.bin.gz` | [Horaires SNCF](https://data.sncf.com/explore/dataset/horaires-sncf/), SNCF Voyageurs | [ODbL 1.0](ODbL-1.0.txt) |
| `idfm.bin.gz` | [Horaires prévus sur les lignes de transport en commun d'Île-de-France (GTFS Datahub)](https://data.iledefrance-mobilites.fr/explore/dataset/offre-horaires-tc-gtfs-idfm/), Île-de-France Mobilités | [Licence Mobilités](https://cloud.fabmob.io/s/eYWWJBdM3fQiFNm) |
| `idfm.bin.gz` (station UIC codes) | [Référentiel des arrêts : Arrêts](https://data.iledefrance-mobilites.fr/explore/dataset/arrets/) and [Arrêts transporteur](https://data.iledefrance-mobilites.fr/explore/dataset/arrets-transporteur/), Île-de-France Mobilités | [Licence Ouverte 2.0](https://www.etalab.gouv.fr/licence-ouverte-open-licence/) |

## Changes

`tools/build_timetable.py` does all of them:

- `sncf.bin.gz`: all trains and coaches, with stops grouped by station.
- `idfm.bin.gz`: `route_type` 2 lines only (RER, Transilien, TER), with stops grouped by station and official walking times between stations. Each station gets the UIC code of its SNCF stop from the referential, with its check digit added.
- Both: converted to the compact format described at the top of `tools/build_timetable.py`.

`corrections.json` links two names of one station when they cannot be matched automatically.

## Read by the app

Real-time data is read directly by the app on the phone. It is neither stored in this repository nor redistributed.

| Data | Source | Licence |
|---|---|---|
| Delays, platforms, cancellations, skipped stops; railway tracks and stations for the map | SNCF data via [Train Nomade](https://www.data.gouv.fr/dataservices/api-train-nomade-departs-et-arrivees-des-gares-sncf-temps-reel-et-horaires-prevus), an independent third-party service ([documentation](https://trainnomade.fr/api/)) | ODbL 1.0 |
| Service alerts, and trip updates for trains Train Nomade lacks | SNCF GTFS-RT feeds, from the [national access point](https://transport.data.gouv.fr/datasets/horaires-sncf) | ODbL 1.0 |
| Map background | [OpenFreeMap](https://openfreemap.org/) / OpenMapTiles, from [OpenStreetMap](https://www.openstreetmap.org/copyright) data | ODbL 1.0 |

Files are provided as is. Datrain is independent from SNCF, Île-de-France Mobilités and Train Nomade.
