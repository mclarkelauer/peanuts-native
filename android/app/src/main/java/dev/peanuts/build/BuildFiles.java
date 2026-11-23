package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a game's Build wrote, so that Delete build (dev.peanuts.ui.BuildDeleteUi: Settings › Storage, Settings › Library › Built games, the game's sheet) can
 * name every piece with its size, remove it, and leave the game as it was before its first Build. The library then lists the game as one to build again by
 * its own rule (LibraryScanner.packOf): no list entry of upstream's that is ready, no pack.tsv under a &lt;title ID&gt;-work folder beside the game, no complete pack
 * in a build folder. The caller removes the list entries; this class removes the files.
 * <ul>
 * <li>What goes: the work folders (&lt;home&gt;/&lt;title ID&gt;-work, its parent's, the one beside the file upstream starts, DumpCheck.workOf's rule, and a build folder's:
 *     the rewritten code, the split and translated shaders, the Prepare job and its logs, the companion installed beside them), the pack (only one in the app's own
 *     storage, in a work folder or in a build folder's &lt;title ID&gt;/: a pack elsewhere was picked or copied by the player and stays), the builds with code mods
 *     (ModdedBuild's places), the app's own files for the game (temp/&lt;id&gt;, its run and build logs, aoc/&lt;id&gt;, the packer's kept split scan, translated shaders and
 *     decoded Unreal archives in the no-backup folder), the companion package when it sits outside the work folder.</li>
 * <li>Separately, on the player's own say: the .nup Build made from their NSP/XCI dumps, only while those dumps are still there (a .nup the player imported
 *     themselves, with no original beside it, is their game file and never offered).</li>
 * <li>Never: saves (the app's save roots, a picked save folder, and anything inside, holding or named after one, as StorageScan refuses them), the player's dump
 *     folder or dumps, the file upstream starts (unless it is that .nup and the player chose it), a library root. Checked when a place is listed and again
 *     right before it is deleted. Deletion does not follow symbolic links.</li>
 * </ul>
 * No Android classes: BuildFilesJvmTest (peanuts/tests/test_build_delete.py) runs it on the JVM.
 */
public final class BuildFiles {
    private BuildFiles() {}

    public enum Kind {
        WORK("Working files", "The rewritten code, the split and translated shaders, the build's job and logs, and the companion installed beside them."),
        PACK("Pack", "The built game: its code and its shader library."),
        MODS("Builds with mods", "The separate builds made with code mods; they play from this game's shaders."),
        APP("The app's own files for this game", "Its temporary files and pipeline cache, what the packer kept between builds, its run and build logs, its DLC links."),
        NUP("The .nup made from your dumps", "Build imports it again from your dumps the next time.");

        public final String title, about;
        Kind(String title, String about) { this.title = title; this.about = about; }
    }

    /** One file or folder that can go */
    public static final class Place {
        public final Kind kind;
        public final File file;
        public final String label;
        public final long bytes;
        Place(Kind kind, File file, String label, long bytes) { this.kind = kind; this.file = file; this.label = label; this.bytes = bytes; }
        @Override public String toString() { return kind + " " + label + " " + bytes; }
    }

    /** One game as the caller knows it: the library's title, the preferences, upstream's list. Nothing here reads a file */
    public static final class Game {
        public final String titleId, name;
        /** What upstream starts: the dump folder or the .nup; null before the import */
        public File launch;
        /** The folder that holds the game and its &lt;title ID&gt;-work */
        public File home;
        /** The .nup Build made from the originals; null when the base is the player's own file */
        public File nup;
        /** The player's dump folder when the base is one */
        public File dumpFolder;
        /** The companion package installed for the title (&lt;companion root&gt;/&lt;package&gt;), when known */
        public File companionDir;
        /** The app's own folders: the external files folder (packs/, temp/, logs/, build-logs/, saves/), the internal files folder, the no-backup folder */
        public File appExt, appFiles, appNoBackup;
        /** The player's NSP/XCI dumps the .nup was made from */
        public final List<File> originals = new ArrayList<>();
        /** More work folders and pack folders the caller knows (upstream's list entries of the title) */
        public final List<File> workDirs = new ArrayList<>(), packDirs = new ArrayList<>();
        /** &lt;build folder&gt;/&lt;title ID&gt; of every build folder */
        public final List<File> buildGameDirs = new ArrayList<>();
        /** Save folders beyond the app's own save roots: the picked one, the title's default, old defaults */
        public final List<File> saves = new ArrayList<>();
        /** The folders the library scans: never deleted or pruned */
        public final List<File> roots = new ArrayList<>();
        public Game(String titleId, String name) { this.titleId = titleId.toUpperCase(Locale.ROOT); this.name = name == null || name.isEmpty() ? this.titleId : name; }
    }

    /** What a Build wrote: what goes, the .nup that may go, and what stays with why */
    public static final class Found {
        public final List<Place> places = new ArrayList<>();
        public Place nup;
        public final List<String> kept = new ArrayList<>();
        public long total() { return BuildFiles.total(places); }
        public long total(boolean withNup) { return total() + (withNup && nup != null ? nup.bytes : 0); }
        /** Nothing of a build is there */
        public boolean empty() { return places.isEmpty() && nup == null; }
    }

    /** The result of a deletion: bytes freed, what was deleted, and a line for each place that was refused or not fully deleted */
    public static final class Result {
        public long freed;
        public final List<File> deleted = new ArrayList<>();
        public final List<String> problems = new ArrayList<>();
    }

    // ---- The scan ----

    public static final String WORK = "-work";

    private static final String[] SAVE_ROOTS = {"saves", "save", "save-backups", "save-import"};

    /** The work folders a build of this game may have used, in the order they are looked for */
    static List<File> workDirs(Game g) {
        LinkedHashSet<File> out = new LinkedHashSet<>();
        if (g.home != null) {
            out.add(new File(g.home, g.titleId + WORK));
            File p = g.home.getAbsoluteFile().getParentFile();
            if (p != null) out.add(new File(p, g.titleId + WORK));
        }
        if (g.launch != null) {   // DumpCheck.workOf: <the game file's folder>/<title ID>-work
            File p = g.launch.getAbsoluteFile().getParentFile();
            if (p != null) out.add(new File(p, g.titleId + WORK));
        }
        for (File b : g.buildGameDirs) out.add(new File(b, g.titleId + WORK));
        out.addAll(g.workDirs);
        return new ArrayList<>(out);
    }

    /** Where a pack of this game is the app's own: the app's packs/, a build folder's &lt;title ID&gt;/, or a work folder */
    static boolean ownPack(Game g, File pack, List<File> work) {
        if (g.appExt != null && within(pack, new File(g.appExt, "packs"))) return true;
        for (File b : g.buildGameDirs) if (within(pack, b)) return true;
        for (File w : work) if (within(pack, w)) return true;
        return false;
    }

    public static Found find(Game g) {
        Found f = new Found();
        Set<String> seen = new HashSet<>();
        List<File> work = workDirs(g);
        for (File w : work) add(g, f, Kind.WORK, w, seen);
        // the pack: only the app's own places, never a folder the player picked or copied
        LinkedHashSet<File> packs = new LinkedHashSet<>(g.packDirs);
        if (g.appExt != null) packs.add(new File(new File(g.appExt, "packs"), g.titleId));
        for (File b : g.buildGameDirs) packs.add(new File(b, "pack"));
        for (File p : packs) {
            if (p == null || !p.exists()) continue;
            boolean inWork = false;
            for (File w : work) if (within(p, w)) inWork = true;
            if (inWork) continue;   // goes with its work folder, or stays with it
            if (!ownPack(g, p, work)) { f.kept.add(p + ": a pack folder you picked or copied, not one the app made; it stays, and the game plays from it"); continue; }
            add(g, f, Kind.PACK, p, seen);
        }
        // builds with code mods (ModdedBuild): <app packs>/<id>-mod-<hash>, <home>/<id>-mod-<hash>-work, <build folder>/<id>/mod-<hash>
        Pattern inApp = Pattern.compile("(?i)" + g.titleId + "-mod-[0-9a-f]{6,}"), beside = Pattern.compile("(?i)" + g.titleId + "-mod-[0-9a-f]{6,}" + WORK), inBuild = Pattern.compile("mod-[0-9a-f]{6,}");
        if (g.appExt != null) for (File k : list(new File(g.appExt, "packs"))) if (inApp.matcher(k.getName()).matches()) add(g, f, Kind.MODS, k, seen);
        for (File h : new File[] {g.home, g.home == null ? null : g.home.getAbsoluteFile().getParentFile()})
            for (File k : list(h)) if (beside.matcher(k.getName()).matches()) add(g, f, Kind.MODS, k, seen);
        for (File b : g.buildGameDirs) for (File k : list(b)) if (inBuild.matcher(k.getName()).matches()) add(g, f, Kind.MODS, k, seen);
        // the app's own files for the game
        if (g.appExt != null) {
            add(g, f, Kind.APP, new File(g.appExt, "temp/" + g.titleId), seen);
            for (File l : list(new File(g.appExt, "logs"))) if (l.getName().toUpperCase(Locale.ROOT).startsWith(g.titleId + "-")) add(g, f, Kind.APP, l, seen);
            for (File l : list(new File(g.appExt, "build-logs"))) if (l.getName().toUpperCase(Locale.ROOT).startsWith(g.titleId + ".")) add(g, f, Kind.APP, l, seen);
        }
        if (g.appFiles != null) add(g, f, Kind.APP, new File(g.appFiles, "aoc/" + g.titleId), seen);
        if (g.appNoBackup != null) for (String n : new String[] {"split.scan", "xlate", "ue"}) add(g, f, Kind.APP, new File(g.appNoBackup, n + "/" + g.titleId), seen);
        // the companion, when it is not inside a work folder that goes
        if (g.companionDir != null) {
            boolean inWork = false;
            for (File w : work) if (within(g.companionDir, w)) inWork = true;
            if (!inWork) add(g, f, Kind.WORK, g.companionDir, seen);
        }
        // the .nup made from the player's dumps: offered apart, and only while the dumps are there to make it again
        if (g.nup != null && g.nup.isFile() && !g.originals.isEmpty()) {
            boolean there = false;
            for (File o : g.originals) if (o.isFile()) there = true;
            if (there) {
                String why = refuse(g, g.nup, true);
                if (why == null) f.nup = new Place(Kind.NUP, g.nup, label(g, g.nup), size(g.nup));
                else f.kept.add(g.nup + ": " + why);
            }
        }
        return f;
    }

    private static void add(Game g, Found f, Kind k, File file, Set<String> seen) {
        if (file == null || !file.exists() && !Files.isSymbolicLink(file.toPath())) return;
        String key;
        try { key = file.getCanonicalPath(); } catch (IOException e) { key = file.getAbsolutePath(); }
        if (!seen.add(key)) return;
        String why = refuse(g, file, false);
        if (why != null) { f.kept.add(file + ": " + why); return; }
        f.places.add(new Place(k, file, label(g, file), size(file)));
    }

    // ---- The guard ----

    private static String canon(File f) throws IOException { return f.getCanonicalPath(); }

    private static boolean inside(String child, String parent) { return child.startsWith(parent.endsWith(File.separator) ? parent : parent + File.separator); }

    /** Whether f is dir or inside it (by canonical paths; false when one cannot be resolved) */
    static boolean within(File f, File dir) {
        if (f == null || dir == null) return false;
        try { String a = canon(f), b = canon(dir); return a.equals(b) || inside(a, b); } catch (IOException e) { return false; }
    }

    /** The save folders that are never touched: the app's save roots and the ones the caller named */
    static List<File> saveRoots(Game g) {
        List<File> out = new ArrayList<>();
        for (File r : new File[] {g.appExt, g.appFiles}) if (r != null) for (String n : SAVE_ROOTS) out.add(new File(r, n));
        out.addAll(g.saves);
        return out;
    }

    /**
     * null when f may be deleted, else why not: a library root, a save (the folder, inside it, holding it, or a working sibling named after it), the player's
     * dumps or dump folder, the game file upstream starts (unless it is the .nup Build made and the player chose it: withNup). Compared by canonical paths; a path
     * that cannot be resolved is refused.
     */
    public static String refuse(Game g, File f, boolean withNup) {
        if (f == null) return "no path";
        String c;
        try { c = canon(f); } catch (IOException e) { return "cannot resolve " + f; }
        File cf = new File(c);
        if (cf.getParent() == null) return "a storage root";
        for (File r : g.roots) if (sameFile(cf, r)) return "a folder the library scans";
        String name = cf.getName(), parent = cf.getParent();
        for (File s : saveRoots(g)) {
            String sc;
            try { sc = canon(s); } catch (IOException e) { return "a save folder cannot be resolved: " + s; }
            if (c.equals(sc) || inside(c, sc)) return "a save";
            if (inside(sc, c)) return "holds a save";
            File sf = new File(sc);
            if (parent.equals(sf.getParent()) && name.startsWith(sf.getName())) return "beside a save, named after it";
        }
        List<File> own = new ArrayList<>(g.originals);
        if (g.dumpFolder != null) own.add(g.dumpFolder);
        for (File o : own) {
            if (within(cf, o)) return "your own game files";
            if (within(o, cf)) return "holds your own game files";
        }
        if (g.launch != null && g.launch.exists()) {   // a game file that is gone protects nothing
            boolean theNup = withNup && g.nup != null && sameFile(g.launch, g.nup);
            if (!theNup) {
                if (within(cf, g.launch)) return "the game file the app starts";
                if (within(g.launch, cf)) return "holds the game file the app starts";
            }
        }
        if (g.nup != null && !withNup && sameFile(cf, g.nup)) return "the .nup (kept unless you choose to delete it)";
        return null;
    }

    private static boolean sameFile(File a, File b) {
        try { return canon(a).equals(canon(b)); } catch (IOException e) { return a.getAbsoluteFile().equals(b.getAbsoluteFile()); }
    }

    // ---- Sizes and deletion (no link followed) ----

    public static long size(File f) {
        Path p = f.toPath();
        if (Files.isSymbolicLink(p)) return 0;
        if (!Files.isDirectory(p)) return f.length();
        long[] n = {0};
        try {
            Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path x, BasicFileAttributes a) { if (a.isRegularFile()) n[0] += a.size(); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(Path x, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { /* what was counted */ }
        return n[0];
    }

    private static void deleteTree(Path p) throws IOException {
        if (Files.isSymbolicLink(p) || !Files.isDirectory(p)) { Files.deleteIfExists(p); return; }
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {   // walkFileTree does not follow links: a link inside is visited as a file and removed itself
            @Override public FileVisitResult visitFile(Path x, BasicFileAttributes a) throws IOException { Files.deleteIfExists(x); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { if (e != null) throw e; Files.deleteIfExists(d); return FileVisitResult.CONTINUE; }
        });
    }

    /**
     * Deletes the places (each checked by the guard again first), the .nup too when withNup and found names one, then removes the folders a build made that are
     * empty now: each build folder's &lt;title ID&gt;/ and the game's home folder (the .nup's folder under the game folder), never a library root or a folder that
     * still holds something.
     */
    public static Result delete(Game g, Found found, List<Place> picked, boolean withNup) {
        Result res = new Result();
        List<Place> l = new ArrayList<>(picked);
        if (withNup && found.nup != null) l.add(found.nup);
        Set<String> done = new HashSet<>();
        for (Place it : l) {
            String key;
            try { key = canon(it.file); } catch (IOException e) { key = it.file.getAbsolutePath(); }
            if (!done.add(key)) continue;
            String why = refuse(g, it.file, withNup && it.kind == Kind.NUP);
            if (why != null) { res.problems.add("not deleted (" + why + "): " + it.label); continue; }
            try {
                deleteTree(it.file.toPath());
                res.freed += it.bytes;
                res.deleted.add(it.file);
            } catch (IOException | SecurityException e) {
                res.problems.add("not all of " + it.label + " could be deleted: " + e.getMessage());
            }
        }
        List<File> prune = new ArrayList<>(g.buildGameDirs);
        if (g.home != null) prune.add(g.home);
        for (File d : prune) {
            if (d == null || !d.isDirectory()) continue;
            String[] kids = d.list();
            if (kids == null || kids.length > 0) continue;
            boolean root = false;
            for (File r : g.roots) if (sameFile(d, r)) root = true;
            if (root || refuse(g, d, withNup) != null) continue;
            if (d.delete()) res.deleted.add(d);
        }
        return res;
    }

    // ---- Words ----

    public static long total(List<Place> l) { long n = 0; for (Place i : l) n += i.bytes; return n; }

    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1L << 20) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1L << 30) return String.format(Locale.ROOT, "%.1f MB", b / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.2f GB", b / (double) (1L << 30));
    }

    /** A place's name for the list: inside the app's folders as "the app's storage/…", else its path */
    static String label(Game g, File f) {
        String[][] roots = {{"the app's storage", path(g.appExt)}, {"the app's files", path(g.appFiles)}, {"the app's files", path(g.appNoBackup)}};
        String p = f.getAbsolutePath();
        for (String[] r : roots) if (r[1] != null && p.startsWith(r[1] + File.separator)) return r[0] + "/" + p.substring(r[1].length() + 1);
        return p;
    }

    private static String path(File f) { return f == null ? null : f.getAbsolutePath(); }

    /** The .nup row's own words: the file, its size, and the dumps it is made from again */
    public static String nupLine(Game g, Found f) {
        if (f.nup == null) return null;
        List<String> names = new ArrayList<>();
        for (File o : g.originals) if (o.isFile()) names.add(o.getName());
        return "Also delete the .nup made from your dumps (" + f.nup.file.getName() + ", " + bytes(f.nup.bytes) + "). The next Build imports it again from " + String.join(", ", names) + ".";
    }

    /** What the confirmation names: every place that goes with its size, the total, what stays, and what the game becomes */
    public static String confirm(Game g, Found f, boolean withNup) {
        StringBuilder m = new StringBuilder("Deleted:\n");
        List<Place> l = new ArrayList<>(f.places);
        if (withNup && f.nup != null) l.add(f.nup);
        for (Place it : l) m.append("· ").append(it.label).append(" (").append(bytes(it.bytes)).append(")\n");
        m.append("\n").append(bytes(total(l))).append(" in all. This cannot be undone.");
        m.append("\n\nKept: your saves");
        if (g.dumpFolder != null) m.append(", your dump folder (").append(g.dumpFolder.getName()).append(")");
        List<String> orig = new ArrayList<>();
        for (File o : g.originals) if (o.isFile()) orig.add(o.getName());
        if (!orig.isEmpty()) m.append(", your dumps (").append(String.join(", ", orig)).append(")");
        if (g.nup != null && !withNup) m.append(", the .nup (").append(g.nup.getName()).append(")");
        else if (g.launch != null && g.nup == null && g.dumpFolder == null) m.append(", your game file (").append(g.launch.getName()).append(")");
        m.append(", the game's settings and its cover.");
        for (String k : f.kept) m.append("\n· Stays: ").append(k);
        m.append("\n\n").append(g.name).append(" is then listed as a game to build, as before its first Build. Saves are never deleted here.");
        return m.toString();
    }

    private static File[] list(File d) {
        File[] l = d == null ? null : d.listFiles();
        if (l == null) return new File[0];
        Arrays.sort(l);
        return l;
    }
}
