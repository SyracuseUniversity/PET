"""Pre-install scan: layer 3 of the D19 safeguard (D31).

Run it on every plugin APK before it is installed with adb:

    python -I tools\\preinstall_scan.py <file.apk> [<file.apk> ...]

The dex files in an APK are usually deflate-compressed, so a byte search of
the .apk file alone can miss a hostname. This scan opens the APK as a zip,
decompresses EVERY entry (and any zip or jar nested inside one), and byte-
searches each for the retired gateway hosts, ignoring case, as UTF-8 and as
UTF-16 in both byte orders (compiled XML and resources.arsc can hold UTF-16
strings). It also searches the raw .apk, and prints its SHA-256 so the copy
installed on the phone can be checked against the one scanned.

Exit 0: zero hits in every APK given. Exit 1: a hit, or an APK that could not
be read in full. Anything other than 0 means: do not install.

    python -I tools\\preinstall_scan.py --self-test

builds test archives in memory and checks that the scan finds the names in
compressed, stored, nested, UTF-16 and mixed-case entries, that a raw byte
search alone would have missed the compressed ones, and that a clean archive
passes.

Written for Python 3.11 or later, standard library only.
"""

import hashlib
import io
import sys
import zipfile

# Keep in step with checkForbiddenHosts in app/build.gradle (layer 1).
RETIRED_HOSTS = ("very-traceable.xyz", "witec.io")
ENCODINGS = {"utf-8": "UTF-8", "utf-16-le": "UTF-16LE", "utf-16-be": "UTF-16BE"}
MAX_NESTING = 3

PATTERNS = [(host, label, host.encode(enc))
            for host in RETIRED_HOSTS
            for enc, label in ENCODINGS.items()]


def search(data, where):
    """Return (where, offset, host, encoding) for every occurrence in data."""
    lowered = data.lower()  # lowercases ASCII A-Z only, so UTF-16 offsets hold
    hits = []
    for host, label, pattern in PATTERNS:
        i = lowered.find(pattern)
        while i >= 0:
            hits.append((where, i, host, label))
            i = lowered.find(pattern, i + 1)
    return hits


def scan_zip(data, where, stats, listing, depth=0):
    """Decompress every entry of the zip in data and search each one."""
    hits = []
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for info in zf.infolist():
            name = f"{where}!{info.filename}" if where else info.filename
            content = zf.read(info)  # decompressed; raises on a CRC mismatch
            method = "stored" if info.compress_type == zipfile.ZIP_STORED else "deflated"
            if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                method = f"method{info.compress_type}"
            stats["entries"] += 1
            stats[method] = stats.get(method, 0) + 1
            stats["bytes"] += len(content)
            listing.append((method, info.compress_size, len(content), name))
            hits += search(content, name)
            if depth < MAX_NESTING and zipfile.is_zipfile(io.BytesIO(content)):
                stats["nested"] += 1
                hits += scan_zip(content, name, stats, listing, depth + 1)
    return hits


def scan_bytes(raw):
    """Search the raw archive, then every decompressed entry inside it."""
    stats = {"entries": 0, "bytes": 0, "nested": 0}
    listing = []
    hits = search(raw, "(raw .apk)")
    hits += scan_zip(raw, "", stats, listing)
    return hits, stats, listing


def scan_apk(path):
    print(f"APK      {path}")
    try:
        with open(path, "rb") as f:
            raw = f.read()
        print(f"SHA-256  {hashlib.sha256(raw).hexdigest()}")
        print(f"Size     {len(raw):,} bytes")
        hits, stats, listing = scan_bytes(raw)
    except Exception as e:  # any failure to read it in full is a failure
        print(f"RESULT   FAIL - could not read the APK in full: {type(e).__name__}: {e}")
        return False

    print("Entries scanned (method, compressed -> decompressed bytes, name):")
    for method, packed, size, name in listing:
        print(f"  {method:8} {packed:>9,} -> {size:>9,}  {name}")
    methods = ", ".join(f"{stats[k]} {k}" for k in sorted(stats)
                        if k not in ("entries", "bytes", "nested"))
    print(f"Totals   {stats['entries']} entries ({methods}); "
          f"{stats['bytes']:,} bytes decompressed; {stats['nested']} nested archives")
    print(f"Searched for {', '.join(RETIRED_HOSTS)} as {', '.join(ENCODINGS.values())}, any case")
    if hits:
        print(f"HITS     {len(hits)}")
        for where, offset, host, label in hits:
            print(f"  {where} @ byte {offset}: {host} ({label})")
        print("RESULT   FAIL - do not install this APK.")
        return False
    print("RESULT   PASS - zero hits.")
    return True


def self_test():
    def make_zip(entries):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            for name, data, method in entries:
                zf.writestr(name, data, compress_type=method)
        return buf.getvalue()

    pad = b"\x00\x01\x02\x03" * 64  # gives deflate something to compress
    D, S = zipfile.ZIP_DEFLATED, zipfile.ZIP_STORED
    inner = make_zip([("a/B.class", pad + b"http://www.witec.io/x" + pad, D)])
    planted = make_zip([
        ("classes.dex", pad + b"https://Very-Traceable.XYZ/health" + pad, D),
        ("assets/plain.txt", b"see witec.io", S),
        ("AndroidManifest.xml", "WITEC.IO".encode("utf-16-le") + pad, D),
        ("resources.arsc", pad + "very-traceable.xyz".encode("utf-16-be") + pad, D),
        ("assets/inner.jar", inner, S),
    ])
    clean = make_zip([
        ("classes.dex", pad + b"http://127.0.0.1:5000/health traceable.xyz witec-io" + pad, D),
        ("assets/plain.txt", b"very traceable, witec io", S),
    ])

    expected = {
        ("classes.dex", "very-traceable.xyz", "UTF-8"),
        ("assets/plain.txt", "witec.io", "UTF-8"),
        ("AndroidManifest.xml", "witec.io", "UTF-16LE"),
        ("resources.arsc", "very-traceable.xyz", "UTF-16BE"),
        ("assets/inner.jar!a/B.class", "witec.io", "UTF-8"),
    }
    hits, stats, _ = scan_bytes(planted)
    found = {(where, host, label) for where, _, host, label in hits}
    raw_only = {(host, label) for where, _, host, label in hits if where == "(raw .apk)"}
    clean_hits, _, _ = scan_bytes(clean)

    checks = [
        ("every planted name is found, in its entry and encoding",
         expected <= found),
        ("nothing is reported outside the planted entries and the raw file",
         {w for w, _, _ in found} <= {w for w, _, _ in expected} | {"(raw .apk)"}),
        ("a raw byte search alone finds only the stored entry's name",
         raw_only == {("witec.io", "UTF-8")}),
        ("the nested jar is opened", stats["nested"] == 1),
        ("a clean archive has zero hits", clean_hits == []),
    ]
    for text, ok in checks:
        print(f"  {'PASS' if ok else 'FAIL'}  {text}")
    ok = all(ok for _, ok in checks)
    print(f"SELF-TEST {'PASS' if ok else 'FAIL'}")
    return ok


def main(args):
    if args == ["--self-test"]:
        return 0 if self_test() else 1
    if not args or any(a.startswith("-") for a in args):
        print(__doc__)
        return 2
    results = []
    for path in args:
        results.append(scan_apk(path))
        print()
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
