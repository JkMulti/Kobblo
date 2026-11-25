#!/usr/bin/env python3
"""Builds app/src/main/assets/places.bin, the offline table behind the gallery's Places.

Input is GeoNames' cities15000.zip (https://download.geonames.org/export/dump/), which is CC BY 4.0, so the app
says where the names come from on the Places screen and in app/src/main/assets/legal/notices.txt. Run it by hand when the table
should be refreshed: nothing in the build needs this script or the network. A new table changes what a stored place
number means, so raise PlaceTable.Version along with it.

    python3 scripts/places/build_places.py cities15000.zip app/src/main/assets/places.bin

The file is columns rather than rows because that compresses far better inside the APK. All big-endian:

    "PLC1"  magic
    int32   how many places
    int16[] latitude  * 100, ascending, which is what the lookup's binary search relies on
    int16[] longitude * 100
    uint8[] how far the place reaches, in half kilometres
    2 ASCII bytes[] country code
    then, for each place, a uint8 length and that many bytes of UTF-8 name
"""
import math
import struct
import sys
import zipfile

# A section of a city, which GeoNames lists as well as the city. Paris should be Paris, not its arrondissements.
SKIPPED_FEATURES = {"PPLX", "PPLH", "PPLQ", "PPLW"}


def reach_in_half_km(population):
    """A bigger city claims photos further from its centre, so a suburb doesn't take the middle of town."""
    km = 0.012 * math.sqrt(max(population, 1))
    return min(255, round(km * 2))


def main(zip_path, out_path):
    rows = []
    with zipfile.ZipFile(zip_path) as archive:
        with archive.open("cities15000.txt") as source:
            for raw in source:
                f = raw.decode("utf-8").rstrip("\n").split("\t")
                name, lat, lon, feature, country, population = f[1], float(f[4]), float(f[5]), f[7], f[8], int(f[14] or 0)
                if feature in SKIPPED_FEATURES or len(country) != 2:
                    continue
                encoded = name.encode("utf-8")
                if not encoded or len(encoded) > 255:
                    continue
                rows.append((round(lat * 100), round(lon * 100), reach_in_half_km(population), country.encode("ascii"), encoded))
    rows.sort(key=lambda r: (r[0], r[1]))
    with open(out_path, "wb") as out:
        out.write(b"PLC1")
        out.write(struct.pack(">i", len(rows)))
        out.write(struct.pack(f">{len(rows)}h", *[r[0] for r in rows]))
        out.write(struct.pack(f">{len(rows)}h", *[r[1] for r in rows]))
        out.write(bytes(r[2] for r in rows))
        for r in rows:
            out.write(r[3])
        for r in rows:
            out.write(bytes([len(r[4])]))
            out.write(r[4])
    print(len(rows), "places")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
