#!/usr/bin/env python3
"""
Download QEMU for Android (arm64) from the Termux package repository and put it
into the app:

  app/src/main/jniLibs/arm64-v8a/   QEMU programs + their libraries (as lib*.so)
  app/src/main/assets/qemu-data/    BIOS / EDK2 firmware and keymaps

Why the renaming: Android only lets an app execute files from its native
library folder, and only files named lib*.so are placed there. Libraries whose
names carry a version (libz.so.1) are renamed (libz1.so) and every program and
library that refers to them is patched to use the new name.

Usage:  python3 scripts/fetch_qemu.py            (run from the project folder)
Needs:  Python 3.8+, internet access. No other tools.
"""

import argparse
import bz2
import gzip
import io
import lzma
import os
import re
import shutil
import struct
import sys
import tarfile
import urllib.request

DEFAULT_REPO = "https://packages-cf.termux.dev/apt/termux-main"
DEFAULT_PACKAGES = ["qemu-system-x86-64-headless", "qemu-system-aarch64-headless", "qemu-utils"]
PROGRAMS = ["qemu-system-x86_64", "qemu-system-aarch64", "qemu-img"]
TERMUX_PREFIX = "data/data/com.termux/files/usr/"

# Libraries Android itself provides; never bundled.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so",
    "libjnigraphics.so", "libmediandk.so", "libOpenSLES.so", "libaaudio.so", "libstdc++.so",
    "libnativewindow.so", "libsync.so", "libcamera2ndk.so", "libbinder_ndk.so",
}

# QEMU's network library (libslirp) reads the host's DNS servers from this Termux path, which
# does not exist inside our app. It is rewritten to a file the app writes before each start.
TERMUX_RESOLV = b"/data/data/com.termux/files/usr/etc/resolv.conf"
APP_RESOLV = b"/data/data/com.sarab.vm/files/resolv.conf"

# Firmware files worth shipping (x86 PC + aarch64 virt). Everything else is skipped.
DATA_KEEP = [
    r"^bios.*\.bin$", r"^vgabios.*\.bin$", r"^kvmvapic\.bin$", r"^linuxboot.*\.bin$",
    r"^multiboot.*\.bin$", r"^pvh\.bin$", r"^efi-.*\.rom$", r"^pxe-.*\.rom$",
    r"^edk2-aarch64-code\.fd$", r"^keymaps/.*",
]


def log(msg):
    print(msg, flush=True)


# ----------------------------------------------------------------------------- repository

def fetch(url, cache_dir):
    os.makedirs(cache_dir, exist_ok=True)
    name = re.sub(r"[^A-Za-z0-9._-]", "_", url.split("://", 1)[-1])
    path = os.path.join(cache_dir, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        with open(path, "rb") as f:
            return f.read()
    log("  download " + url)
    req = urllib.request.Request(url, headers={"User-Agent": "SarabVM-fetch/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    with open(path + ".tmp", "wb") as f:
        f.write(data)
    os.replace(path + ".tmp", path)
    return data


def load_index(repo, arch, cache_dir):
    base = "%s/dists/stable/main/binary-%s/Packages" % (repo, arch)
    errors = []
    for suffix, decode in (("", lambda b: b), (".xz", lzma.decompress), (".gz", gzip.decompress), (".bz2", bz2.decompress)):
        try:
            return parse_index(decode(fetch(base + suffix, cache_dir)).decode("utf-8", "replace"))
        except Exception as e:  # try the next compression
            errors.append("%s%s: %s" % (base, suffix, e))
    raise SystemExit("Could not download the package list:\n  " + "\n  ".join(errors))


def parse_index(text):
    packages = {}
    for block in text.split("\n\n"):
        fields = {}
        key = None
        for line in block.splitlines():
            if line.startswith((" ", "\t")) and key:
                fields[key] += "\n" + line.strip()
            elif ":" in line:
                key, value = line.split(":", 1)
                fields[key.strip()] = value.strip()
        if "Package" in fields:
            packages[fields["Package"]] = fields
            for prov in split_deps(fields.get("Provides", "")):
                packages.setdefault(prov[0], fields)
    return packages


def split_deps(value):
    """'a (>= 1), b | c' -> [['a'], ['b', 'c']]"""
    out = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        alts = [re.sub(r"\s*\(.*?\)", "", a).strip() for a in part.split("|")]
        out.append([a for a in alts if a])
    return out


def resolve(packages, wanted):
    order, seen, missing = [], set(), []
    stack = list(reversed(wanted))
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        info = packages.get(name)
        if info is None:
            missing.append(name)
            seen.add(name)
            continue
        real = info["Package"]
        seen.add(name)
        if real in seen and real != name:
            continue
        seen.add(real)
        order.append(info)
        deps = split_deps(info.get("Depends", "")) + split_deps(info.get("Pre-Depends", ""))
        for alts in deps:
            choice = next((a for a in alts if a in packages), alts[0])
            if choice not in seen:
                stack.append(choice)
    return order, missing


# ----------------------------------------------------------------------------- .deb

def ar_members(data):
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("not a .deb (ar) file")
    pos = 8
    while pos + 60 <= len(data):
        header = data[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        body = data[pos + 60:pos + 60 + size]
        yield name, body
        pos += 60 + size + (size & 1)


def deb_files(deb):
    """Yields (path under the Termux prefix, kind, content-or-link-target)."""
    for name, body in ar_members(deb):
        if not name.startswith("data.tar"):
            continue
        if name.endswith(".zst"):
            raise SystemExit("This package uses zstd compression, which Python cannot open. Install 'zstandard' support or update the script.")
        with tarfile.open(fileobj=io.BytesIO(body), mode="r:*") as tar:
            for m in tar.getmembers():
                path = m.name.lstrip("./")
                if not path.startswith(TERMUX_PREFIX):
                    continue
                rel = path[len(TERMUX_PREFIX):]
                if m.issym():
                    yield rel, "link", m.linkname
                elif m.isfile():
                    yield rel, "file", tar.extractfile(m).read()


# ----------------------------------------------------------------------------- ELF patching

class Elf:
    """Just enough ELF64 little-endian parsing to rename DT_NEEDED / DT_SONAME / verneed files."""

    def __init__(self, data):
        if data[:4] != b"\x7fELF":
            raise ValueError("not an ELF file")
        if data[4] != 2 or data[5] != 1:
            raise ValueError("only 64-bit little-endian ELF is supported")
        self.data = bytearray(data)
        (self.e_type, self.e_machine, _, _, self.e_phoff, self.e_shoff, _, _, self.e_phentsize,
         self.e_phnum, self.e_shentsize, self.e_shnum, _) = struct.unpack_from("<HHIQQQIHHHHHH", self.data, 16)
        self.phdrs = [struct.unpack_from("<IIQQQQQQ", self.data, self.e_phoff + i * self.e_phentsize)
                      for i in range(self.e_phnum)]
        self.dyn = []
        for p in self.phdrs:
            if p[0] == 2:  # PT_DYNAMIC
                off = p[2]
                while True:
                    tag, val = struct.unpack_from("<qQ", self.data, off)
                    self.dyn.append((tag, val, off))
                    off += 16
                    if tag == 0:
                        break
        self.strtab = self._file_offset(self._dyn_value(5)) if self._dyn_value(5) is not None else None
        self.strsz = self._dyn_value(10) or 0

    def _dyn_value(self, tag):
        for t, v, _ in self.dyn:
            if t == tag:
                return v
        return None

    def _file_offset(self, vaddr):
        for p in self.phdrs:
            if p[0] == 1 and p[3] <= vaddr < p[3] + p[5]:  # PT_LOAD: p_vaddr, p_filesz
                return p[2] + (vaddr - p[3])
        raise ValueError("address 0x%x not in file" % vaddr)

    def string(self, offset):
        start = self.strtab + offset
        end = self.data.index(b"\0", start)
        return self.data[start:end].decode()

    def needed(self):
        return [self.string(v) for t, v, _ in self.dyn if t == 1]

    def soname(self):
        v = self._dyn_value(14)
        return None if v is None else self.string(v)

    def _verneed_file_offsets(self):
        addr, num = self._dyn_value(0x6ffffffe), self._dyn_value(0x6fffffff)
        out = []
        if not addr or not num:
            return out
        off = self._file_offset(addr)
        for _ in range(num):
            _, _, vn_file, _, vn_next = struct.unpack_from("<HHIII", self.data, off)
            out.append(vn_file)
            if vn_next == 0:
                break
            off += vn_next
        return out

    def _symbol_name_offsets(self):
        """st_name of every dynamic symbol, found through the .dynsym section header."""
        names = []
        if not self.e_shoff or not self.e_shnum:
            return names
        for i in range(self.e_shnum):
            sh = struct.unpack_from("<IIQQQQIIQQ", self.data, self.e_shoff + i * self.e_shentsize)
            if sh[1] == 11:  # SHT_DYNSYM: sh_offset, sh_size, sh_entsize
                offset, size, entsize = sh[4], sh[5], sh[9] or 24
                for off in range(offset, offset + size, entsize):
                    names.append(struct.unpack_from("<I", self.data, off)[0])
        return names

    def _other_string_offsets(self):
        """Every other dynamic string reference (rpath, runpath, version names)."""
        out = [v for t, v, _ in self.dyn if t in (15, 29)]
        return out

    def rename(self, mapping):
        """Rewrites library names in place. New names must not be longer than the old ones."""
        refs = [v for t, v, _ in self.dyn if t in (1, 14)] + self._verneed_file_offsets()
        targets = {}
        for off in set(refs):
            old = self.string(off)
            if old in mapping:
                targets[off] = (old, mapping[old])
        if not targets:
            return 0
        sym_names = self._symbol_name_offsets() + self._other_string_offsets() + list(set(refs) - set(targets))
        for off, (old, new) in targets.items():
            if len(new) > len(old):
                raise ValueError("new name %s longer than %s" % (new, old))
            inside = [s for s in sym_names if off < s < off + len(old)]
            if inside:
                raise ValueError("string %s is shared with a symbol name; cannot patch safely" % old)
            start = self.strtab + off
            encoded = new.encode() + b"\0" * (len(old) - len(new))
            self.data[start:start + len(old)] = encoded
        return len(targets)


def android_name(soname):
    """libz.so.1 -> libz1.so ; libfoo.so stays."""
    if soname.endswith(".so"):
        return soname
    m = re.match(r"^(.+?)\.so((?:\.[0-9A-Za-z]+)+)$", soname)
    if not m:
        raise ValueError("unexpected library name " + soname)
    return m.group(1) + re.sub(r"[^0-9A-Za-z]", "", m.group(2)) + ".so"


# ----------------------------------------------------------------------------- main work

def collect(debs):
    files, links = {}, {}
    for deb in debs:
        for rel, kind, content in deb_files(deb):
            if kind == "file":
                files[rel] = content
            else:
                links[rel] = content
    return files, links


def lookup_lib(name, files, links):
    """Finds lib/<name>, following symlinks."""
    rel = "lib/" + name
    for _ in range(10):
        if rel in files:
            return rel
        if rel in links:
            target = links[rel]
            rel = os.path.normpath(os.path.join(os.path.dirname(rel), target)) if not target.startswith("/") \
                else target.split(TERMUX_PREFIX, 1)[-1]
            continue
        return None
    return None


def build_bundle(files, links, programs, machine=183):
    """Returns {output file name: patched bytes} for programs and every library they need."""
    bundle_src = {}   # output name -> source rel path
    queue = []
    for prog in programs:
        rel = "bin/" + prog
        if rel not in files:
            found = sorted(r for r in files if r.startswith("bin/qemu"))
            raise SystemExit("%s not found in the downloaded packages. Programs found: %s"
                             % (prog, ", ".join(found) or "none"))
        bundle_src["lib%s.so" % prog] = rel
        queue.append(rel)

    lib_names = {}    # needed/soname -> output name
    seen = set()
    missing = set()
    while queue:
        rel = queue.pop()
        if rel in seen:
            continue
        seen.add(rel)
        elf = Elf(files[rel])
        if machine and elf.e_machine != machine:
            raise SystemExit("%s is not built for the expected CPU (machine %d)" % (rel, elf.e_machine))
        for need in elf.needed():
            if need in SYSTEM_LIBS:
                continue
            src = lookup_lib(need, files, links)
            if src is None:
                missing.add(need)
                continue
            out = android_name(need)
            lib_names[need] = out
            soname = Elf(files[src]).soname()
            if soname:
                lib_names[soname] = out
            bundle_src[out] = src
            queue.append(src)
    if missing:
        raise SystemExit("Libraries needed but not found: " + ", ".join(sorted(missing)))

    out_names = {}
    for out, src in bundle_src.items():
        if out in out_names and out_names[out] != src:
            raise SystemExit("two libraries map to %s" % out)
        out_names[out] = src

    mapping = {k: v for k, v in lib_names.items() if k != v}
    result = {}
    for out, src in bundle_src.items():
        elf = Elf(files[src])
        elf.rename(mapping)
        result[out] = bytes(elf.data)
    return result, mapping


def patch_paths(data, old=TERMUX_RESOLV, new=APP_RESOLV):
    """Replaces every NUL-terminated copy of `old` with `new`, padded with NULs. Returns (bytes, count)."""
    if len(new) > len(old):
        raise ValueError("replacement path is longer than the original")
    target = old + b"\0"
    replacement = new + b"\0" * (len(old) - len(new) + 1)
    count = data.count(target)
    return data.replace(target, replacement), count


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--packages", nargs="+", default=DEFAULT_PACKAGES)
    ap.add_argument("--project", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap.add_argument("--cache", default=None, help="download cache folder (default: <project>/qemu-cache)")
    args = ap.parse_args()

    project = os.path.abspath(args.project)
    cache = args.cache or os.path.join(project, "qemu-cache")
    jni = os.path.join(project, "app", "src", "main", "jniLibs", "arm64-v8a")
    assets = os.path.join(project, "app", "src", "main", "assets", "qemu-data")

    log("1/4  Reading the Termux package list")
    packages = load_index(args.repo, "aarch64", cache)
    for p in args.packages:
        if p not in packages:
            similar = sorted(k for k in packages if k.startswith("qemu"))
            raise SystemExit("Package %s not found. QEMU packages in the repository:\n  %s" % (p, "\n  ".join(similar)))

    order, missing = resolve(packages, args.packages)
    if missing:
        log("  note: optional/unknown dependencies ignored: " + ", ".join(sorted(missing)))
    log("2/4  Downloading %d packages" % len(order))
    debs = [fetch(args.repo + "/" + info["Filename"], cache) for info in order]

    log("3/4  Preparing programs and libraries for Android")
    files, links = collect(debs)
    bundle, mapping = build_bundle(files, links, PROGRAMS)
    shutil.rmtree(jni, ignore_errors=True)
    os.makedirs(jni)
    total = 0
    patched = 0
    for name in list(bundle):
        bundle[name], n = patch_paths(bundle[name])
        patched += n
    log("  DNS settings path redirected in %d place(s)" % patched)
    if patched == 0:
        log("  warning: resolv.conf path not found; internet names may not resolve inside machines")
    for name, data in sorted(bundle.items()):
        with open(os.path.join(jni, name), "wb") as f:
            f.write(data)
        total += len(data)
    for old, new in sorted(mapping.items()):
        log("  renamed %s -> %s" % (old, new))
    log("  %d files, %.1f MB in %s" % (len(bundle), total / 1e6, os.path.relpath(jni, project)))

    log("4/4  Copying firmware files")
    shutil.rmtree(assets, ignore_errors=True)
    kept = 0
    for rel, data in files.items():
        if not rel.startswith("share/qemu/"):
            continue
        sub = rel[len("share/qemu/"):]
        if not any(re.match(p, sub) for p in DATA_KEEP):
            continue
        dest = os.path.join(assets, sub)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(data)
        kept += 1
    if kept == 0:
        raise SystemExit("No QEMU firmware files found (share/qemu). Check the package list.")
    for required in ("bios-256k.bin", "vgabios-stdvga.bin"):
        if not os.path.exists(os.path.join(assets, required)):
            raise SystemExit("Required firmware %s missing" % required)
    if not os.path.exists(os.path.join(assets, "edk2-aarch64-code.fd")):
        log("  warning: edk2-aarch64-code.fd missing; aarch64 machines will not boot")
    log("  %d firmware files in %s" % (kept, os.path.relpath(assets, project)))
    log("Done. Now build the app (./gradlew assembleDebug).")


if __name__ == "__main__":
    main()
