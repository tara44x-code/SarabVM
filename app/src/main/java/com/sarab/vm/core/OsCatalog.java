package com.sarab.vm.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ready-made systems the app can download from their official websites.
 * File names change with every release, so each entry finds the current file
 * (and its SHA-256 checksum) from the project's own release index.
 */
public final class OsCatalog {

    /** Downloads a small text file (release index). Lets tests run without internet. */
    public interface TextFetcher {
        String fetch(String url) throws IOException;
    }

    /** A concrete file to download, with the checksum the project publishes ("SHA-256" or "MD5"). */
    public static final class Resolved {
        public final String url;
        public final String fileName;
        public final String checksum;
        public final String algorithm;

        public Resolved(String url, String fileName, String checksum, String algorithm) {
            this.url = url;
            this.fileName = fileName;
            this.checksum = checksum == null ? null : checksum.toLowerCase(Locale.ROOT);
            this.algorithm = algorithm;
        }
    }

    public enum Source { ALPINE, DEBIAN, TINYCORE }

    public static final class Entry {
        public final String id;
        public final String title;
        public final String description;
        public final String approxSize;
        public final VmConfig.Arch arch;
        public final int ramMb;
        public final int diskGb;
        final Source source;
        final String sourceArch;

        Entry(String id, String title, String description, String approxSize, VmConfig.Arch arch,
              int ramMb, int diskGb, Source source, String sourceArch) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.approxSize = approxSize;
            this.arch = arch;
            this.ramMb = ramMb;
            this.diskGb = diskGb;
            this.source = source;
            this.sourceArch = sourceArch;
        }

        /** Fills in a new machine for this system. */
        public VmConfig newConfig(String isoPath) {
            VmConfig c = new VmConfig();
            c.name = Lang.t(title);
            c.osType = VmConfig.OsType.LINUX;
            c.arch = arch;
            c.ramMb = ramMb;
            c.cpus = 2;
            c.diskSizeGb = diskGb;
            c.isoPath = isoPath;
            return c;
        }
    }

    static final String ALPINE_BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/";
    static final String DEBIAN_BASE = "https://cdimage.debian.org/debian-cd/current/";
    static final String TINYCORE_BASE = "https://distro.ibiblio.org/tinycorelinux/";

    private static final List<Entry> ENTRIES;

    static {
        List<Entry> l = new ArrayList<>();
        l.add(new Entry("tinycore", "Tiny Core Linux",
                "لينكس صغير جدًا بسطح مكتب رسومي. يقلع بسرعة، مناسب للتجربة الأولى.",
                "25 MB", VmConfig.Arch.I386, 512, 0, Source.TINYCORE, "x86"));
        l.add(new Entry("alpine-arm", "Alpine Linux ARM",
                "الأسرع على الهاتف لأنه بنفس معمارية المعالج. سطر أوامر.",
                "70 MB", VmConfig.Arch.AARCH64, 1024, 4, Source.ALPINE, "aarch64"));
        l.add(new Entry("alpine", "Alpine Linux",
                "لينكس خفيف لمعالجات الكمبيوتر (x86_64). سطر أوامر.",
                "65 MB", VmConfig.Arch.X86_64, 1024, 4, Source.ALPINE, "x86_64"));
        l.add(new Entry("debian-arm", "Debian ARM (مثبّت)",
                "يثبّت Debian كاملًا على قرص وهمي، ويُحمّل الحزم من الإنترنت أثناء التثبيت. سريع نسبيًا.",
                "700 MB", VmConfig.Arch.AARCH64, 2048, 16, Source.DEBIAN, "arm64"));
        l.add(new Entry("debian", "Debian (مثبّت)",
                "يثبّت Debian كاملًا لمعالجات الكمبيوتر. التثبيت يأخذ وقتًا طويلًا على الهاتف.",
                "700 MB", VmConfig.Arch.X86_64, 2048, 16, Source.DEBIAN, "amd64"));
        ENTRIES = Collections.unmodifiableList(l);
    }

    private OsCatalog() { }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** Finds the current download for an entry. Blocking (network). */
    public static Resolved resolve(Entry e, TextFetcher fetcher) throws IOException {
        switch (e.source) {
            case ALPINE: {
                String base = ALPINE_BASE + e.sourceArch + "/";
                return parseAlpine(fetcher.fetch(base + "latest-releases.yaml"), base);
            }
            case DEBIAN: {
                String base = DEBIAN_BASE + e.sourceArch + "/iso-cd/";
                return parseDebian(fetcher.fetch(base + "SHA256SUMS"), base, e.sourceArch);
            }
            case TINYCORE:
            default:
                return resolveTinyCore(fetcher, e.sourceArch);
        }
    }

    /** latest-releases.yaml → the "alpine-virt" ISO (made for virtual machines). */
    static Resolved parseAlpine(String yaml, String base) throws IOException {
        String[] blocks = yaml.split("(?m)^-");
        for (String block : blocks) {
            if (!Pattern.compile("(?m)^\\s*flavor:\\s*alpine-virt\\s*$").matcher(block).find()) continue;
            String iso = field(block, "iso");
            if (iso == null) iso = field(block, "file");
            if (iso == null || !iso.endsWith(".iso")) continue;
            String sha = field(block, "sha256");
            return new Resolved(base + iso, iso, sha, sha == null ? null : "SHA-256");
        }
        throw new IOException(Lang.t("لم أجد نسخة alpine-virt في قائمة الإصدارات"));
    }

    /** SHA256SUMS → debian-X.Y.Z-ARCH-netinst.iso */
    static Resolved parseDebian(String sums, String base, String arch) throws IOException {
        Pattern p = Pattern.compile("(?m)^([0-9a-fA-F]{64})\\s+\\*?(debian-[0-9.]+-" + Pattern.quote(arch) + "-netinst\\.iso)\\s*$");
        Matcher m = p.matcher(sums);
        if (!m.find()) throw new IOException(Lang.t("لم أجد ملف netinst في قائمة Debian"));
        return new Resolved(base + m.group(2), m.group(2), m.group(1), "SHA-256");
    }

    /**
     * Tiny Core keeps one folder per major version (17.x, 18.x …). Read the newest folder
     * listing that exists and take TinyCore-N.N.iso with its published MD5.
     */
    static Resolved resolveTinyCore(TextFetcher fetcher, String arch) throws IOException {
        IOException last = null;
        for (int v = 24; v >= 15; v--) {
            String dir = TINYCORE_BASE + v + ".x/" + arch + "/release/";
            String listing;
            try {
                listing = fetcher.fetch(dir);
            } catch (IOException e) {
                last = e;
                continue;
            }
            String file = newestTinyCore(listing);
            if (file == null) continue;
            String md5 = null;
            try {
                Matcher m = Pattern.compile("([0-9a-fA-F]{32})").matcher(fetcher.fetch(dir + file + ".md5.txt"));
                if (m.find()) md5 = m.group(1);
            } catch (IOException ignored) {
                // download without verification
            }
            return new Resolved(dir + file, file, md5, md5 == null ? null : "MD5");
        }
        throw new IOException(Lang.t("تعذر العثور على نسخة Tiny Core") + (last == null ? "" : ": " + last.getMessage()));
    }

    /** Highest "TinyCore-17.0.iso" style name in a folder listing. */
    static String newestTinyCore(String listing) {
        Matcher m = Pattern.compile("TinyCore-(\\d+)\\.(\\d+)\\.iso(?![.\\w])").matcher(listing);
        String best = null;
        int bestMajor = -1, bestMinor = -1;
        while (m.find()) {
            int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
            if (major > bestMajor || (major == bestMajor && minor > bestMinor)) {
                bestMajor = major;
                bestMinor = minor;
                best = "TinyCore-" + major + "." + minor + ".iso";
            }
        }
        return best;
    }

    private static String field(String block, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*" + name + ":\\s*\"?([^\"\\s]+)\"?\\s*$").matcher(block);
        return m.find() ? m.group(1) : null;
    }

    public static Entry byId(String id) {
        for (Entry e : ENTRIES) if (e.id.equals(id)) return e;
        return null;
    }
}
