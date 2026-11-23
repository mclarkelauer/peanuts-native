// PEANUTS: BuildFiles on the JVM (peanuts/tests/test_build_delete.py)
package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Delete build's files: a game folder with the .nup Build made and the dumps it came from, the work folder with its pack, a pack in the app's storage, a build
 * folder's pack and work folder, a build with mods, the app's own files for the game, and saves everywhere. Finds what a build wrote and nothing of the
 * player's; deletes it and leaves the game as a game to build; refuses what it must. Prints "ok N"
 */
public final class BuildFilesJvmTest {
    private static int n;
    private static final String ID = "0400A3A01EE4C000";

    private static void yes(boolean b, String what) { n++; if (!b) throw new AssertionError(what); }
    private static void eq(Object want, Object got, String what) { n++; if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError(what + ": wanted " + want + ", got " + got); }

    private static File file(File dir, String name, int bytes) throws IOException {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[bytes]);
        return f;
    }

    private static boolean has(List<BuildFiles.Place> l, File f) { for (BuildFiles.Place p : l) if (p.file.getAbsoluteFile().equals(f.getAbsoluteFile())) return true; return false; }

    private static File tmp() throws IOException { return Files.createTempDirectory("buildfiles").toFile(); }

    /** The game folder: Pokopia/Pokopia.nup made from Pokopia.nsp beside it, its work folder with the pack inside, a build with mods beside */
    private static BuildFiles.Game nupGame(File root) throws IOException {
        File games = new File(root, "Games"), home = new File(games, "Pokopia"), app = new File(root, "app"), files = new File(root, "files"), nb = new File(root, "nb");
        File nup = file(home, "Pokopia.nup", 3000), orig = file(home, "Pokopia.nsp", 5000);
        file(home, ID + "-work/ondevice/job.json", 100);
        file(home, ID + "-work/android-pack/pack.tsv", 50);
        file(home, ID + "-work/android-pack/shaders/v-abc/dmsh_vk.lib", 2000);
        file(home, ID + "-work/peanuts/Pokopia/package.json", 10);
        file(home, ID + "-mod-abcdef12-work/ondevice/job.json", 40);
        file(home, "icon.png", 7);   // the PC builder's cover beside the .nup: not a build's file
        file(app, "temp/" + ID + "/pipeline.cache", 300);
        file(app, "logs/" + ID + "-20261123-101010.log", 20);
        file(app, "logs/" + ID + "-20261123-101010.digest.txt", 5);
        file(app, "logs/0100000000010000-20261123-101010.log", 20);   // another game's run
        file(app, "build-logs/" + ID + ".log", 30);
        file(app, "saves/" + ID + "/save.bin", 999);
        file(app, "saves/" + ID + "_device/x", 1);
        file(files, "aoc/" + ID + "/link", 1);
        file(files, "icons/" + ID + ".png", 9);
        file(nb, "split.scan/" + ID + "/scan.tsv", 60);
        file(nb, "xlate/" + ID + "/r.bin", 70);
        BuildFiles.Game g = new BuildFiles.Game(ID.toLowerCase(), "Pokopia");
        g.launch = nup; g.home = home; g.nup = nup; g.originals.add(orig);
        g.appExt = app; g.appFiles = files; g.appNoBackup = nb;
        g.roots.add(games);
        return g;
    }

    public static void main(String[] args) throws Exception {
        // 1. the .nup game: what goes, what is apart, what stays
        File root = tmp();
        BuildFiles.Game g = nupGame(root);
        File home = g.home;
        BuildFiles.Found f = BuildFiles.find(g);
        yes(has(f.places, new File(home, ID + "-work")), "the work folder goes");
        yes(!has(f.places, new File(home, ID + "-work/android-pack")), "the pack inside the work folder is not listed twice");
        yes(has(f.places, new File(home, ID + "-mod-abcdef12-work")), "the build with mods goes");
        yes(has(f.places, new File(g.appExt, "temp/" + ID)), "the temporary folder goes");
        yes(has(f.places, new File(g.appExt, "logs/" + ID + "-20261123-101010.log")), "the run log goes");
        yes(has(f.places, new File(g.appExt, "logs/" + ID + "-20261123-101010.digest.txt")), "its digest goes");
        yes(!has(f.places, new File(g.appExt, "logs/0100000000010000-20261123-101010.log")), "another game's log stays");
        yes(has(f.places, new File(g.appExt, "build-logs/" + ID + ".log")), "the build log goes");
        yes(has(f.places, new File(g.appFiles, "aoc/" + ID)), "the DLC links go");
        yes(!has(f.places, new File(g.appFiles, "icons/" + ID + ".png")), "the cover stays");
        yes(has(f.places, new File(g.appNoBackup, "split.scan/" + ID)), "the kept split scan goes");
        yes(has(f.places, new File(g.appNoBackup, "xlate/" + ID)), "the kept translated shaders go");
        yes(!has(f.places, new File(g.appExt, "saves/" + ID)), "the save is never listed");
        yes(!has(f.places, g.nup), "the .nup is not among the places");
        yes(f.nup != null && f.nup.file.equals(g.nup) && f.nup.bytes == 3000, "the .nup is offered apart, with its size");
        yes(!has(f.places, g.originals.get(0)), "the dump stays");
        yes(!has(f.places, new File(home, "icon.png")), "the cover beside the .nup stays");
        eq(100 + 50 + 2000 + 10 + 40 + 300 + 20 + 5 + 30 + 1 + 60 + 70, f.total(), "the total of what goes");
        eq(f.total() + 3000, f.total(true), "the total with the .nup");
        yes(f.kept.isEmpty(), "nothing had to be kept: " + f.kept);
        String words = BuildFiles.confirm(g, f, false);
        yes(words.contains("Kept: your saves, your dumps (Pokopia.nsp), the .nup (Pokopia.nup)"), "the words say what stays: " + words);
        yes(words.contains("Pokopia is then listed as a game to build, as before its first Build."), "the words say what the game becomes");
        yes(words.contains("This cannot be undone."), "the warning");
        yes(BuildFiles.confirm(g, f, true).contains("Kept: your saves, your dumps (Pokopia.nsp), the game's settings"), "with the .nup chosen it is not among the kept");
        yes(BuildFiles.nupLine(g, f).startsWith("Also delete the .nup made from your dumps (Pokopia.nup, 3 KB). The next Build imports it again from Pokopia.nsp."), "the .nup row: " + BuildFiles.nupLine(g, f));

        // the guard, by itself
        yes(BuildFiles.refuse(g, new File(g.appExt, "saves/" + ID), false) != null, "a save is refused");
        yes(BuildFiles.refuse(g, new File(g.appExt, "saves/" + ID + "_device"), false) != null, "the device save is refused");
        yes(BuildFiles.refuse(g, new File(g.appExt, "saves"), false) != null, "the save root is refused");
        yes(BuildFiles.refuse(g, g.appExt, false) != null, "a folder holding saves is refused");
        yes(BuildFiles.refuse(g, g.originals.get(0), false) != null, "the dump is refused");
        yes(BuildFiles.refuse(g, g.nup, false) != null, "the .nup without the choice");
        yes(BuildFiles.refuse(g, g.nup, true) == null, "the .nup with the choice");
        yes(BuildFiles.refuse(g, home, false) != null, "the home folder holds the game file");
        yes(BuildFiles.refuse(g, g.roots.get(0), false) != null, "the game folder itself");
        yes(BuildFiles.refuse(g, new File("/"), false) != null, "a storage root");
        File picked = new File(root, "MySaves"); picked.mkdirs(); g.saves.add(picked);
        yes(BuildFiles.refuse(g, new File(root, "MySaves.lock"), false) != null, "a sibling named after a picked save folder");

        // 2. deleting without the .nup: the game to build again from the .nup that stays
        BuildFiles.Result r = BuildFiles.delete(g, f, f.places, false);
        yes(r.problems.isEmpty(), "no problems: " + r.problems);
        eq(f.total(), r.freed, "freed what was listed");
        yes(!new File(home, ID + "-work").exists(), "the work folder is gone");
        yes(g.nup.isFile() && g.originals.get(0).isFile(), "the .nup and the dump stay");
        yes(new File(g.appExt, "saves/" + ID + "/save.bin").isFile(), "the save stays");
        yes(new File(g.appFiles, "icons/" + ID + ".png").isFile(), "the cover stays");
        yes(home.isDirectory(), "the home folder stays while it holds the .nup");
        yes(BuildFiles.find(g).places.isEmpty() && BuildFiles.find(g).nup != null, "only the .nup is left to find");

        // 3. deleting with the .nup: the home folder is pruned once it is empty, never the game folder
        root = tmp(); g = nupGame(root); home = g.home;
        new File(home, "icon.png").delete();
        f = BuildFiles.find(g);
        r = BuildFiles.delete(g, f, f.places, true);
        yes(r.problems.isEmpty(), "no problems with the .nup: " + r.problems);
        eq(f.total(true), r.freed, "freed the .nup too");
        yes(!g.nup.exists(), "the .nup is gone");
        yes(g.originals.get(0).isFile(), "the dump stays");
        yes(home.isDirectory(), "the home folder stays: it still holds the dump");
        yes(g.roots.get(0).isDirectory(), "the game folder stays");
        // ...and when the dump lives elsewhere, the empty home goes
        root = tmp(); g = nupGame(root); home = g.home;
        File elsewhere = file(new File(root, "Dumps"), "Pokopia.nsp", 5000);
        new File(home, "Pokopia.nsp").delete(); new File(home, "icon.png").delete();
        g.originals.clear(); g.originals.add(elsewhere);
        f = BuildFiles.find(g);
        r = BuildFiles.delete(g, f, f.places, true);
        yes(r.problems.isEmpty(), "no problems: " + r.problems);
        yes(!home.exists(), "the empty home folder is pruned");
        yes(g.roots.get(0).isDirectory(), "the game folder stays");

        // 4. a dump folder: its work folder beside it (DumpCheck.workOf) goes, one inside the dump is kept with a reason, the dump never
        root = tmp();
        File dump = new File(root, "Games/DKB"), appd = new File(root, "app");
        file(dump, "base/exefs/main", 10);
        file(new File(root, "Games"), ID + "-work/android-pack/pack.tsv", 10);
        file(dump, ID + "-work/xlate2-pack/pack.tsv", 10);
        g = new BuildFiles.Game(ID, "Bananza");
        g.launch = dump; g.home = dump; g.dumpFolder = dump; g.appExt = appd; g.roots.add(new File(root, "Games"));
        f = BuildFiles.find(g);
        yes(has(f.places, new File(root, "Games/" + ID + "-work")), "the work folder beside the dump goes");
        yes(!has(f.places, new File(dump, ID + "-work")), "a work folder inside the dump is not deleted");
        eq(1, f.kept.size(), "it is named as kept: " + f.kept);
        yes(f.kept.get(0).contains("your own game files"), "with the reason: " + f.kept);
        yes(f.nup == null, "a dump has no .nup to offer");
        yes(BuildFiles.confirm(g, f, false).contains("Kept: your saves, your dump folder (DKB)"), "the words name the dump folder");
        r = BuildFiles.delete(g, f, f.places, false);
        yes(r.problems.isEmpty() && dump.isDirectory() && new File(dump, "base/exefs/main").isFile(), "the dump is untouched");

        // 5. the app's pack and a build folder's: own places go, the build folder's <ID> is pruned, a picked pack elsewhere stays
        root = tmp();
        File app5 = new File(root, "app"), build = new File(root, "sd/Builds"), bgame = new File(build, ID);
        File nup5 = file(bgame, "Zelda.nup", 100);
        file(bgame, "pack/pack.tsv", 10);
        file(bgame, ID + "-work/ondevice/job.json", 10);
        file(bgame, "mod-1234567/pack/pack.tsv", 10);
        file(app5, "packs/" + ID + "/pack.tsv", 10);
        file(app5, "packs/" + ID + "-mod-abcdef1/pack.tsv", 10);
        file(app5, "packs/0100000000010000/pack.tsv", 10);
        File mine = file(new File(root, "MacPacks"), ID + "-pack/pack.tsv", 10).getParentFile();
        g = new BuildFiles.Game(ID, "Zelda");
        g.launch = nup5; g.home = bgame; g.appExt = app5; g.buildGameDirs.add(bgame); g.packDirs.add(mine); g.roots.add(build);
        f = BuildFiles.find(g);
        yes(has(f.places, new File(bgame, "pack")), "the build folder's pack goes");
        yes(has(f.places, new File(bgame, ID + "-work")), "the build folder's work folder goes");
        yes(has(f.places, new File(bgame, "mod-1234567")), "the build folder's build with mods goes");
        yes(has(f.places, new File(app5, "packs/" + ID)), "the app's pack goes");
        yes(has(f.places, new File(app5, "packs/" + ID + "-mod-abcdef1")), "the app's build with mods goes");
        yes(!has(f.places, new File(app5, "packs/0100000000010000")), "another game's pack stays");
        yes(!has(f.places, mine), "a picked pack elsewhere is not deleted");
        yes(f.kept.size() == 1 && f.kept.get(0).contains("a pack folder you picked or copied"), "and is named as kept: " + f.kept);
        yes(f.nup == null, "a .nup without originals is the player's: not offered");
        yes(BuildFiles.confirm(g, f, false).contains("your game file (Zelda.nup)"), "the words name it as kept");
        r = BuildFiles.delete(g, f, f.places, false);
        yes(r.problems.isEmpty(), "no problems: " + r.problems);
        yes(bgame.isDirectory() && nup5.isFile(), "the build folder's game folder stays while it holds the .nup");
        yes(mine.isDirectory(), "the picked pack stays");
        nup5.delete();
        r = BuildFiles.delete(g, BuildFiles.find(g), new ArrayList<>(), false);
        yes(!bgame.exists() && build.isDirectory(), "the empty <title ID> folder is pruned, the build folder stays");

        // 6. a symbolic link inside a place is removed, never what it points to
        root = tmp();
        File target = file(new File(root, "elsewhere"), "big.bin", 500), work6 = new File(root, "Games/" + ID + "-work");
        work6.mkdirs();
        try { Files.createSymbolicLink(new File(work6, "link").toPath(), target.toPath()); } catch (UnsupportedOperationException | IOException e) { work6 = null; }
        if (work6 != null) {
            g = new BuildFiles.Game(ID, "Links");
            g.home = new File(root, "Games/x"); g.roots.add(new File(root, "Games"));
            g.workDirs.add(work6);
            f = BuildFiles.find(g);
            yes(has(f.places, work6) && BuildFiles.total(f.places) == 0, "a link counts nothing");
            r = BuildFiles.delete(g, f, f.places, false);
            yes(!work6.exists() && target.isFile(), "the link went, its target stays");
        }

        // words
        eq("512 B", BuildFiles.bytes(512), "bytes");
        eq("3 KB", BuildFiles.bytes(3000), "kilobytes");
        eq("1.5 MB", BuildFiles.bytes(1572864), "megabytes");
        eq("2.00 GB", BuildFiles.bytes(2L << 30), "gigabytes");
        eq("Working files", BuildFiles.Kind.WORK.title, "a kind's title");
        System.out.println("ok " + n);
    }

}
