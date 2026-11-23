"""Delete build (dev.peanuts.build.BuildFiles, dev.peanuts.ui.BuildDeleteUi): a built game's build out of the storage and the game back to one to build, from
Settings › Storage (one hook line in upstream's StorageUi), Settings › Library › Built games and the game's sheet. BuildFilesJvmTest drives the files part on the
JVM (javac compiles BuildFiles alone, no Android types): what a build wrote is found with its size, the .nup Build made is apart, saves and the player's own
files are refused, deletion frees what it named and prunes the empty folders a build made. The wiring part reads the call sites and the upstream names the UI
reflects on. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UP = APP / 'main/java/dev/nxnative/android'
OND = APP / 'ondevice/java/dev/nxnative/android'
UI = APP / 'main/java/dev/peanuts/ui'
SRC = APP / 'main/java/dev/peanuts/build/BuildFiles.java'
TEST = APP / 'jvmtest/java/dev/peanuts/build/BuildFilesJvmTest.java'


def read(p):
    return p.read_text(encoding='utf-8')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Files(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='buildfiles_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(SRC), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.build.BuildFilesJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')

    def test_no_android(self):
        self.assertNotIn('import android', read(SRC), 'BuildFiles compiles alone')


class Wiring(unittest.TestCase):
    def setUp(self):
        self.ui = read(UI / 'BuildDeleteUi.java')
        self.storage = read(UP / 'StorageUi.java')
        self.sheet = read(UI / 'GameSheet.java')
        self.lib = read(UI / 'LibrarySettings.java')
        self.upstream = read(UI / 'Upstream.java')

    def test_three_ways_in(self):
        # Settings › Storage: one line, after upstream's own per-game buttons, marked
        self.assertIn('dev.peanuts.ui.BuildDeleteUi.section(a, box, d);', self.storage)
        for line in self.storage.splitlines():
            if 'BuildDeleteUi' in line:
                self.assertIn('PEANUTS', line, 'the hook line carries its marker')
        self.assertEqual(self.storage.count('BuildDeleteUi'), 1, 'one hook line in StorageUi')
        # the game's sheet: under Rebuild, for a built game, off while it builds
        self.assertIn('if (ready) {', self.sheet)
        self.assertIn('PUi.button(a, "Delete the build…", PUi.Kind.QUIET, Haptics.on(Haptics.Word.TICK, v -> BuildDeleteUi.delete(a, title, () -> { if (dialog != null) dialog.dismiss(); })))', self.sheet)
        self.assertIn('del.setEnabled(!building);', self.sheet)
        # Settings › Library: its own row, placed in the Space group and explained
        self.assertIn('"Built games", Haptics.on(Haptics.Word.TICK, v -> BuildDeleteUi.open(a))', self.lib)
        self.assertIn('r("Built games", "build")', read(UI / 'SettingsSections.java'))
        self.assertIn('put(m, "Built games", ', read(UI / 'SettingsHelp.java'))

    def test_asks_and_resets(self):
        # the question names what goes (BuildFiles.confirm), the .nup is a tick box off by default, Cancel has the focus
        self.assertIn('BuildFiles.confirm(g, f, false)', self.ui)
        self.assertIn('nup.setChecked(false);', self.ui)
        self.assertIn('if (n != null) n.requestFocus();', self.ui)
        # refused while a build, a game, Prepare or a pack export runs; checked again right before the deletion
        for s in ('BuildPipeline.running() != 0', 'lib.building(titleId)', 'endsWith(":game")', 'endsWith(":pack")', 'Upstream.packBusy(titleId)', 'dev.peanuts.pack.PackImport.busy(titleId)'):
            self.assertIn(s, self.ui, s)
        run = self.ui.split('private static void run(')[1].split('\n    }\n')[0]
        self.assertIn('busy(a, t.titleId, t.name)', run)
        # the state after the files: the list entries, the picked pack folder, the remembered job, then the library's own scan
        reset = self.ui.split('static void reset(')[1].split('\n    }\n')[0]
        self.assertIn('Upstream.removeEntries(c, e -> e.titleId.equalsIgnoreCase(t.titleId)', reset)
        self.assertIn('Upstream.forgetFolder(c, "pack_" + t.titleId)', reset)
        self.assertIn('Upstream.forgetJob(c)', reset)
        self.assertIn('Library.rescan(a);', run)
        self.assertIn('GameSheet.closeIfOpen(t.titleId);', run)

    def test_saves_protected_everywhere(self):
        game = self.ui.split('static BuildFiles.Game game(')[1].split('\n    }\n')[0]
        self.assertIn('Upstream.saveOf(c, e)', game)
        self.assertIn('Upstream.pickedFolder(c, "save_" + t.titleId)', game)
        self.assertIn('g.saves.add(new File(g.appExt, "saves/" + t.titleId))', game)
        self.assertIn('g.roots.addAll(PeanutsPrefs.roots(c));', game)

    def test_upstream_names_it_reflects_on(self):
        """Upstream.java reads these by name: each still exists upstream with these parameters"""
        want = {
            UP / 'AppStorage.java': [r'static File root\(Context c\)'],
            UP / 'Game.java': [r'static List<Game> load\(Context c\)', r'static void save\(Context c, List<Game> games\)', r'final String titleId, name, gameDir, packDir;'],
            UP / 'Folders.java': [r'static File save\(Context a, Game g\)'],
            UP / 'Prefs.java': [r'static String folder\(Context c, String key\)', r'static void folder\(Context c, String key, String path\)'],
            UP / 'PackUi.java': [r'static synchronized boolean busy\(String titleId\)'],
            OND / 'Job.java': [r'static File remembered\(Context c\)', r'static void remember\(Context c, File jobFile\)'],
            UP / 'MainActivity.java': [r'\n    void refresh\(\) \{'],
        }
        for f, pats in want.items():
            src = read(f)
            for p in pats:
                self.assertRegex(src, p, f'{f.name}: {p}')
        for name in ('"AppStorage"', '"Folders"', '"Prefs"', '"PackUi"', '"Job"', '"MainActivity"'):
            self.assertIn('P + ' + name, self.upstream)

    def test_places_match_the_writers(self):
        """BuildFiles names folders other code writes: the names still match"""
        src = read(SRC)
        self.assertIn('public static final String WORK = "-work";', src)
        self.assertIn('return new File(parent != null ? parent : dump, titleId + "-work");', read(OND / 'DumpCheck.java'))
        self.assertIn('"split.scan/" + titleId()', read(OND / 'Job.java'))
        self.assertIn('"ue/" + titleId()', read(OND / 'Job.java'))
        self.assertIn('new File(own, "xlate/" + titleId)', read(OND / 'Outputs.java'))
        self.assertIn('"build-logs")', read(APP / 'main/java/dev/peanuts/build/BuildLogShare.java'))
        self.assertIn('new File(r.ext, "temp/" + g.titleId)', read(UP / 'StorageScan.java'))
        self.assertIn('new File(r.files, "aoc/" + g.titleId)', read(UP / 'StorageScan.java'))
        modded = read(APP / 'main/java/dev/peanuts/build/ModdedBuild.java')
        self.assertIn('new File(gameHome, variantId + "-work")', modded)
        self.assertIn('IN_BUILD_FOLDER = java.util.regex.Pattern.compile("mod-[0-9a-f]{6,}")', modded)
        self.assertIn('"([0-9A-Fa-f]{16})-mod-[0-9a-f]{6,}"', modded)
        # the library's rule for a built game: what Delete build takes away
        scanner = read(UI / 'LibraryScanner.java')
        self.assertIn('for (Upstream.Entry e : list) if (e.ready && samePath(e.gameDir, t.launch.getPath())) return new File(e.packDir);', scanner)
        self.assertIn('File[] kids = new File(h, t.titleId + "-work").listFiles(File::isDirectory);', scanner)
        self.assertIn('File built = new File(dir, "pack");', scanner)

    def test_documented(self):
        for f, s in ((REPO / 'PEANUTS.md', 'BuildDeleteUi'), (UI / 'HOOKS.md', 'BuildDeleteUi'), (REPO / 'peanuts/UPSTREAM-EDITS.md', 'StorageUi.java'),
                     (REPO / 'peanuts/docs/FAQ.md', 'Delete the build'), (REPO / 'README.md', 'Built games')):
            self.assertIn(s, read(f), f'{f.name} mentions {s}')


if __name__ == '__main__':
    unittest.main()
