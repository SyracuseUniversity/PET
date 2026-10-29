"""Release script for App v1 (D47; Day 2, step 9).

Makes a release folder a teammate can be given, outside the repository:

    <Documents>\\PET_releases\\App-v1-<hash8>\\

where <hash8> is the first 8 hex characters of the commit it was built
from. Run from anywhere:

    python -I lingualinkledger\\tools\\make_release.py

Every release matches a commit and is known clean (the user's rules):

  1. REFUSED if the working tree has any uncommitted change, or any file
     git does not know about (untracked; ignored files such as build
     output do not count). A release is built only from a commit.
  2. The plugin is built (assembleCivDebug) and its unit tests run
     (testCivDebugUnitTest); REFUSED if either fails.
  3. REFUSED if the build changed any tracked file.
  4. REFUSED unless the APK is the one for HEAD: its name carries HEAD's
     hash8, as the build names it.
  5. REFUSED unless the APK passes the pre-install scan
     (tools\\preinstall_scan.py, layer 3 of the D19 safeguard, D31).
  6. REFUSED if JUnit or Hamcrest classes reached the APK (D50).
  7. REFUSED if a release folder for this commit already exists: a
     release is never replaced.
  8. REFUSED unless the APK passes the Android API-level check
     (tools\\api_level_check.py, D58): nothing it references needs more
     than the minSdk in its manifest, nothing removed, nothing unresolved.
  9. REFUSED unless the install guide in docs\\ uses the placeholders
     {{RELEASE}}, {{PLUGIN_APK}} and {{RELEASE_DATE}} and names no release
     itself: a guide cannot name its own release (writing the commit's
     hash into it would change the commit), so each release folder's copy
     is stamped with that folder's release, APK and date (the user, D61).
     Checked before building.

The folder is assembled under a temporary name, every file's SHA-256 is
written to SHA256SUMS.txt and checked again, and only then is it renamed
into place, so a half-made release folder never exists. It holds:

    the APK
    RELEASE.txt                  the commit, the APK's SHA-256, what is in it
    SHA256SUMS.txt               every other file's SHA-256 (sha256sum format)
    preinstall_scan_report.txt   the scan's own output
    api_level_report.txt         the API-level check's own output (rule 8)
    third_party\\zxing-core-3.5.4\\LICENSE, NOTICE   ZXing's licence (D51)
    test-qr\\                     the simulated test labels, v1.1 (D52, D53)
    PET_App_v1_Install_Guide.txt  stamped for this release (rule 9)

The APK's SHA-256 is sent to teammates separately from the folder (D47).

    python -I lingualinkledger\\tools\\make_release.py --self-test

checks each refusal on throwaway fixtures: a git repository with an
uncommitted and an untracked file, an APK holding a retired gateway
host, one holding JUnit, and one needing a newer Android API than its
minSdk (the API-level check's own self-test runs too). Nothing in the
repository is touched.

    python -I lingualinkledger\\tools\\make_release.py --stamp-guide <release folder>

writes the stamped guide BESIDE a release folder made before the guide
existed (named PET_App_v1_Install_Guide_<release>.txt), so the folder and
its SHA256SUMS.txt stay as they were. It is never replaced.

Options: --java-home <JDK> (default: JAVA_HOME_FOR_PET, else this PC's
jbr-21.0.11, D12); --releases <folder> (default: Documents\\PET_releases).

Python 3.11 or later, standard library only.
"""

import datetime
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

TOOLS = os.path.dirname(os.path.abspath(__file__))
PLUGIN = os.path.dirname(TOOLS)                      # lingualinkledger
REPO = os.path.dirname(PLUGIN)                       # PET
SCAN = os.path.join(TOOLS, "preinstall_scan.py")
API_CHECK = os.path.join(TOOLS, "api_level_check.py")
APK_DIR = os.path.join(PLUGIN, "app", "build", "outputs", "apk", "civ", "debug")
TEST_RESULTS = os.path.join(PLUGIN, "app", "build", "test-results", "testCivDebugUnitTest")
ZXING = os.path.join(PLUGIN, "app", "src", "main", "assets", "third_party", "zxing-core-3.5.4")
TEST_QR = os.path.join(REPO, "docs", "test-qr")
GUIDE = os.path.join(REPO, "docs", "PET_App_v1_Install_Guide.txt")
GUIDE_NAME = os.path.basename(GUIDE)
MONTHS = ("January", "February", "March", "April", "May", "June", "July", "August",
          "September", "October", "November", "December")
DEFAULT_JDK = r"C:\Users\darre\.jdks\jbr-21.0.11"
FORBIDDEN_IN_APK = (b"Lorg/junit/", b"Lorg/hamcrest/")


class Refused(Exception):
    """A rule was broken: no release is made."""


# --- the checks --------------------------------------------------------------

def git(repo, *args):
    r = subprocess.run(["git", "-C", repo] + list(args), capture_output=True, text=True)
    if r.returncode != 0:
        raise Refused(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def require_clean(repo):
    """Rule 1: nothing uncommitted, nothing untracked."""
    status = git(repo, "status", "--porcelain", "--untracked-files=all")
    if status.strip():
        lines = status.rstrip("\n").splitlines()
        shown = "\n    ".join(lines[:20]) + ("\n    ..." if len(lines) > 20 else "")
        raise Refused("the working tree is not clean; commit or remove these first:\n    " + shown)


def require_scan_passes(apk, scan=SCAN):
    """Rule 5: the pre-install scan's verdict, and its report."""
    r = subprocess.run([sys.executable, "-I", scan, apk], capture_output=True, text=True)
    report = r.stdout + r.stderr
    if r.returncode != 0:
        raise Refused(f"the pre-install scan failed (exit {r.returncode}); do not install or release "
                      f"this APK:\n{report}")
    return report


def require_api_check(apk, *options, check=API_CHECK):
    """Rule 8: the API-level check's verdict, and its report."""
    r = subprocess.run([sys.executable, "-I", check, apk] + list(options), capture_output=True, text=True)
    report = r.stdout + r.stderr
    if r.returncode != 0:
        raise Refused(f"the API-level check failed (exit {r.returncode}); the APK references an Android "
                      f"API newer than its minSdk, or one that could not be resolved:\n{report}")
    return report


def require_no_junit(apk):
    """Rule 6: no test library inside the APK's dex files."""
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if name.endswith(".dex"):
                data = z.read(name)
                for marker in FORBIDDEN_IN_APK:
                    if marker in data:
                        raise Refused(f"{name} holds {marker.decode()} classes: a test library is in the APK")


def read_guide():
    """The install guide in docs, as written (line endings kept), or None."""
    if not os.path.isfile(GUIDE):
        return None
    with open(GUIDE, encoding="utf-8", newline="") as f:
        return f.read()


def stamp_guide(text, release, apk_name, made):
    """Rule 9: the guide names no release; its copy names this one, its APK and its date."""
    if "{{RELEASE}}" not in text:
        raise Refused("the install guide has no {{RELEASE}} placeholder, so its copy could not name "
                      "the release it is for")
    named = sorted(set(re.findall(r"App-v1-[0-9a-f]{8}", text)))
    if named:
        raise Refused(f"the install guide in docs names a release ({', '.join(named)}); it must use "
                      "the placeholders, so that each release folder's copy names its own release")
    out = (text.replace("{{RELEASE}}", release).replace("{{PLUGIN_APK}}", apk_name)
           .replace("{{RELEASE_DATE}}", f"{made.day} {MONTHS[made.month - 1]} {made.year}"))
    left = sorted(set(re.findall(r"\{\{[^{}]*\}\}", out)))
    if left:
        raise Refused(f"the install guide has placeholders this script does not fill: {', '.join(left)}")
    return out


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


# --- building ------------------------------------------------------------------

def build(java_home):
    """Rule 2: the APK and the unit tests, as gradlew runs them."""
    env = dict(os.environ, JAVA_HOME=java_home)
    gradlew = os.path.join(PLUGIN, "gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = (["cmd", "/c", gradlew] if os.name == "nt" else [gradlew]) + [
        "assembleCivDebug", "testCivDebugUnitTest", "-q"]
    print("Building and testing (gradlew assembleCivDebug testCivDebugUnitTest) ...")
    r = subprocess.run(cmd, cwd=PLUGIN, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        tail = "\n".join((r.stdout + r.stderr).strip().splitlines()[-30:])
        raise Refused(f"the build or a unit test failed (exit {r.returncode}):\n{tail}")


def unit_test_count():
    tests = failed = 0
    for p in glob.glob(os.path.join(TEST_RESULTS, "*.xml")):
        r = ET.parse(p).getroot()
        tests += int(r.get("tests"))
        failed += int(r.get("failures")) + int(r.get("errors"))
    if failed:
        raise Refused(f"{failed} unit tests failed")
    return tests


def head_apk(hash8):
    """Rule 4: the APK the build named for this commit."""
    found = glob.glob(os.path.join(APK_DIR, f"ATAK-Plugin-lingualinkledger-*-{hash8}-*-civ-debug.apk"))
    if len(found) != 1:
        others = [os.path.basename(p) for p in glob.glob(os.path.join(APK_DIR, "*.apk"))]
        raise Refused(f"expected one APK named for {hash8}, found {len(found)}; in the folder: {others}")
    return found[0]


# --- the release folder ------------------------------------------------------------

def assemble(partial, apk, scan_report, api_report, head, subject, tests):
    os.makedirs(partial)
    shutil.copy2(apk, partial)
    with open(os.path.join(partial, "preinstall_scan_report.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(scan_report)
    with open(os.path.join(partial, "api_level_report.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(api_report)
    api_result = next((l.split(None, 1)[1] for l in api_report.splitlines() if l.startswith("RESULT")), "PASS")
    zx = os.path.join(partial, "third_party", "zxing-core-3.5.4")
    os.makedirs(zx)
    for name in ("LICENSE", "NOTICE"):
        shutil.copy2(os.path.join(ZXING, name), zx)
    qr = os.path.join(partial, "test-qr")
    os.makedirs(os.path.join(qr, "png"))
    for name in ("README.txt", "PET_Test_QR_Codes_v1.1.pdf"):
        shutil.copy2(os.path.join(TEST_QR, name), qr)
    for png in sorted(glob.glob(os.path.join(TEST_QR, "png", "*.png"))):
        shutil.copy2(png, os.path.join(qr, "png"))
    release = os.path.basename(partial).replace(".partial", "")
    apk_name = os.path.basename(apk)
    made = datetime.datetime.now(datetime.timezone.utc)
    guide = read_guide()
    has_guide = guide is not None
    if has_guide:                                              # rule 9: stamped for this release
        with open(os.path.join(partial, GUIDE_NAME), "w", encoding="utf-8", newline="") as f:
            f.write(stamp_guide(guide, release, apk_name, made))

    apk_sha = sha256_of(apk)
    built = made.strftime("%Y-%m-%dT%H:%M:%SZ")
    release_txt = f"""PET App v1 - release {os.path.basename(partial).replace('.partial', '')}

Commit      {head}
            {subject}
Made        {built}
APK         {apk_name}
APK SHA-256 {apk_sha}

Checked before this folder was made (tools\\make_release.py):
  - built from that commit, with a clean working tree;
  - {tests} unit tests passed;
  - the pre-install scan passed (preinstall_scan_report.txt);
  - no JUnit or Hamcrest classes in the APK;
  - the Android API-level check passed (api_level_report.txt):
    {api_result}.

This is a debug-signed test build for the SDK build of ATAK-CIV 5.8.0.5
(a306924), never the Play Store or RELEASE APKs. It sends nothing to any
server or gateway; its records stay on the phone, in plain text, and
hold the phone's real position.

Before installing, check the APK's SHA-256 against the value sent to you
separately, not against the one above or in SHA256SUMS.txt: anyone who
changed the APK could change those too.
    Get-FileHash -Algorithm SHA256 {apk_name}
    certutil -hashfile {apk_name} SHA256

SHA256SUMS.txt lists every other file in this folder. test-qr\\ holds the
simulated test labels (version 1.1): show them on a screen or print them.
Install guide: {GUIDE_NAME + ' (written for this release)' if has_guide else 'not yet in this release (it comes with step 15)'}.
"""
    with open(os.path.join(partial, "RELEASE.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(release_txt)

    sums = []
    for root, _, files in os.walk(partial):
        for name in files:
            path = os.path.join(root, name)
            rel = os.path.relpath(path, partial).replace(os.sep, "/")
            if rel != "SHA256SUMS.txt":
                sums.append((rel, sha256_of(path)))
    sums.sort()
    with open(os.path.join(partial, "SHA256SUMS.txt"), "w", encoding="ascii", newline="\n") as f:
        for rel, h in sums:
            f.write(f"{h}  {rel}\n")
    return apk_sha, len(sums)


def verify(folder):
    """Every file listed in SHA256SUMS.txt hashes as listed, and none is missing or extra."""
    listed = {}
    with open(os.path.join(folder, "SHA256SUMS.txt"), encoding="ascii") as f:
        for line in f:
            h, rel = line.rstrip("\n").split("  ", 1)
            listed[rel] = h
    present = set()
    for root, _, files in os.walk(folder):
        for name in files:
            rel = os.path.relpath(os.path.join(root, name), folder).replace(os.sep, "/")
            if rel != "SHA256SUMS.txt":
                present.add(rel)
    if present != set(listed):
        raise Refused(f"SHA256SUMS.txt does not list exactly the folder's files: {sorted(present ^ set(listed))}")
    for rel, h in listed.items():
        if sha256_of(os.path.join(folder, *rel.split("/"))) != h:
            raise Refused(f"{rel} does not match SHA256SUMS.txt")


def make_release(java_home, releases):
    require_clean(REPO)                                        # rule 1
    head = git(REPO, "rev-parse", "HEAD").strip()
    hash8 = head[:8]
    subject = git(REPO, "log", "-1", "--format=%s").strip()
    target = os.path.join(releases, f"App-v1-{hash8}")
    if os.path.exists(target):                                 # rule 7
        raise Refused(f"{target} already exists; a release is never replaced")

    guide = read_guide()
    if guide is not None:                                      # rule 9, before building
        stamp_guide(guide, f"App-v1-{hash8}", "check.apk", datetime.datetime.now(datetime.timezone.utc))

    build(java_home)                                           # rule 2
    tests = unit_test_count()
    require_clean(REPO)                                        # rule 3
    apk = head_apk(hash8)                                      # rule 4
    print(f"APK: {os.path.basename(apk)}")
    report = require_scan_passes(apk)                          # rule 5
    require_no_junit(apk)                                      # rule 6
    api_report = require_api_check(apk)                        # rule 8

    os.makedirs(releases, exist_ok=True)
    partial = target + ".partial"
    if os.path.exists(partial):
        shutil.rmtree(partial)                                 # left by an earlier run cut short
    apk_sha, files = assemble(partial, apk, report, api_report, head, subject, tests)
    verify(partial)
    os.rename(partial, target)
    verify(target)
    print(f"\nRELEASE MADE: {target}")
    print(f"  commit {head} ({subject})")
    print(f"  {tests} unit tests passed; pre-install scan passed; no JUnit in the APK; "
          f"API-level check passed")
    print(f"  {files} files, each in SHA256SUMS.txt, checked")
    print(f"\nAPK SHA-256, to send to teammates separately (D47):\n  {apk_sha}")
    return 0


def stamp_beside(folder):
    """The stamped guide for a release made before the guide existed, written beside its folder."""
    folder = os.path.normpath(os.path.abspath(folder))
    release = os.path.basename(folder)
    if not re.fullmatch(r"App-v1-[0-9a-f]{8}", release):
        raise Refused(f"{folder} is not a release folder (App-v1-<hash8>)")
    verify(folder)                                             # the folder is as it was made
    if os.path.exists(os.path.join(folder, GUIDE_NAME)):
        raise Refused(f"{release} already holds its own guide")
    apks = glob.glob(os.path.join(folder, f"ATAK-Plugin-lingualinkledger-*-{release[7:]}-*-civ-debug.apk"))
    if len(apks) != 1:
        raise Refused(f"expected one APK named for {release[7:]} in {folder}, found {len(apks)}")
    made = None
    with open(os.path.join(folder, "RELEASE.txt"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("Made"):
                made = datetime.datetime.strptime(line.split()[1], "%Y-%m-%dT%H:%M:%SZ")
    if made is None:
        raise Refused(f"no Made line in {release}'s RELEASE.txt")
    guide = read_guide()
    if guide is None:
        raise Refused(f"{GUIDE} does not exist")
    out = os.path.join(os.path.dirname(folder), f"{os.path.splitext(GUIDE_NAME)[0]}_{release}.txt")
    if os.path.exists(out):
        raise Refused(f"{out} already exists; it is never replaced")
    text = stamp_guide(guide, release, os.path.basename(apks[0]), made)
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    print(f"GUIDE WRITTEN: {out}")
    print(f"  for {release} ({os.path.basename(apks[0])}), made {made:%Y-%m-%d}")
    print(f"  SHA-256 {sha256_of(out)}")
    return 0


# --- self-test ---------------------------------------------------------------------

def self_test():
    checks = []

    def expect_refused(what, fn, *args):
        try:
            fn(*args)
            checks.append((f"refused: {what}", False))
        except Refused:
            checks.append((f"refused: {what}", True))

    def expect_passes(what, fn, *args):
        try:
            fn(*args)
            checks.append((f"passes: {what}", True))
        except Refused as e:
            checks.append((f"passes: {what} ({e})", False))

    with tempfile.TemporaryDirectory() as tmp:
        repo = os.path.join(tmp, "repo")
        os.makedirs(repo)
        git(repo, "init", "-q")
        with open(os.path.join(repo, "a.txt"), "w") as f:
            f.write("one\n")
        git(repo, "add", "a.txt")
        git(repo, "-c", "user.name=self-test", "-c", "user.email=self-test@example.invalid",
            "commit", "-q", "-m", "first")
        with open(os.path.join(repo, ".gitignore"), "w") as f:
            f.write("build/\n")
        git(repo, "add", ".gitignore")
        git(repo, "-c", "user.name=self-test", "-c", "user.email=self-test@example.invalid",
            "commit", "-q", "-m", "ignore build")
        os.makedirs(os.path.join(repo, "build"))
        with open(os.path.join(repo, "build", "out.apk"), "w") as f:
            f.write("ignored build output\n")
        expect_passes("a clean tree (ignored build output does not count)", require_clean, repo)

        with open(os.path.join(repo, "new.txt"), "w") as f:
            f.write("untracked\n")
        expect_refused("an untracked file", require_clean, repo)
        os.remove(os.path.join(repo, "new.txt"))

        with open(os.path.join(repo, "a.txt"), "w") as f:
            f.write("changed\n")
        expect_refused("an uncommitted change", require_clean, repo)
        git(repo, "checkout", "-q", "--", "a.txt")

        git(repo, "rm", "-q", "--cached", "a.txt")
        expect_refused("a staged but uncommitted change", require_clean, repo)

        def apk(name, entries):
            path = os.path.join(tmp, name)
            with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
                for entry, data in entries.items():
                    z.writestr(entry, data)
            return path

        clean = apk("clean.apk", {"classes.dex": b"dex\nLcom/atakmap/Plugin;" * 50,
                                  "AndroidManifest.xml": b"manifest"})
        retired = apk("retired.apk", {"classes.dex": b"dex\n" * 50 + b"https://very-traceable.xyz/health"})
        junit = apk("junit.apk", {"classes2.dex": b"dex\nLorg/junit/Test;"})
        expect_passes("the scan on a clean APK", require_scan_passes, clean)
        expect_refused("an APK holding a retired gateway host", require_scan_passes, retired)
        expect_passes("no JUnit in a clean APK", require_no_junit, clean)
        expect_refused("an APK holding JUnit", require_no_junit, junit)

        r = subprocess.run([sys.executable, "-I", API_CHECK, "--self-test"], capture_output=True, text=True)
        checks.append(("passes: the API-level check's own self-test", r.returncode == 0))
        fx = os.path.join(tmp, "api-fixtures")
        r = subprocess.run([sys.executable, "-I", API_CHECK, "--write-fixtures", fx],
                           capture_output=True, text=True)
        checks.append(("the API-level check wrote its fixtures", r.returncode == 0))
        inputs = ("--api-versions", os.path.join(fx, "api-versions.xml"),
                  "--android-jar", os.path.join(fx, "android.jar"), "--atak-jar", os.path.join(fx, "atak.jar"))
        expect_passes("an APK within its minSdk (API check)", require_api_check,
                      os.path.join(fx, "within.apk"), *inputs)
        expect_refused("an APK needing a newer Android API than its minSdk", require_api_check,
                       os.path.join(fx, "newer.apk"), *inputs)
        expect_refused("an APK referencing a class found nowhere", require_api_check,
                       os.path.join(fx, "unknown.apk"), *inputs)

        folder = os.path.join(tmp, "release")
        os.makedirs(folder)
        with open(os.path.join(folder, "x.txt"), "w") as f:
            f.write("x\n")
        with open(os.path.join(folder, "SHA256SUMS.txt"), "w", encoding="ascii", newline="\n") as f:
            f.write(f"{sha256_of(os.path.join(folder, 'x.txt'))}  x.txt\n")
        expect_passes("a folder that matches its SHA256SUMS.txt", verify, folder)
        with open(os.path.join(folder, "x.txt"), "a") as f:
            f.write("changed\n")
        expect_refused("a file changed after SHA256SUMS.txt", verify, folder)

        made = datetime.datetime(2026, 10, 13, 21, 23)
        sample = "Release {{RELEASE}} ({{RELEASE_DATE}}), file {{PLUGIN_APK}}.\r\nNext line\n"
        checks.append(("the guide's copy names its release, APK and date, line endings kept",
                       stamp_guide(sample, "App-v1-449152c3", "p-449152c3.apk", made)
                       == "Release App-v1-449152c3 (13 October 2026), file p-449152c3.apk.\r\nNext line\n"))
        expect_refused("a guide with no {{RELEASE}} placeholder", stamp_guide,
                       "Release App, file x\n", "App-v1-449152c3", "p.apk", made)
        expect_refused("a guide in docs that names a release itself", stamp_guide,
                       "{{RELEASE}}; see also App-v1-3ccdc57e\n", "App-v1-449152c3", "p.apk", made)
        expect_refused("a guide with a placeholder the script does not fill", stamp_guide,
                       "{{RELEASE}} {{PHONE}}\n", "App-v1-449152c3", "p.apk", made)
        guide = read_guide()
        if guide is not None:
            expect_passes("the guide in docs stamps cleanly", stamp_guide, guide, "App-v1-449152c3",
                          "p-449152c3.apk", made)

    for text, ok in checks:
        print(f"  {'PASS' if ok else 'FAIL'}  {text}")
    ok = all(ok for _, ok in checks)
    print(f"SELF-TEST {'PASS' if ok else 'FAIL'}")
    return ok


def main(args):
    if args == ["--self-test"]:
        return 0 if self_test() else 1
    if len(args) == 2 and args[0] == "--stamp-guide":
        try:
            return stamp_beside(args[1])
        except Refused as e:
            print(f"\nREFUSED - no guide written: {e}")
            return 1
    java_home = os.environ.get("JAVA_HOME_FOR_PET", DEFAULT_JDK)
    releases = os.path.join(os.path.expanduser("~"), "Documents", "PET_releases")
    it = iter(args)
    for a in it:
        if a == "--java-home":
            java_home = next(it, None)
        elif a == "--releases":
            releases = next(it, None)
        else:
            print(__doc__)
            return 2
    if not java_home or not releases:
        print(__doc__)
        return 2
    try:
        return make_release(java_home, releases)
    except Refused as e:
        print(f"\nREFUSED - no release made: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
