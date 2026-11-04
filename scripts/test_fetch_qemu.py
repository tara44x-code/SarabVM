#!/usr/bin/env python3
"""Tests for fetch_qemu.py. Run: python3 scripts/test_fetch_qemu.py"""

import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fetch_qemu as fq  # noqa: E402


def make_deb(entries):
    """entries: list of (path, bytes) or (path, '->target') for symlinks."""
    tar_buf = io.BytesIO()
    with tarfile.open(fileobj=tar_buf, mode="w:xz") as tar:
        for path, content in entries:
            info = tarfile.TarInfo("./" + fq.TERMUX_PREFIX + path)
            if isinstance(content, str) and content.startswith("->"):
                info.type = tarfile.SYMTYPE
                info.linkname = content[2:]
                tar.addfile(info)
            else:
                info.size = len(content)
                tar.addfile(info, io.BytesIO(content))
    members = [("debian-binary", b"2.0\n"), ("control.tar.xz", b""), ("data.tar.xz", tar_buf.getvalue())]
    out = io.BytesIO()
    out.write(b"!<arch>\n")
    for name, body in members:
        header = name.ljust(16) + "0".ljust(12) + "0".ljust(6) + "0".ljust(6) + "100644".ljust(8) + str(len(body)).ljust(10) + "`\n"
        out.write(header.encode())
        out.write(body)
        if len(body) & 1:
            out.write(b"\n")
    return out.getvalue()


class IndexTests(unittest.TestCase):
    INDEX = """Package: qemu-utils
Version: 1:9.1.0
Depends: glib, libandroid-shmem, zlib (>= 1.2), libnettle | libgcrypt
Filename: pool/main/q/qemu-utils/qemu-utils_9.1.0_aarch64.deb

Package: glib
Depends: libiconv, pcre2
Filename: pool/main/g/glib/glib.deb

Package: libiconv
Filename: a.deb

Package: pcre2
Filename: b.deb

Package: libandroid-shmem
Filename: c.deb

Package: zlib
Provides: libz
Filename: d.deb

Package: libgcrypt
Filename: e.deb
"""

    def test_parse_and_resolve(self):
        pk = fq.parse_index(self.INDEX)
        self.assertIn("qemu-utils", pk)
        self.assertEqual(pk["libz"]["Package"], "zlib")
        order, missing = fq.resolve(pk, ["qemu-utils"])
        names = [p["Package"] for p in order]
        self.assertEqual(names[0], "qemu-utils")
        self.assertEqual(sorted(names), sorted(["qemu-utils", "glib", "libiconv", "pcre2", "libandroid-shmem", "zlib", "libgcrypt"]))
        self.assertEqual(missing, [])

    def test_split_deps(self):
        self.assertEqual(fq.split_deps("a (>= 1), b | c (= 2),"), [["a"], ["b", "c"]])

    def test_android_name(self):
        self.assertEqual(fq.android_name("libz.so.1"), "libz1.so")
        self.assertEqual(fq.android_name("libfoo.so.1.2.3"), "libfoo123.so")
        self.assertEqual(fq.android_name("libglib-2.0.so"), "libglib-2.0.so")
        for n in ["libz.so.1", "libssl.so.3", "libpixman-1.so.0.42.2"]:
            self.assertLessEqual(len(fq.android_name(n)), len(n))


class PathPatchTests(unittest.TestCase):
    def test_resolv_path_redirect(self):
        data = b"xx" + fq.TERMUX_RESOLV + b"\0yy" + fq.TERMUX_RESOLV + b"\0"
        out, n = fq.patch_paths(data)
        self.assertEqual(n, 2)
        self.assertEqual(len(out), len(data))
        self.assertIn(fq.APP_RESOLV + b"\0", out)
        self.assertNotIn(fq.TERMUX_RESOLV, out)
        # a longer string that only starts with the path is left alone
        other = fq.TERMUX_RESOLV + b".bak\0"
        self.assertEqual(fq.patch_paths(other), (other, 0))
        self.assertLessEqual(len(fq.APP_RESOLV), len(fq.TERMUX_RESOLV))


class DebTests(unittest.TestCase):
    def test_extract(self):
        deb = make_deb([("bin/qemu-img", b"\x7fELFdata"), ("lib/libx.so.1", "->libx.so.1.0"),
                        ("share/qemu/bios-256k.bin", b"bios")])
        got = {rel: (kind, c) for rel, kind, c in fq.deb_files(deb)}
        self.assertEqual(got["bin/qemu-img"], ("file", b"\x7fELFdata"))
        self.assertEqual(got["lib/libx.so.1"], ("link", "libx.so.1.0"))
        self.assertEqual(got["share/qemu/bios-256k.bin"], ("file", b"bios"))

    def test_lookup_follows_links(self):
        files = {"lib/libz.so.1.3": b"x"}
        links = {"lib/libz.so.1": "libz.so.1.3", "lib/libz.so": "libz.so.1"}
        self.assertEqual(fq.lookup_lib("libz.so", files, links), "lib/libz.so.1.3")
        self.assertIsNone(fq.lookup_lib("libnope.so", files, links))


@unittest.skipUnless(sys.platform.startswith("linux") and shutil.which("git") and shutil.which("readelf")
                     and os.path.exists("/lib/x86_64-linux-gnu/libz.so.1"), "needs x86_64 Linux with git")
class ElfPatchTests(unittest.TestCase):
    """Real end-to-end check on this machine: rename libz.so.1 -> libz1.so inside git and run it."""

    def test_patched_program_still_runs(self):
        git = shutil.which("git")
        with open(os.path.realpath(git), "rb") as f:
            git_bytes = f.read()
        with open(os.path.realpath("/lib/x86_64-linux-gnu/libz.so.1"), "rb") as f:
            z_bytes = f.read()
        elf = fq.Elf(git_bytes)
        self.assertIn("libz.so.1", elf.needed())

        files = {"bin/qemu-img": git_bytes, "lib/libz.so.1.real": z_bytes}
        links = {"lib/libz.so.1": "libz.so.1.real"}
        saved = set(fq.SYSTEM_LIBS)
        try:
            # glibc's own libraries stand in for Android's system libraries here.
            for n in elf.needed():
                if n != "libz.so.1":
                    fq.SYSTEM_LIBS.add(n)
            bundle, mapping = fq.build_bundle(files, links, ["qemu-img"], machine=62)
        finally:
            fq.SYSTEM_LIBS.clear()
            fq.SYSTEM_LIBS.update(saved)

        self.assertEqual(mapping, {"libz.so.1": "libz1.so"})
        self.assertEqual(sorted(bundle), ["libqemu-img.so", "libz1.so"])
        patched = fq.Elf(bundle["libqemu-img.so"])
        self.assertIn("libz1.so", patched.needed())
        self.assertNotIn("libz.so.1", patched.needed())
        self.assertEqual(fq.Elf(bundle["libz1.so"]).soname(), "libz1.so")

        tmp = tempfile.mkdtemp()
        try:
            for name, data in bundle.items():
                p = os.path.join(tmp, name)
                with open(p, "wb") as f:
                    f.write(data)
                os.chmod(p, 0o755)
            verneed = subprocess.run(["readelf", "-V", os.path.join(tmp, "libqemu-img.so")],
                                     capture_output=True, text=True).stdout
            self.assertIn("File: libz1.so", verneed)
            self.assertNotIn("File: libz.so.1", verneed)
            env = dict(os.environ, LD_LIBRARY_PATH=tmp)
            out = subprocess.run([os.path.join(tmp, "libqemu-img.so"), "--version"], env=env,
                                 capture_output=True, text=True, timeout=30)
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertIn("git version", out.stdout)
            ldd = subprocess.run(["ldd", os.path.join(tmp, "libqemu-img.so")], env=env, capture_output=True, text=True).stdout
            self.assertIn(os.path.join(tmp, "libz1.so"), ldd)
        finally:
            shutil.rmtree(tmp)


if __name__ == "__main__":
    unittest.main(verbosity=2)
