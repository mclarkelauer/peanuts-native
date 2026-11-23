package dev.peanuts.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.build.BuildFiles;
import dev.peanuts.build.BuildPipeline;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Delete build: a built game's build out of the storage, and the game back to the state before its first Build, so Home lists it as a game to build again.
 * Three ways in: Settings › Storage (upstream's StorageUi, one hook line: {@link #section}, a "Built games" section with each built game's size), Settings ›
 * Library › Built games ({@link #open}, the same list as its own sheet) and the game's sheet ({@link #delete}, "Delete the build…" under Rebuild).
 * <p>
 * What goes is {@link BuildFiles}' business (found and sized off the main thread, named in the confirmation with the total, the player's own files and saves
 * never). The .nup Build made from the player's NSP/XCI dumps is a separate tick box, off by default, offered only while those dumps are still there. After the
 * files, the state: upstream's list entries of the game whose file or pack went (Game.load / Game.save under Game's lock, as Remove from list does), a picked pack
 * folder that went, Home's Prepare card when its job was this game's; then the library scans again and finds no pack (LibraryScanner.packOf), so the cover reads
 * Build. Refused while a build runs, while a game runs, or while this game's pack is being exported or imported. Every row and button takes the controller's
 * focus (PadFocus watches the window); Cancel has the focus when the question shows.
 */
public final class BuildDeleteUi {
    private BuildDeleteUi() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** The built games of the last scan */
    static List<LibraryScanner.Title> built() {
        List<LibraryScanner.Title> out = new ArrayList<>();
        for (LibraryScanner.Title t : Library.titlesNow()) if (t.status == LibraryScanner.Status.READY) out.add(t);
        return out;
    }

    /**
     * Settings › Storage's section (the hook in StorageUi.show): every built game, its name, where its build is and how much it takes (measured off the main
     * thread), a tap opens the question. host: the Storage sheet, closed first so the question stands alone
     */
    public static void section(Activity a, LinearLayout box, AlertDialog[] host) {
        List<LibraryScanner.Title> built = built();
        PUi.add(box, PUi.section(a, "Built games"), 16);
        PUi.add(box, PUi.small(a, built.isEmpty() ? "No game is built. A build's files can be deleted here once there is one."
                : "Delete a game's build to get its space back: its working files, its pack and its shaders. The game is then listed as one to build again. Your saves and your dumps stay."), 2);
        for (LibraryScanner.Title t : built) rows(a, box, t, () -> { if (host != null && host[0] != null) host[0].dismiss(); });
    }

    /** Settings › Library › Built games: the same list as its own sheet */
    public static void open(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        List<LibraryScanner.Title> built = built();
        PUi.add(box, PUi.small(a, built.isEmpty() ? "No game is built yet. Once one is, its build and the space it takes are listed here."
                : "Each built game with the space its build takes. Delete a build to get the space back: the game is then listed as one to build again, as before its first Build. Your saves and your dumps stay."), 0);
        for (LibraryScanner.Title t : built) rows(a, box, t, () -> { if (d[0] != null) d[0].dismiss(); });
        d[0] = PUi.sheet(a, "Built games", box).setNegativeButton("Close", null).show();
        Haptics.watch(box);
    }

    private static void rows(Activity a, LinearLayout box, LibraryScanner.Title t, Runnable closeHost) {
        PUi.Setting row = new PUi.Setting(a, "🧱", t.name, Haptics.on(Haptics.Word.TICK, v -> { closeHost.run(); delete(a, t, null); }));
        row.value("Measuring…").chevron(a);
        PUi.add(box, row.view, 2);
        Context app = a.getApplicationContext();
        new Thread(() -> {
            BuildFiles.Game g = game(app, t);
            BuildFiles.Found f = BuildFiles.find(g);
            String where = t.packDir != null ? t.packDir.getPath() : "";
            UI.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                row.value(PUi.joined(BuildFiles.bytes(f.total()) + (f.nup != null ? " and the .nup, " + BuildFiles.bytes(f.nup.bytes) : ""), where.isEmpty() ? null : where, "Delete build…"));
            });
        }, "peanuts-build-size").start();
    }

    /** Why nothing may be deleted now, or null */
    static String busy(Context c, String titleId, String name) {
        if (BuildPipeline.running() != 0) return "A build is running now. Wait for it to finish.";
        Library lib = Library.now();
        if (lib != null && lib.building(titleId)) return name + " is being built now. Wait for it to finish.";
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
        if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) {
            if (p.processName == null) continue;
            if (p.processName.endsWith(":game")) return "A game is running: stop it first (its in-game panel › Stop the game).";
            if (p.processName.endsWith(":pack")) return "Prepare is running: wait for it, or pause it, and close the app's Prepare first.";
        }
        if (Upstream.packBusy(titleId) || dev.peanuts.pack.PackImport.busy(titleId)) return "A pack of " + name + " is being exported or imported. Wait for it to finish.";
        return null;
    }

    /** The game as BuildFiles wants it: the title's places, the build folders, upstream's list entries of the title, the app's folders, the save folders */
    static BuildFiles.Game game(Context c, LibraryScanner.Title t) {
        BuildFiles.Game g = new BuildFiles.Game(t.titleId, t.name);
        g.launch = t.launch;
        g.home = t.home;
        if (t.base != null && t.base.isDump()) g.dumpFolder = t.base.file;
        if (t.base != null && t.base.isNup() && !t.originals.isEmpty()) g.nup = t.base.file;
        for (LibraryScanner.Source s : t.originals) g.originals.add(s.file);
        if (t.packDir != null) g.packDirs.add(t.packDir);
        g.buildGameDirs.addAll(PeanutsPrefs.buildGameDirs(c, t.titleId));
        g.roots.addAll(PeanutsPrefs.roots(c));
        g.appExt = Upstream.appRoot(c);
        g.appFiles = c.getFilesDir();
        g.appNoBackup = c.getNoBackupFilesDir();
        for (Upstream.Entry e : Upstream.listedOf(c, t.titleId)) {
            File game = new File(e.gameDir).getAbsoluteFile();
            if (game.getParentFile() != null) g.workDirs.add(new File(game.getParentFile(), t.titleId + BuildFiles.WORK));
            if (!e.packDir.isEmpty()) g.packDirs.add(new File(e.packDir));
            File save = Upstream.saveOf(c, e);
            if (save != null) g.saves.add(save);
        }
        String picked = Upstream.pickedFolder(c, "save_" + t.titleId);
        if (picked != null) g.saves.add(new File(picked));
        if (g.appExt != null) g.saves.add(new File(g.appExt, "saves/" + t.titleId));
        CompanionPort.Item companion = CompanionPort.forTitle(Library.catalogNow(), t.titleId);
        File root = t.companionRoot();
        if (companion != null && root != null) g.companionDir = new File(root, companion.packageName);
        return g;
    }

    /**
     * The question for one game, then the deletion: what goes with its size, the .nup as a tick box when Build made it, Cancel focused. closed: what to do once
     * the files went (the sheet that asked closes), or null
     */
    static void delete(Activity a, LibraryScanner.Title t, Runnable closed) {
        String why = busy(a, t.titleId, t.name);
        if (why != null) { refuse(a, why); return; }
        AlertDialog wait = new AlertDialog.Builder(a).setMessage("Looking at " + t.name + "'s build…").setCancelable(false).show();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            BuildFiles.Game g = game(app, t);
            BuildFiles.Found f = BuildFiles.find(g);
            UI.post(() -> {
                wait.dismiss();
                if (a.isFinishing() || a.isDestroyed()) return;
                if (f.empty()) {
                    StringBuilder m = new StringBuilder("Nothing of " + t.name + "'s build is in a place the app made.");
                    for (String k : f.kept) m.append("\n· ").append(k);
                    new AlertDialog.Builder(a).setTitle("Delete the build").setMessage(m).setPositiveButton("OK", null).show();
                    return;
                }
                ask(a, t, g, f, closed);
            });
        }, "peanuts-build-find").start();
    }

    private static void ask(Activity a, LibraryScanner.Title t, BuildFiles.Game g, BuildFiles.Found f, Runnable closed) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView words = PUi.body(a, BuildFiles.confirm(g, f, false));
        words.setTextIsSelectable(true);
        PUi.add(box, words, 0);
        CheckBox nup = null;
        if (f.nup != null) {
            nup = new CheckBox(a);
            nup.setText(BuildFiles.nupLine(g, f));
            nup.setChecked(false);
            CheckBox cb = nup;
            cb.setOnCheckedChangeListener((b, on) -> { Haptics.toggle(b, on); words.setText(BuildFiles.confirm(g, f, on)); });
            PUi.add(box, nup, 12);
        }
        CheckBox withNup = nup;
        AlertDialog d = PUi.sheet(a, "Delete " + t.name + "'s build?", box)
                .setPositiveButton("Delete", (x, w) -> run(a, t, g, f, withNup != null && withNup.isChecked(), closed))
                .setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> {
            Button b = d.getButton(AlertDialog.BUTTON_POSITIVE);
            if (b != null) b.setTextColor(PUi.col(a, R.color.bn_danger));
            Button n = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (n != null) n.requestFocus();
        });
        d.show();
        Haptics.watch(box);
    }

    private static void run(Activity a, LibraryScanner.Title t, BuildFiles.Game g, BuildFiles.Found f, boolean withNup, Runnable closed) {
        String why = busy(a, t.titleId, t.name);   // something may have started while the question showed
        if (why != null) { refuse(a, why); return; }
        AlertDialog wait = new AlertDialog.Builder(a).setMessage("Deleting…").setCancelable(false).show();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            BuildFiles.Result r = BuildFiles.delete(g, f, f.places, withNup);
            UI.post(() -> {
                wait.dismiss();
                if (a.isFinishing() || a.isDestroyed()) return;
                reset(app, t, g, r);
                GameSheet.closeIfOpen(t.titleId);
                if (closed != null) closed.run();
                Library.rescan(a);
                Upstream.refreshHome(a);
                String done = BuildFiles.bytes(r.freed) + " freed. " + t.name + " is listed as a game to build again.";
                if (r.problems.isEmpty()) { Haptics.play(a.getWindow().getDecorView(), Haptics.Word.DONE); Toast.makeText(a, done, Toast.LENGTH_LONG).show(); }
                else PUi.explain(a, "Not everything was deleted", done, String.join("\n", r.problems)).setPositiveButton("OK", null).show();
            });
        }, "peanuts-build-delete").start();
    }

    /**
     * The state after the files: upstream's list entries of the title whose game file or pack went, the picked pack folder when it went, Home's Prepare card when
     * its job lived in a folder that went. The library's own status needs nothing: its next scan finds no pack
     */
    static void reset(Context c, LibraryScanner.Title t, BuildFiles.Game g, BuildFiles.Result r) {
        Upstream.removeEntries(c, e -> e.titleId.equalsIgnoreCase(t.titleId) && (!new File(e.gameDir).exists() || gone(new File(e.packDir), r.deleted)));
        String pack = Upstream.pickedFolder(c, "pack_" + t.titleId);
        if (pack != null && gone(new File(pack), r.deleted)) Upstream.forgetFolder(c, "pack_" + t.titleId);
        File job = Upstream.rememberedJob(c);
        if (job != null && gone(job, r.deleted)) Upstream.forgetJob(c);
    }

    private static boolean gone(File f, List<File> deleted) {
        if (f.getPath().isEmpty()) return false;
        if (!f.exists()) for (File d : deleted) if (inside(f, d)) return true;
        return false;
    }

    private static boolean inside(File f, File dir) {
        String a = f.getAbsolutePath(), b = dir.getAbsolutePath();
        return a.equals(b) || a.startsWith(b.endsWith(File.separator) ? b : b + File.separator);
    }

    private static void refuse(Activity a, String why) {
        Haptics.play(a.getWindow().getDecorView(), Haptics.Word.REJECT);
        new AlertDialog.Builder(a).setTitle("Delete the build").setMessage(why).setPositiveButton("OK", null).show();
    }
}
