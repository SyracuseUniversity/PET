"""Android API-level check of a plugin APK (D58).

Every Android class, method and field the APK's dex files reference is
looked up in the SDK's api-versions.xml, so the APK cannot call anything
newer than the phones it is built for:

    python -I tools\\api_level_check.py <file.apk>

minSdk is read from the APK's own (binary) AndroidManifest.xml. A
reference to a platform class is looked up in api-versions.xml, following
the class's supertypes for inherited members. A reference to one of the
APK's own classes, or to one of ATAK's (main.jar, which ATAK provides at
run time), is followed up that class's own supertypes until the member is
declared: by the plugin or ATAK (not a platform API), or by a platform
class (checked). A reference needs its member's "since" level (or its
class's, when the member has none), and at least its class's own.

Exit 0 (PASS): nothing needs more than minSdk, nothing removed, nothing
unresolved. Exit 1 (FAIL): any of those, or an APK or input that could not
be read. Exit 2: usage. Reported but not refused:
  - members public in android.jar that api-versions.xml leaves out because
    they come from a hidden parent class (StringBuilder.setLength and the
    like), taken at their class's level;
  - dex system annotations (dalvik/annotation/...), which the compiler
    writes and the runtime reads; they are not API calls;
  - methods the APK defines that override a newer platform method, which
    only the newer platform calls.

Inputs, by default from this PC (each can be given instead):
    --api-versions <xml>  <sdk.dir>/platforms/android-<compileSdk>/data/
                          api-versions.xml (sdk.dir from local.properties,
                          compileSdk from app\\build.gradle)
    --android-jar <jar>   the same platform's android.jar
    --atak-jar <jar>      main.jar two levels above lingualinkledger\\
    --min-sdk <n>         instead of the APK's manifest

    python -I tools\\api_level_check.py --self-test

builds dex files and a manifest in memory and checks that an APK within
minSdk passes, that one needing a newer API, one referencing a class found
nowhere and one using a removed API are refused, that inherited members
and supertypes are followed, and that minSdk is read from the manifest.

    python -I tools\\api_level_check.py --write-fixtures <empty folder>

writes those fixtures (APKs and inputs) for make_release.py's self-test.

Written for Python 3.11 or later, standard library only.
"""

import io
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

TOOLS = os.path.dirname(os.path.abspath(__file__))
PLUGIN = os.path.dirname(TOOLS)
NO_INDEX = 0xFFFFFFFF
MIN_SDK_ATTR = 0x0101020C          # android:minSdkVersion
DEX_SYSTEM_ANNOTATIONS = {
    "dalvik/annotation/AnnotationDefault", "dalvik/annotation/EnclosingClass",
    "dalvik/annotation/EnclosingMethod", "dalvik/annotation/InnerClass",
    "dalvik/annotation/MemberClasses", "dalvik/annotation/MethodParameters",
    "dalvik/annotation/Signature", "dalvik/annotation/SourceDebugExtension",
    "dalvik/annotation/Throws", "dalvik/annotation/NestHost",
    "dalvik/annotation/NestMembers", "dalvik/annotation/Record",
    "dalvik/annotation/PermittedSubclasses",
}
RESERVED = ("java/", "javax/", "android/", "dalvik/", "org/json/", "org/w3c/", "org/xml/",
            "org/apache/http/", "junit/", "sun/", "libcore/")


class CheckError(Exception):
    """An input could not be read."""


# --- dex ---------------------------------------------------------------------

def uleb(buf, pos):
    result = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, pos
        shift += 7


def internal(desc):
    """'Landroid/view/View;' -> 'android/view/View'; arrays and primitives -> None."""
    return desc[1:-1] if desc.startswith("L") and desc.endswith(";") else None


class Dex:
    """The tables of a dex file this check needs: types, methods, fields, classes."""

    def __init__(self, buf):
        self.buf = buf
        u = lambda off: struct.unpack_from("<I", buf, off)[0]
        n_str, o_str, n_typ, o_typ = u(0x38), u(0x3C), u(0x40), u(0x44)
        n_pro, o_pro, n_fld, o_fld = u(0x48), u(0x4C), u(0x50), u(0x54)
        n_met, o_met, n_cls, o_cls = u(0x58), u(0x5C), u(0x60), u(0x64)
        self.strings = [self.mutf8(u(o_str + 4 * i)) for i in range(n_str)]
        self.types = [self.strings[u(o_typ + 4 * i)] for i in range(n_typ)]
        self.protos = []
        for i in range(n_pro):
            _, ret, params = struct.unpack_from("<III", buf, o_pro + 12 * i)
            self.protos.append("(" + "".join(self.type_list(params)) + ")" + self.types[ret])
        self.fields = []
        for i in range(n_fld):
            c, t, name = struct.unpack_from("<HHI", buf, o_fld + 8 * i)
            self.fields.append((self.types[c], self.strings[name], self.types[t]))
        self.methods = []
        for i in range(n_met):
            c, p, name = struct.unpack_from("<HHI", buf, o_met + 8 * i)
            self.methods.append((self.types[c], self.strings[name], self.protos[p]))
        self.classes = {}        # internal name -> (super, [interfaces], {members})
        for i in range(n_cls):
            cls, _, sup, ifaces, _, _, data, _ = struct.unpack_from("<8I", buf, o_cls + 32 * i)
            self.classes[internal(self.types[cls])] = (
                internal(self.types[sup]) if sup != NO_INDEX else None,
                [internal(t) for t in self.type_list(ifaces)],
                self.members(data))

    def mutf8(self, off):
        _, pos = uleb(self.buf, off)
        end = self.buf.index(b"\x00", pos)
        return self.buf[pos:end].decode("utf-8", "surrogateescape")

    def type_list(self, off):
        if off == 0:
            return []
        n = struct.unpack_from("<I", self.buf, off)[0]
        return [self.types[struct.unpack_from("<H", self.buf, off + 4 + 2 * k)[0]] for k in range(n)]

    def members(self, off):
        out = set()
        if off == 0:
            return out
        buf = self.buf
        counts = []
        pos = off
        for _ in range(4):
            n, pos = uleb(buf, pos)
            counts.append(n)
        for n in counts[:2]:                                   # static, instance fields
            idx = 0
            for _ in range(n):
                d, pos = uleb(buf, pos)
                _, pos = uleb(buf, pos)
                idx += d
                out.add(("F", self.fields[idx][1], self.fields[idx][2]))
        for n in counts[2:]:                                   # direct, virtual methods
            idx = 0
            for _ in range(n):
                d, pos = uleb(buf, pos)
                _, pos = uleb(buf, pos)
                _, pos = uleb(buf, pos)
                idx += d
                out.add(("M", self.methods[idx][1], self.methods[idx][2]))
        return out


# --- class files (main.jar, android.jar) ----------------------------------------------

def parse_class(data):
    """-> (name, super, [interfaces], {members}) of one .class file."""
    count = struct.unpack_from(">H", data, 8)[0]
    pos, utf, cls_ref, i = 10, {}, {}, 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack_from(">H", data, pos + 1)[0]
            utf[i] = data[pos + 3:pos + 3 + n].decode("utf-8", "surrogateescape")
            pos += 3 + n
        elif tag == 7:
            cls_ref[i] = struct.unpack_from(">H", data, pos + 1)[0]
            pos += 3
        elif tag in (8, 16, 19, 20):
            pos += 3
        elif tag == 15:
            pos += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        else:
            raise ValueError(f"constant pool tag {tag}")
        i += 1
    _, this, sup, n_if = struct.unpack_from(">HHHH", data, pos)
    pos += 8
    name = lambda ci: utf[cls_ref[ci]]
    ifaces = []
    for _ in range(n_if):
        ifaces.append(name(struct.unpack_from(">H", data, pos)[0]))
        pos += 2
    members = set()
    for kind in ("F", "M"):
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(n):
            _, ni, di, na = struct.unpack_from(">HHHH", data, pos)
            pos += 8
            for _ in range(na):
                pos += 6 + struct.unpack_from(">I", data, pos + 2)[0]
            members.add((kind, utf[ni], utf[di]))
    return name(this), (name(sup) if sup else None), ifaces, members


def load_jar(path):
    classes = {}
    with zipfile.ZipFile(path) as z:
        for entry in z.namelist():
            if entry.endswith(".class") and not entry.startswith("META-INF/"):
                try:
                    n, sup, ifaces, members = parse_class(z.read(entry))
                except (ValueError, KeyError, struct.error):
                    continue      # a class file this reader cannot follow is skipped
                classes[n] = (sup, ifaces, members)
    return classes


# --- api-versions.xml -------------------------------------------------------------------

def load_api(path):
    api = {}
    for _, el in ET.iterparse(path):
        if el.tag != "class":
            continue
        since = int(el.get("since", "1"))
        supers, members = [], {}
        for ch in el:
            if ch.tag in ("extends", "implements"):
                supers.append(ch.get("name"))
            elif ch.tag in ("method", "field"):
                members[("M" if ch.tag == "method" else "F", ch.get("name"))] = (
                    int(ch.get("since", since)), ch.get("removed"))
        api[el.get("name")] = (since, el.get("removed"), supers, members)
        el.clear()
    if not api:
        raise CheckError(f"{path} lists no classes")
    return api


# --- the APK's manifest (binary XML) ---------------------------------------------------------

def manifest_min_sdk(axml):
    """minSdkVersion from a compiled AndroidManifest.xml, or None."""
    if struct.unpack_from("<H", axml, 0)[0] != 0x0003:
        raise CheckError("AndroidManifest.xml is not compiled XML")
    pos, strings, resource_ids = 8, [], []
    while pos + 8 <= len(axml):
        ctype, hsize, csize = struct.unpack_from("<HHI", axml, pos)
        if csize < 8:
            break
        if ctype == 0x0001:                                    # string pool
            n, _, flags, start, _ = struct.unpack_from("<IIIII", axml, pos + 8)
            for k in range(n):
                off = pos + start + struct.unpack_from("<I", axml, pos + hsize + 4 * k)[0]
                if flags & 0x100:                              # UTF-8
                    _, o = _len8(axml, off)
                    blen, o = _len8(axml, o)
                    strings.append(axml[o:o + blen].decode("utf-8", "replace"))
                else:                                          # UTF-16
                    clen = struct.unpack_from("<H", axml, off)[0]
                    o = off + 2
                    if clen & 0x8000:
                        clen = ((clen & 0x7FFF) << 16) | struct.unpack_from("<H", axml, o)[0]
                        o += 2
                    strings.append(axml[o:o + 2 * clen].decode("utf-16-le", "replace"))
        elif ctype == 0x0180:                                  # resource ids of attribute names
            resource_ids = list(struct.unpack_from(f"<{(csize - 8) // 4}I", axml, pos + 8))
        elif ctype == 0x0102:                                  # start of an element
            name_i = struct.unpack_from("<I", axml, pos + hsize + 4)[0]
            a_start, a_size, a_count = struct.unpack_from("<HHH", axml, pos + hsize + 8)
            if name_i < len(strings) and strings[name_i] == "uses-sdk":
                for k in range(a_count):
                    a = pos + hsize + a_start + k * a_size
                    _, an, raw, _, _, dtype, data = struct.unpack_from("<IIIHBBI", axml, a)
                    named = an < len(strings) and strings[an] == "minSdkVersion"
                    by_id = an < len(resource_ids) and resource_ids[an] == MIN_SDK_ATTR
                    if named or by_id:
                        if dtype in (0x10, 0x11):              # an integer
                            return data
                        if raw != NO_INDEX and raw < len(strings) and strings[raw].isdigit():
                            return int(strings[raw])
                        raise CheckError("minSdkVersion is not a number (a preview codename?)")
        pos += csize
    return None


def _len8(buf, off):
    n = buf[off]
    if n & 0x80:
        return ((n & 0x7F) << 8) | buf[off + 1], off + 2
    return n, off + 1


# --- resolving a reference ---------------------------------------------------------------------

def find_member(classes, cls, kind, name, desc):
    """The class (cls or a supertype, within classes) declaring the member, or None."""
    queue, seen = [cls], set()
    while queue:
        c = queue.pop(0)
        if c is None or c in seen or c not in classes:
            continue
        seen.add(c)
        sup, ifaces, members = classes[c]
        if (kind, name, desc) in members:
            return c
        queue.append(sup)
        queue.extend(ifaces)
    return None


def resolve(api, local, cls, kind, name, desc):
    """-> ('platform', cls, level, removed) | ('local', cls) | ('unknown', cls) | ('unresolved', cls)."""
    queue, seen = [cls], set()
    while queue:
        c = queue.pop(0)
        if c is None or c in seen:
            continue
        seen.add(c)
        if c in api:
            m = api[c][3].get((kind, name + desc) if kind == "M" else (kind, name))
            if m is not None:
                return ("platform", c, m[0], m[1])
            if kind == "M" and name == "<init>":
                return ("unresolved", c)          # constructors are not inherited
            queue.extend(api[c][2])
        elif c in local:
            sup, ifaces, members = local[c]
            if (kind, name, desc) in members:
                return ("local", c)
            if kind == "M" and name == "<init>":
                return ("unresolved", c)
            queue.append(sup)
            queue.extend(ifaces)
        else:
            return ("unknown", c)
    return ("unresolved", cls)


# --- the check ---------------------------------------------------------------------------------

def check(apk, api, stubs, atak, min_sdk=None):
    """-> (passed, report lines)."""
    dexes = []
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        for n in sorted(names):
            if re.fullmatch(r"classes\d*\.dex", n):
                dexes.append((n, Dex(z.read(n))))
        manifest = z.read("AndroidManifest.xml") if "AndroidManifest.xml" in names else None
    if not dexes:
        raise CheckError("the APK holds no classes*.dex")
    if min_sdk is None:
        if manifest is None:
            raise CheckError("the APK holds no AndroidManifest.xml to read minSdk from")
        min_sdk = manifest_min_sdk(manifest)
        if min_sdk is None:
            raise CheckError("no minSdkVersion in the APK's manifest")
        min_from = "the APK's manifest"
    else:
        min_from = "given"

    apk_classes = {}
    for _, d in dexes:
        apk_classes.update(d.classes)
    local = dict(atak)
    local.update(apk_classes)            # the APK's own classes win

    needs, unresolved, removed, hidden, annotations, overrides = {}, set(), set(), set(), set(), set()
    n_refs = 0

    def note(text, level):
        if needs.get(text, 0) < level:
            needs[text] = level

    for _, d in dexes:
        for t in d.types:
            c = internal(t.lstrip("["))
            if c is None:
                continue
            if c in api:
                note(f"class {c}", api[c][0])
                if api[c][1]:
                    removed.add(f"class {c} (removed in API {api[c][1]})")
            elif c in DEX_SYSTEM_ANNOTATIONS:
                annotations.add(c)
            elif c not in local:
                where = "in a platform package, not in api-versions.xml" if c.startswith(RESERVED) \
                    else "not a platform class, not in the APK, not in ATAK's main.jar"
                unresolved.add(f"class {c}: {where}")
        for kind, refs in (("M", d.methods), ("F", d.fields)):
            for cls_desc, name, desc in refs:
                c = internal(cls_desc)
                if c is None:
                    continue                     # an array's clone()
                n_refs += 1
                label = (f"method {c}.{name}{desc}" if kind == "M" else f"field {c}.{name}:{desc}")
                if c in apk_classes and (kind, name, desc) in apk_classes[c][2]:
                    sup, ifaces, _ = apk_classes[c]
                    for s in [sup] + ifaces:
                        r = resolve(api, local, s, kind, name, desc) if s else None
                        if r and r[0] == "platform" and r[2] > min_sdk:
                            overrides.add(f"{label} overrides {r[1]} (API {r[2]})")
                    continue                     # defined by the APK itself
                r = resolve(api, local, c, kind, name, desc)
                if r[0] == "platform":
                    note(label + (f"  [declared in {r[1]}]" if r[1] != c else ""),
                         max(r[2], api[c][0] if c in api else 1))
                    if r[3]:
                        removed.add(f"{label} (removed in API {r[3]})")
                elif r[0] == "unknown":
                    unresolved.add(f"{label}: class {r[1]} found nowhere")
                elif r[0] == "unresolved":
                    owner = find_member(stubs, r[1], kind, name, desc) if r[1] in api else None
                    if owner:
                        level = api[r[1]][0]
                        hidden.add(f"{label}  [android.jar: {owner}; taken as API {level}]")
                        note(label, level)
                    else:
                        unresolved.add(f"{label}: not declared on {c} or any supertype")

    top = max(needs.values()) if needs else 0
    over = sorted((lvl, t) for t, lvl in needs.items() if lvl > min_sdk)
    hist = {}
    for lvl in needs.values():
        hist[lvl] = hist.get(lvl, 0) + 1
    passed = not over and not unresolved and not removed
    out = [
        f"minSdk         {min_sdk} (from {min_from})",
        f"APK classes    {len(apk_classes)} in {', '.join(n for n, _ in dexes)}",
        f"References     {n_refs} methods and fields; {len(needs)} types and members reach the platform",
        "By level       " + (", ".join(f"API {k}: {hist[k]}" for k in sorted(hist)) or "none"),
        f"Highest level  API {top}",
    ]
    out += [f"               {t}" for t, lvl in sorted(needs.items()) if lvl == top and top > 1]
    out.append(f"Above minSdk {min_sdk}: {len(over)}")
    out += [f"  API {lvl}  {t}" for lvl, t in over]
    out.append(f"Removed APIs: {len(removed)}")
    out += [f"  {t}" for t in sorted(removed)]
    out.append(f"Unresolved: {len(unresolved)}")
    out += [f"  {t}" for t in sorted(unresolved)]
    out.append("Public in android.jar but not in api-versions.xml (inherited from a hidden parent; "
               f"taken at the class's level): {len(hidden)}")
    out += [f"  {t}" for t in sorted(hidden)]
    out.append(f"Dex system annotations (read by the runtime; not API calls): {len(annotations)}")
    out += [f"  {t}" for t in sorted(annotations)]
    out.append(f"Overrides of platform methods newer than {min_sdk} (informational): {len(overrides)}")
    out += [f"  {t}" for t in sorted(overrides)]
    out.append(f"RESULT         {'PASS' if passed else 'FAIL'} - minSdk {min_sdk}, highest API needed {top}")
    return passed, out


# --- inputs on this PC -------------------------------------------------------------------------

def default_inputs():
    props = os.path.join(PLUGIN, "local.properties")
    sdk = None
    if os.path.isfile(props):
        with open(props, encoding="latin-1") as f:
            for line in f:
                m = re.match(r"\s*sdk\.dir\s*[=:]\s*(.*?)\s*$", line)
                if m:
                    sdk = re.sub(r"\\(.)", r"\1", m.group(1))
    if not sdk:
        raise CheckError(f"no sdk.dir in {props}; give --api-versions and --android-jar")
    with open(os.path.join(PLUGIN, "app", "build.gradle"), encoding="utf-8") as f:
        m = re.search(r"^\s*compileSdk(?:Version)?\s+(\d+)", f.read(), re.M)
    if not m:
        raise CheckError("no compileSdk in app\\build.gradle; give --api-versions and --android-jar")
    platform = os.path.join(sdk, "platforms", f"android-{m.group(1)}")
    return (os.path.join(platform, "data", "api-versions.xml"),
            os.path.join(platform, "android.jar"),
            os.path.normpath(os.path.join(PLUGIN, "..", "..", "main.jar")))


def run(apk, api_path, jar_path, atak_path, min_sdk):
    print(f"APK            {apk}")
    try:
        for p in (apk, api_path, jar_path, atak_path):
            if not os.path.isfile(p):
                raise CheckError(f"not found: {p}")
        api = load_api(api_path)
        stubs = load_jar(jar_path)
        atak = load_jar(atak_path)
        print(f"api-versions   {api_path} ({len(api)} platform classes)")
        print(f"android.jar    {jar_path} ({len(stubs)} classes)")
        print(f"ATAK classes   {atak_path} ({len(atak)} classes)")
        passed, lines = check(apk, api, stubs, atak, min_sdk)
    except (CheckError, OSError, zipfile.BadZipFile, ET.ParseError, ValueError, struct.error,
            IndexError, KeyError) as e:
        print(f"RESULT         FAIL - could not check the APK: {type(e).__name__}: {e}")
        return False
    print("\n".join(lines))
    return passed


# --- fixtures and self-test --------------------------------------------------------------------

def make_dex(method_refs=(), type_refs=(), class_defs=()):
    """A minimal dex: method_refs (class, name, return, [params]) as descriptors; class_defs
    (class, super[, [declared (name, return, [params])]]); type_refs extra types. Enough
    for Dex above."""
    strings, types, protos = [], [], []

    def s(x):
        if x not in strings:
            strings.append(x)
        return strings.index(x)

    def t(x):
        s(x)
        if x not in types:
            types.append(x)
        return types.index(x)

    meths = []

    def m(cls, name, ret, params):
        key = (t(ret), tuple(t(p) for p in params))
        if key not in protos:
            protos.append(key)
        if (t(cls), protos.index(key), s(name)) not in meths:
            meths.append((t(cls), protos.index(key), s(name)))
        return meths.index((t(cls), protos.index(key), s(name)))

    for cls, name, ret, params in method_refs:
        m(cls, name, ret, params)
    for x in type_refs:
        t(x)
    defs = []
    for d in class_defs:
        declared = [m(d[0], n, r, ps) for n, r, ps in (d[2] if len(d) > 2 else [])]
        defs.append((t(d[0]), t(d[1]), sorted(declared)))

    n_s, n_t, n_p, n_m, n_c = len(strings), len(types), len(protos), len(meths), len(defs)
    o_s = 0x70
    o_t = o_s + 4 * n_s
    o_p = o_t + 4 * n_t
    o_m = o_p + 12 * n_p
    o_c = o_m + 8 * n_m
    data = bytearray()
    base = o_c + 32 * n_c
    str_off = []
    for x in strings:
        str_off.append(base + len(data))
        raw = x.encode("utf-8")
        data += bytes([len(x)]) + raw + b"\x00"       # every fixture string is under 128 chars
    data_off = []
    for _, _, declared in defs:
        if not declared:
            data_off.append(0)
            continue
        data_off.append(base + len(data))
        data += bytes([0, 0, 0, len(declared)])           # no fields, no direct methods
        prev = 0
        for idx in declared:                              # virtual methods: idx diff, flags, code
            data += bytes([idx - prev, 1, 0])
            prev = idx
    plist_off = []
    for _, params in protos:
        while (base + len(data)) % 4:
            data += b"\x00"
        if params:
            plist_off.append(base + len(data))
            data += struct.pack("<I", len(params)) + b"".join(struct.pack("<H", p) for p in params)
        else:
            plist_off.append(0)
    buf = bytearray(0x70)
    buf[0:8] = b"dex\n035\x00"
    struct.pack_into("<10I", buf, 0x38, n_s, o_s, n_t, o_t, n_p, o_p, 0, 0, n_m, o_m)
    struct.pack_into("<2I", buf, 0x60, n_c, o_c)
    buf += b"".join(struct.pack("<I", o) for o in str_off)
    buf += b"".join(struct.pack("<I", strings.index(x)) for x in types)
    buf += b"".join(struct.pack("<III", 0, ret, plist_off[i]) for i, (ret, _) in enumerate(protos))
    buf += b"".join(struct.pack("<HHI", c, p, n) for c, p, n in meths)
    buf += b"".join(struct.pack("<8I", c, 1, sup, 0, NO_INDEX, 0, data_off[i], 0)
                    for i, (c, sup, _) in enumerate(defs))
    return bytes(buf + data)


def make_manifest(min_sdk):
    """A minimal compiled AndroidManifest.xml: <manifest><uses-sdk minSdkVersion=n/></manifest>."""
    strs = ["minSdkVersion", "uses-sdk", "manifest"]
    body = bytearray()
    offs = []
    for x in strs:
        offs.append(len(body))
        body += struct.pack("<H", len(x)) + x.encode("utf-16-le") + b"\x00\x00"
    while len(body) % 4:
        body += b"\x00"
    pool_h = 28
    pool = struct.pack("<HHIIIIII", 0x0001, pool_h, pool_h + 4 * len(strs) + len(body),
                       len(strs), 0, 0, pool_h + 4 * len(strs), 0)
    pool += b"".join(struct.pack("<I", o) for o in offs) + bytes(body)
    resmap = struct.pack("<HHI", 0x0180, 8, 12) + struct.pack("<I", MIN_SDK_ATTR)

    def element(name, attrs):
        a = b"".join(struct.pack("<IIIHBBI", NO_INDEX, an, NO_INDEX, 8, 0, 0x10, val) for an, val in attrs)
        ext = struct.pack("<IIHHHHHH", NO_INDEX, name, 20, 20, len(attrs), 0, 0, 0)
        return struct.pack("<HHIII", 0x0102, 16, 16 + len(ext) + len(a), 1, NO_INDEX) + ext + a

    chunks = pool + resmap + element(2, []) + element(1, [(0, min_sdk)])
    return struct.pack("<HHI", 0x0003, 8, 8 + len(chunks)) + chunks


FIXTURE_API = """<?xml version="1.0" encoding="utf-8"?>
<api version="3">
  <class name="java/lang/Object" since="1">
    <method name="&lt;init>()V"/>
  </class>
  <class name="android/app/Old" since="1">
    <extends name="java/lang/Object"/>
    <method name="foo()V"/>
  </class>
  <class name="android/app/New" since="26">
    <extends name="java/lang/Object"/>
    <method name="bar()V"/>
  </class>
  <class name="android/app/Gone" since="1" removed="23">
    <extends name="java/lang/Object"/>
    <method name="baz()V"/>
  </class>
</api>
"""

FIXTURE_APKS = {
    # name: (method refs, extra types, class defs, minSdk in its manifest)
    "within.apk": ([("Landroid/app/Old;", "foo", "V", []),
                    ("Lcom/test/Mine;", "foo", "V", []),          # inherited from Old
                    ("Lcom/test/Sub;", "own", "V", [])],          # inherited from Mine (the APK's)
                   ["Ldalvik/annotation/Signature;"],
                   [("Lcom/test/Mine;", "Landroid/app/Old;", [("own", "V", [])]),
                    ("Lcom/test/Sub;", "Lcom/test/Mine;")], 21),
    "newer.apk": ([("Landroid/app/New;", "bar", "V", [])], [], [], 21),
    "newer_inherited.apk": ([("Lcom/test/Mine2;", "bar", "V", [])],   # through the APK's own class
                            [], [("Lcom/test/Mine2;", "Landroid/app/New;")], 21),
    "unknown.apk": ([("Lcom/unknown/Thing;", "baz", "V", [])], [], [], 21),
    "removed.apk": ([("Landroid/app/Gone;", "baz", "V", [])], [], [], 21),
    "newer_at_26.apk": ([("Landroid/app/New;", "bar", "V", [])], [], [], 26),
}


def write_fixtures(folder):
    """The fixture APKs, api-versions.xml, android.jar and ATAK jar, in folder."""
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, "api-versions.xml"), "w", encoding="utf-8") as f:
        f.write(FIXTURE_API)
    for jar in ("android.jar", "atak.jar"):
        with zipfile.ZipFile(os.path.join(folder, jar), "w") as z:
            z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    for name, (refs, types, defs, min_sdk) in FIXTURE_APKS.items():
        with zipfile.ZipFile(os.path.join(folder, name), "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("classes.dex", make_dex(refs, types, defs))
            z.writestr("AndroidManifest.xml", make_manifest(min_sdk))
    return folder


def self_test():
    import tempfile
    checks = []
    with tempfile.TemporaryDirectory() as tmp:
        write_fixtures(tmp)
        api = load_api(os.path.join(tmp, "api-versions.xml"))

        def result(name, min_sdk=None):
            with zipfile.ZipFile(os.path.join(tmp, name)) as z:
                min_read = manifest_min_sdk(z.read("AndroidManifest.xml"))
            passed, lines = check(os.path.join(tmp, name), api, {}, {}, min_sdk)
            return passed, "\n".join(lines), min_read

        ok, rep, m = result("within.apk")
        checks.append(("an APK within minSdk passes", ok))
        checks.append(("minSdk is read from the APK's manifest (21)", m == 21 and "minSdk         21 (from" in rep))
        checks.append(("a member the APK's class inherits from the platform resolves (not unresolved)",
                       "Unresolved: 0" in rep))
        checks.append(("a dex system annotation is reported, not refused",
                       "dalvik/annotation/Signature" in rep and ok))
        ok, rep, _ = result("newer.apk")
        checks.append(("an APK needing a newer API (26 > 21) is refused",
                       not ok and "API 26  method android/app/New.bar()V" in rep))
        ok, rep, _ = result("newer_inherited.apk")
        checks.append(("a newer API reached through the APK's own subclass is refused",
                       not ok and "API 26  method com/test/Mine2.bar()V  [declared in android/app/New]" in rep))
        ok, rep, _ = result("unknown.apk")
        checks.append(("a class found nowhere is refused", not ok and "found nowhere" in rep))
        ok, rep, _ = result("removed.apk")
        checks.append(("a removed API is refused", not ok and "removed in API 23" in rep))
        ok, rep, m = result("newer_at_26.apk")
        checks.append(("the same API passes when the manifest's minSdk is 26", ok and m == 26))
        ok, _, _ = result("newer.apk", min_sdk=26)
        checks.append(("--min-sdk overrides the manifest", ok))
        checks.append(("a non-zip is reported as a failure, not a crash",
                       not run(os.path.join(tmp, "api-versions.xml"), os.path.join(tmp, "api-versions.xml"),
                               os.path.join(tmp, "android.jar"), os.path.join(tmp, "atak.jar"), None)))
    for text, ok in checks:
        print(f"  {'PASS' if ok else 'FAIL'}  {text}")
    ok = all(ok for _, ok in checks)
    print(f"SELF-TEST {'PASS' if ok else 'FAIL'}")
    return ok


def main(args):
    if args == ["--self-test"]:
        return 0 if self_test() else 1
    if len(args) == 2 and args[0] == "--write-fixtures":
        if os.path.exists(args[1]) and os.listdir(args[1]):
            print(f"{args[1]} is not empty")
            return 2
        print(write_fixtures(args[1]))
        return 0
    opts, apk = {}, None
    it = iter(args)
    for a in it:
        if a in ("--api-versions", "--android-jar", "--atak-jar", "--min-sdk"):
            opts[a] = next(it, None)
            if opts[a] is None:
                print(__doc__)
                return 2
        elif a.startswith("-") or apk is not None:
            print(__doc__)
            return 2
        else:
            apk = a
    if apk is None:
        print(__doc__)
        return 2
    try:
        defaults = default_inputs() if not all(k in opts for k in
                                               ("--api-versions", "--android-jar", "--atak-jar")) else (None,) * 3
    except (CheckError, OSError) as e:
        print(f"APK            {apk}\nRESULT         FAIL - {e}")
        return 1
    api_path = opts.get("--api-versions") or defaults[0]
    jar_path = opts.get("--android-jar") or defaults[1]
    atak_path = opts.get("--atak-jar") or defaults[2]
    try:
        min_sdk = int(opts["--min-sdk"]) if "--min-sdk" in opts else None
    except ValueError:
        print(__doc__)
        return 2
    return 0 if run(apk, api_path, jar_path, atak_path, min_sdk) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
