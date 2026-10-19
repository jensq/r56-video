package se.r56.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal RAR4 / RAR5 header reader. It finds the (stored, i.e. uncompressed) video file inside a
 * multi-volume archive and maps it to byte ranges in the volumes, so it can be streamed and seeked
 * without extracting anything.
 */
public final class RarArchive {

    public interface Reader {
        long length() throws IOException;

        /** Reads up to len bytes at pos; returns bytes read or -1 at end of file. */
        int read(long pos, byte[] buf, int off, int len) throws IOException;

        void close();
    }

    public interface Opener {
        Reader open(String volumeName) throws IOException;
    }

    public static final class Segment {
        public final String volume;
        public final long offset; // offset of the data inside the volume
        public final long size;
        public final long start;  // start position inside the virtual file

        Segment(String volume, long offset, long size, long start) {
            this.volume = volume;
            this.offset = offset;
            this.size = size;
            this.start = start;
        }
    }

    public static final class Result {
        public final String fileName;
        public final long totalSize;
        public final List<Segment> segments;

        Result(String fileName, long totalSize, List<Segment> segments) {
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.segments = segments;
        }
    }

    private static final class Entry {
        String name;
        boolean dir;
        boolean encrypted;
        int method; // 0 = stored
        long unpSize;
        long dataOffset;
        long dataSize;
        boolean splitAfter;
    }

    private static final String[] VIDEO_EXT = {
            "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "wmv", "webm", "mpg", "mpeg", "flv", "ogm", "vob"
    };

    public static boolean isVideoName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int z = n.indexOf('\0');
        if (z >= 0) n = n.substring(0, z);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        for (String e : VIDEO_EXT) if (e.equals(ext)) return true;
        return false;
    }

    // ---------------------------------------------------------------- volume naming

    private static final Pattern PART = Pattern.compile("^(.*)\\.part0*(\\d+)\\.rar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN = Pattern.compile("^(.*)\\.rar$", Pattern.CASE_INSENSITIVE);

    /** True if this file name is the first volume of a set (name.rar or name.part1.rar). */
    public static boolean isFirstVolumeName(String name, Collection<String> dirNames) {
        Matcher pm = PART.matcher(name);
        if (pm.matches()) {
            String prefix = pm.group(1).toLowerCase(Locale.ROOT);
            long mine = Long.parseLong(pm.group(2));
            for (String other : dirNames) {
                Matcher om = PART.matcher(other);
                if (om.matches() && om.group(1).toLowerCase(Locale.ROOT).equals(prefix)
                        && Long.parseLong(om.group(2)) < mine) {
                    return false;
                }
            }
            return true;
        }
        return PLAIN.matcher(name).matches();
    }

    /** Base name used for display and for matching sidecar files. */
    public static String baseName(String firstVolume) {
        Matcher pm = PART.matcher(firstVolume);
        if (pm.matches()) return pm.group(1);
        Matcher m = PLAIN.matcher(firstVolume);
        if (m.matches()) return m.group(1);
        return firstVolume;
    }

    /** Ordered list of volume names of the set that starts with firstVolume. */
    public static List<String> volumeNames(String firstVolume, Collection<String> dirNames) {
        final List<String> out = new ArrayList<>();
        Matcher pm = PART.matcher(firstVolume);
        if (pm.matches()) {
            final String prefix = pm.group(1).toLowerCase(Locale.ROOT);
            final List<long[]> tmp = new ArrayList<>();
            final List<String> names = new ArrayList<>();
            for (String n : dirNames) {
                Matcher m = PART.matcher(n);
                if (m.matches() && m.group(1).toLowerCase(Locale.ROOT).equals(prefix)) {
                    tmp.add(new long[]{Long.parseLong(m.group(2)), names.size()});
                    names.add(n);
                }
            }
            Collections.sort(tmp, new Comparator<long[]>() {
                @Override
                public int compare(long[] a, long[] b) {
                    return Long.compare(a[0], b[0]);
                }
            });
            for (long[] t : tmp) out.add(names.get((int) t[1]));
            return out;
        }
        Matcher m = PLAIN.matcher(firstVolume);
        if (!m.matches()) {
            out.add(firstVolume);
            return out;
        }
        final String base = m.group(1).toLowerCase(Locale.ROOT);
        Pattern vol = Pattern.compile("^" + Pattern.quote(base) + "\\.([r-z])(\\d{2})$", Pattern.CASE_INSENSITIVE);
        final List<String[]> vols = new ArrayList<>();
        for (String n : dirNames) {
            Matcher vm = vol.matcher(n);
            if (vm.matches()) vols.add(new String[]{vm.group(1).toLowerCase(Locale.ROOT), vm.group(2), n});
        }
        Collections.sort(vols, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                int c = a[0].compareTo(b[0]);
                return c != 0 ? c : Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
            }
        });
        out.add(firstVolume);
        for (String[] v : vols) out.add(v[2]);
        return out;
    }

    // ---------------------------------------------------------------- indexing

    public static Result index(List<String> volumes, Opener opener) throws IOException {
        if (volumes.isEmpty()) throw new IOException("No RAR volumes found");

        Entry target;
        List<Segment> segs = new ArrayList<>();
        long total = 0;

        Reader r0 = opener.open(volumes.get(0));
        try {
            List<Entry> entries = parseVolume(r0, null);
            target = chooseTarget(entries);
        } finally {
            r0.close();
        }
        if (target == null) throw new IOException("No file found in the RAR archive");
        if (target.encrypted) throw new IOException("Encrypted RAR archives are not supported");
        if (target.method != 0) {
            throw new IOException("This RAR archive is compressed. Only archives stored without compression can be streamed.");
        }

        segs.add(new Segment(volumes.get(0), target.dataOffset, target.dataSize, 0));
        total = target.dataSize;
        boolean more = target.splitAfter;
        int vi = 1;
        while (more) {
            if (vi >= volumes.size()) throw new IOException("Missing RAR volume after " + volumes.get(volumes.size() - 1));
            String vn = volumes.get(vi);
            Reader r = opener.open(vn);
            Entry part = null;
            try {
                List<Entry> es = parseVolume(r, target.name);
                if (!es.isEmpty()) part = es.get(es.size() - 1);
            } finally {
                r.close();
            }
            if (part == null) throw new IOException("Missing data in RAR volume " + vn);
            segs.add(new Segment(vn, part.dataOffset, part.dataSize, total));
            total += part.dataSize;
            more = part.splitAfter;
            vi++;
        }
        if (total < target.unpSize) throw new IOException("Incomplete RAR archive (missing volumes)");

        String shown = target.name;
        int z = shown.indexOf('\0');
        if (z >= 0) shown = shown.substring(0, z);
        shown = shown.replace('\\', '/');
        int slash = shown.lastIndexOf('/');
        if (slash >= 0) shown = shown.substring(slash + 1);
        return new Result(shown, total, segs);
    }

    private static Entry chooseTarget(List<Entry> entries) {
        Entry best = null;
        for (Entry e : entries) {
            if (e.dir) continue;
            if (best == null) {
                best = e;
                continue;
            }
            boolean ev = isVideoName(e.name), bv = isVideoName(best.name);
            if (ev && !bv) best = e;
            else if (ev == bv && e.unpSize > best.unpSize) best = e;
        }
        return best;
    }

    // ---------------------------------------------------------------- parsing

    private static int readFully(Reader r, long pos, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = r.read(pos + total, buf, total, len - total);
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    /** Parses block headers. If stopName != null, returns as soon as that file's entry is found. */
    private static List<Entry> parseVolume(Reader r, String stopName) throws IOException {
        byte[] sig = new byte[8];
        int n = readFully(r, 0, sig, 8);
        if (n < 7 || sig[0] != 0x52 || sig[1] != 0x61 || sig[2] != 0x72 || sig[3] != 0x21 || sig[4] != 0x1A || sig[5] != 0x07) {
            throw new IOException("Not a RAR file");
        }
        if (sig[6] == 0) return parseRar4(r, stopName);
        if (sig[6] == 1 && n >= 8 && sig[7] == 0) return parseRar5(r, stopName);
        throw new IOException("Unsupported RAR version");
    }

    private static int u8(byte[] b, int p) {
        return b[p] & 0xFF;
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }

    private static List<Entry> parseRar4(Reader r, String stopName) throws IOException {
        List<Entry> out = new ArrayList<>();
        long len = r.length();
        long pos = 7;
        byte[] head = new byte[7];
        while (pos + 7 <= len) {
            if (readFully(r, pos, head, 7) < 7) break;
            int type = u8(head, 2);
            int flags = u16(head, 3);
            int headSize = u16(head, 5);
            if (headSize < 7) break;
            if (type == 0x73 && (flags & 0x0080) != 0) throw new IOException("Encrypted RAR headers are not supported");
            if (type == 0x7B) break;
            if (type == 0x74) {
                byte[] h = new byte[headSize];
                if (readFully(r, pos, h, headSize) < headSize) break;
                Entry e = new Entry();
                long packSize = u32(h, 7);
                long unpSize = u32(h, 11);
                e.method = u8(h, 25) - 0x30; // RAR4: 0x30 = store, 0x31..0x35 = compressed
                int nameSize = u16(h, 26);
                int p = 32;
                if ((flags & 0x100) != 0) {
                    packSize |= u32(h, p) << 32;
                    unpSize |= u32(h, p + 4) << 32;
                    p += 8;
                }
                if (p + nameSize > headSize) break;
                e.name = new String(h, p, nameSize, StandardCharsets.ISO_8859_1);
                e.dir = (flags & 0xE0) == 0xE0;
                e.encrypted = (flags & 0x04) != 0;
                e.splitAfter = (flags & 0x02) != 0;
                e.unpSize = unpSize;
                e.dataOffset = pos + headSize;
                e.dataSize = packSize;
                out.add(e);
                if (stopName != null && !e.dir && e.name.equals(stopName)) return out;
                pos += headSize + packSize;
            } else {
                long add = 0;
                if ((flags & 0x8000) != 0) {
                    byte[] h = new byte[11];
                    if (readFully(r, pos, h, 11) < 11) break;
                    add = u32(h, 7);
                }
                pos += headSize + add;
            }
        }
        return out;
    }

    private static final class VInt {
        long value;
        int len;
    }

    private static VInt vint(byte[] b, int p, int limit) throws IOException {
        VInt v = new VInt();
        int shift = 0;
        long val = 0;
        int i = p;
        while (i < limit) {
            int x = b[i++] & 0xFF;
            val |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                v.value = val;
                v.len = i - p;
                return v;
            }
            shift += 7;
            if (shift > 63) break;
        }
        throw new IOException("Corrupt RAR5 header");
    }

    private static List<Entry> parseRar5(Reader r, String stopName) throws IOException {
        List<Entry> out = new ArrayList<>();
        long len = r.length();
        long pos = 8;
        byte[] first = new byte[16];
        while (pos + 8 <= len) {
            int got = readFully(r, pos, first, 16);
            if (got < 6) break;
            VInt hs = vint(first, 4, got);
            int sizeFieldLen = hs.len;
            long headerSize = hs.value;
            if (headerSize <= 0 || headerSize > (4L << 20)) break;
            int total = (int) (4 + sizeFieldLen + headerSize);
            byte[] h = new byte[total];
            if (readFully(r, pos, h, total) < total) break;
            int p = 4 + sizeFieldLen;
            int end = total;
            VInt v = vint(h, p, end);
            long type = v.value;
            p += v.len;
            v = vint(h, p, end);
            long flags = v.value;
            p += v.len;
            long extraSize = 0;
            long dataSize = 0;
            if ((flags & 0x01) != 0) {
                v = vint(h, p, end);
                extraSize = v.value;
                p += v.len;
            }
            if ((flags & 0x02) != 0) {
                v = vint(h, p, end);
                dataSize = v.value;
                p += v.len;
            }
            long dataStart = pos + total;
            if (type == 5) break;
            if (type == 4) throw new IOException("Encrypted RAR headers are not supported");
            if (type == 2) {
                Entry e = new Entry();
                v = vint(h, p, end);
                long fileFlags = v.value;
                p += v.len;
                v = vint(h, p, end);
                e.unpSize = v.value;
                p += v.len;
                v = vint(h, p, end); // attributes
                p += v.len;
                if ((fileFlags & 0x02) != 0) p += 4;
                if ((fileFlags & 0x04) != 0) p += 4;
                v = vint(h, p, end);
                long compInfo = v.value;
                p += v.len;
                v = vint(h, p, end); // host os
                p += v.len;
                v = vint(h, p, end);
                int nameLen = (int) v.value;
                p += v.len;
                if (p + nameLen > end) break;
                e.name = new String(h, p, nameLen, StandardCharsets.UTF_8);
                e.dir = (fileFlags & 0x01) != 0;
                e.method = (int) ((compInfo >> 7) & 7);
                e.splitAfter = (flags & 0x10) != 0;
                e.dataOffset = dataStart;
                e.dataSize = dataSize;
                // extra area: look for the file encryption record (type 1)
                int ep = end - (int) extraSize;
                while (extraSize > 0 && ep < end) {
                    VInt sz = vint(h, ep, end);
                    int recStart = ep + sz.len;
                    if (sz.value <= 0 || recStart + sz.value > end) break;
                    VInt t = vint(h, recStart, end);
                    if (t.value == 1) e.encrypted = true;
                    ep = recStart + (int) sz.value;
                }
                out.add(e);
                if (stopName != null && !e.dir && e.name.equals(stopName)) return out;
            }
            pos = dataStart + dataSize;
        }
        return out;
    }
}
