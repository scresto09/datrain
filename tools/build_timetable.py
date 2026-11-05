#!/usr/bin/env python3
"""Build the compact timetable files that the app downloads.

Usage: build_timetable.py --sncf DIR --idfm DIR --out DIR --base-url URL

Each source gives one file under its own licence: sncf.bin.gz (ODbL) and idfm.bin.gz
(Licence Mobilités). The two are never mixed here: the app combines them on the phone.
Kept: SNCF trains and coaches, plus IDFM route_type 2 (RER, Transilien, TER).

The app reads it in app/src/main/java/com/scresto/datrain/data/RailTimetable.kt.
File format, gzip-compressed and big-endian: "DTRN", version:int, first day:int (yyyymmdd), days:short,
then strings, stations (name, lat:float, lon:float, uic:int), walks (from:int, to:int, seconds:short),
calendars (one bit per day), patterns (size:short, then station:int and flags:byte per stop,
1 = boarding allowed, 2 = alighting allowed), and trips as varints (pattern, calendar, number,
commercial name, mode:byte, first departure, then for each next stop: travel seconds, dwell seconds).
"""
import argparse
import csv
import gzip
import hashlib
import io
import json
import os
import re
import shutil
import struct
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone

MAGIC = b"DTRN"
VERSION = 2
MANIFEST_FORMAT = 1
MODE_TRAIN, MODE_BUS, MODE_SUBURBAN = 0, 1, 2
# A publication with far fewer trains means a broken source: better keep yesterday's files.
MIN_SNCF_TRIPS = 20000
MIN_IDFM_TRIPS = 10000
COMMERCIAL_NAMES = {"Train TER": "TER", "INTERCITES": "Intercités", "INTERCITES de nuit": "Intercités de nuit"}


def rows(folder, name):
    with open(os.path.join(folder, name), encoding="utf-8-sig", newline="") as f:
        yield from csv.DictReader(f)


def seconds(text):
    h, m, s = text.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def parse_day(text):
    return date(int(text[:4]), int(text[4:6]), int(text[6:8]))


class Builder:
    def __init__(self):
        self.stations = []  # name, lat, lon, uic
        self.by_key = {}
        self.strings = {}
        self.transfers = {}

    def station(self, key, name, lat, lon, uic):
        index = self.by_key.get(key)
        if index is None:
            index = len(self.stations)
            self.stations.append((name, lat, lon, uic))
            self.by_key[key] = index
        return index

    def string(self, text):
        return self.strings.setdefault(text, len(self.strings))


def sncf_active_days(folder):
    days = defaultdict(set)
    for r in rows(folder, "calendar_dates.txt"):
        if r["exception_type"] == "1":
            days[r["service_id"]].add(parse_day(r["date"]))
    return days


def idfm_active_days(folder):
    days = defaultdict(set)
    names = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    for r in rows(folder, "calendar.txt"):
        day, end = parse_day(r["start_date"]), parse_day(r["end_date"])
        while day <= end:
            if r[names[day.weekday()]] == "1":
                days[r["service_id"]].add(day)
            day += timedelta(days=1)
    for r in rows(folder, "calendar_dates.txt"):
        day = parse_day(r["date"])
        if r["exception_type"] == "1":
            days[r["service_id"]].add(day)
        else:
            days[r["service_id"]].discard(day)
    return days


def stop_rows(folder, wanted):
    """trip -> [(sequence, stop, arrival, departure, can_board, can_alight)], reading a huge file once."""
    result = defaultdict(list)
    for r in rows(folder, "stop_times.txt"):
        if r["trip_id"] in wanted:
            result[r["trip_id"]].append((
                int(r["stop_sequence"]), r["stop_id"], seconds(r["arrival_time"]), seconds(r["departure_time"]),
                r["pickup_type"] != "1", r["drop_off_type"] != "1"))
    return result


def commercial_name(stop_id, route):
    match = re.match(r"StopPoint:OCE(.+)-[0-9A-Z]+$", stop_id)
    name = match.group(1) if match else route["route_short_name"] or route["route_long_name"]
    return COMMERCIAL_NAMES.get(name, name)


def load_sncf(b, folder):
    route_info = {r["route_id"]: r for r in rows(folder, "routes.txt")}
    parents = {}
    info = {}
    for r in rows(folder, "stops.txt"):
        parents[r["stop_id"]] = r["parent_station"] or r["stop_id"]
        info[r["stop_id"]] = r
    active = sncf_active_days(folder)
    trips = {r["trip_id"]: r for r in rows(folder, "trips.txt")}
    times = stop_rows(folder, trips)
    kept = []
    for trip_id, stops in times.items():
        t = trips[trip_id]
        days = active.get(t["service_id"])
        if not days or len(stops) < 2:
            continue
        route = route_info[t["route_id"]]
        mode = MODE_BUS if route["route_type"] == "3" else MODE_TRAIN
        ordered = sorted(stops)
        points = []
        for seq, stop, arr, dep, board, alight in ordered:
            area = parents[stop]
            s = info[area]
            match = re.search(r"(\d{8})$", area)
            uic = int(match.group(1)) if match else 0
            points.append((b.station(area, s["stop_name"], float(s["stop_lat"]), float(s["stop_lon"]), uic), arr, dep, board, alight))
        kept.append((t["trip_headsign"], commercial_name(ordered[0][1], route), mode, days, points))
    return kept


def load_idfm(b, folder):
    agencies = {r["agency_id"]: r["agency_name"] for r in rows(folder, "agency.txt")}
    route_info = {r["route_id"]: r for r in rows(folder, "routes.txt") if r["route_type"] == "2"}
    trips = {r["trip_id"]: r for r in rows(folder, "trips.txt") if r["route_id"] in route_info}
    active = idfm_active_days(folder)
    stops = {r["stop_id"]: r for r in rows(folder, "stops.txt")}
    times = stop_rows(folder, trips)
    kept = []
    for trip_id, points in times.items():
        t = trips[trip_id]
        days = active.get(t["service_id"])
        if not days or len(points) < 2:
            continue
        route = route_info[t["route_id"]]
        short = route["route_short_name"]
        agency = agencies.get(route["agency_id"], "")
        label = "TER" if short == "TER" else f"{agency} {short}".strip()
        out = []
        for seq, stop, arr, dep, board, alight in sorted(points):
            platform = stops[stop]
            area = platform["parent_station"] or stop
            source = stops.get(area, platform)
            index = b.station(area, source["stop_name"], float(source["stop_lat"]), float(source["stop_lon"]), 0)
            out.append((index, arr, dep, board, alight))
        kept.append((t["trip_short_name"], label, MODE_SUBURBAN, days, out))

    # Official walking times between two stations of the network.
    for r in rows(folder, "transfers.txt"):
        if r.get("transfer_type") != "2" or not r.get("min_transfer_time"):
            continue
        a, c = stops.get(r["from_stop_id"]), stops.get(r["to_stop_id"])
        if not a or not c:
            continue
        x = b.by_key.get(a["parent_station"] or r["from_stop_id"])
        y = b.by_key.get(c["parent_station"] or r["to_stop_id"])
        if x is not None and y is not None and x != y:
            b.transfers[(x, y)] = int(r["min_transfer_time"])
    return kept


def varint(out, value):
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)


def write(b, trips, target):
    first = min(min(t[3]) for t in trips)
    last = max(max(t[3]) for t in trips)
    span = (last - first).days + 1

    service_ids = {}
    patterns = {}
    encoded = []
    for number, label, mode, days, points in trips:
        key = tuple((p[0], p[3], p[4]) for p in points)
        pattern = patterns.setdefault(key, len(patterns))
        service = service_ids.setdefault(frozenset(days), len(service_ids))
        encoded.append((pattern, service, b.string(number), b.string(label), mode, points))
    # Trips of one pattern sit together and in time order, which also compresses better.
    encoded.sort(key=lambda e: (e[0], e[5][0][2]))

    out = io.BytesIO()
    out.write(MAGIC)
    out.write(struct.pack(">IIH", VERSION, int(first.strftime("%Y%m%d")), span))

    strings = sorted(b.strings, key=b.strings.get)
    out.write(struct.pack(">I", len(strings)))
    for text in strings:
        raw = text.encode("utf-8")
        out.write(struct.pack(">H", len(raw)))
        out.write(raw)

    out.write(struct.pack(">I", len(b.stations)))
    for name, lat, lon, uic in b.stations:
        raw = name.encode("utf-8")
        out.write(struct.pack(">H", len(raw)))
        out.write(raw)
        out.write(struct.pack(">ffI", lat, lon, uic))

    out.write(struct.pack(">I", len(b.transfers)))
    for (x, y), walk in sorted(b.transfers.items()):
        out.write(struct.pack(">IIH", x, y, min(walk, 65535)))

    out.write(struct.pack(">I", len(service_ids)))
    width = (span + 7) // 8
    for days, _ in sorted(service_ids.items(), key=lambda kv: kv[1]):
        bits = bytearray(width)
        for day in days:
            i = (day - first).days
            bits[i // 8] |= 1 << (i % 8)
        out.write(bits)

    out.write(struct.pack(">I", len(patterns)))
    for key, _ in sorted(patterns.items(), key=lambda kv: kv[1]):
        out.write(struct.pack(">H", len(key)))
        for station, board, alight in key:
            out.write(struct.pack(">IB", station, (1 if board else 0) | (2 if alight else 0)))

    out.write(struct.pack(">I", len(encoded)))
    for pattern, service, number, label, mode, points in encoded:
        data = bytearray()
        varint(data, pattern)
        varint(data, service)
        varint(data, number)
        varint(data, label)
        data.append(mode)
        varint(data, points[0][2])  # first departure
        previous = points[0][2]
        for _, arr, dep, _, _ in points[1:]:
            varint(data, max(0, arr - previous))
            varint(data, max(0, dep - arr))
            previous = dep
        out.write(data)

    with gzip.GzipFile(target, "wb", compresslevel=9, mtime=0) as f:
        f.write(out.getvalue())
    print(f"{os.path.basename(target)}: {first} -> {last}, {len(b.stations)} stations, {len(encoded)} trains, "
          f"{os.path.getsize(target) / 1e6:.2f} MB")
    return last


def file_entry(path, base_url):
    data = open(path, "rb").read()
    name = os.path.basename(path)
    return {"url": f"{base_url.rstrip('/')}/{name}", "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sncf", required=True)
    parser.add_argument("--idfm", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    sources = (
        ("sncf", args.sncf, load_sncf, MIN_SNCF_TRIPS),
        ("idfm", args.idfm, load_idfm, MIN_IDFM_TRIPS),
    )
    manifest = {"format": MANIFEST_FORMAT, "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), "files": {}}
    for name, folder, load, minimum in sources:
        builder = Builder()
        trips = load(builder, folder)
        if len(trips) < minimum:
            sys.exit(f"{name}: only {len(trips)} trains, publication cancelled")
        target = os.path.join(args.out, f"{name}.bin.gz")
        write(builder, trips, target)
        manifest["files"][name] = file_entry(target, args.base_url)

    corrections = os.path.join(args.out, "corrections.json")
    shutil.copyfile(os.path.join(os.path.dirname(os.path.abspath(__file__)), "corrections.json"), corrections)
    manifest["files"]["corrections"] = file_entry(corrections, args.base_url)

    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)


if __name__ == "__main__":
    main()
