import json
import pathlib
import tempfile
import unittest
import zipfile

from tools.stage_core import JAR_NAME, digest, install, save


class CoreInstallTests(unittest.TestCase):
    def prepare(self, directory):
        instance = pathlib.Path(directory)
        updates = instance / "config" / "atmpilot" / "core-updates"
        updates.mkdir(parents=True)
        target = instance / "mods" / JAR_NAME
        target.parent.mkdir()
        target.write_bytes(b"original installed core")
        staged = updates / "stage.jar"
        with zipfile.ZipFile(staged, "w") as jar:
            for name in ("META-INF/neoforge.mods.toml", "dev/atmpilot/PilotMod.class",
                         "dev/atmpilot/SurvivalReflex.class", "dev/atmpilot/TransportRules.class"):
                jar.writestr(name, "test fixture")
        sha = digest(staged)
        staged.rename(updates / (sha + ".jar"))
        manifest = updates / "test.json"
        save(manifest, {"state": "waiting_for_game_exit", "sha256": sha,
                        "previousSha256": digest(target)})
        return target, manifest, sha

    def test_atomic_install_keeps_verified_backup_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            target, manifest, sha = self.prepare(directory)
            install(manifest)
            report = json.loads(manifest.read_text())
            self.assertEqual(digest(target), sha)
            self.assertEqual(pathlib.Path(report["backup"]).read_bytes(), b"original installed core")
            self.assertEqual(report["state"], "installed_verified")
            install(manifest)
            self.assertEqual(json.loads(manifest.read_text())["state"], "already_installed")

    def test_newer_user_install_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            target, manifest, _ = self.prepare(directory)
            target.write_bytes(b"separately updated core")
            with self.assertRaisesRegex(RuntimeError, "changed since staging"):
                install(manifest)
            self.assertEqual(target.read_bytes(), b"separately updated core")

    def test_superseded_worker_cannot_install_older_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            target, manifest, _ = self.prepare(directory)
            report = json.loads(manifest.read_text())
            report["state"] = "superseded"
            save(manifest, report)
            install(manifest)
            self.assertEqual(target.read_bytes(), b"original installed core")


if __name__ == "__main__":
    unittest.main()
