import json
import pathlib
import tempfile
import unittest
from workflow_store import WorkflowStore


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.store = WorkflowStore(self.root)

    def write(self, ticks=2):
        (self.root / "wait.json").write_text(json.dumps({"version": 1, "defaults": {"ticks": ticks},
            "steps": [{"type": "wait", "ticks": {"$param": "ticks"}}]}), encoding="utf-8")

    def test_edit_is_seen_without_restarting_store_and_old_snapshot_is_unchanged(self):
        self.write(2)
        old = self.store.load("wait")
        self.write(7)
        new = self.store.load("wait")
        self.assertEqual(2, old["steps"][0]["ticks"])
        self.assertEqual(7, new["steps"][0]["ticks"])
        self.assertNotEqual(old["sha256"], new["sha256"])

    def test_parameters_preserve_numeric_and_structured_types(self):
        self.write()
        self.assertEqual(20, self.store.load("wait", {"ticks": 20})["steps"][0]["ticks"])
        with self.assertRaisesRegex(ValueError, "Unused"):
            self.store.load("wait", {"tick": 20})

    def test_invalid_file_does_not_silently_use_stale_workflow(self):
        self.write()
        self.store.load("wait")
        (self.root / "wait.json").write_text('{"version":1,"steps":', encoding="utf-8")
        with self.assertRaises(ValueError):
            self.store.load("wait")

    def test_rejects_paths_unknown_actions_and_non_finite_numbers(self):
        for name in ("../wait", "wait.json", "C:/wait"):
            with self.assertRaises(ValueError):
                self.store.load(name)
        (self.root / "bad.json").write_text('{"version":1,"steps":[{"type":"eval"}]}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "unknown action"):
            self.store.load("bad")
        (self.root / "bad.json").write_text('{"version":1,"steps":[{"type":"wait","ticks":NaN}]}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Non-finite"):
            self.store.load("bad")

    def test_missing_parameter_and_expansion_limit_fail_before_execution(self):
        self.write()
        file = self.root / "wait.json"
        document = json.loads(file.read_text())
        del document["defaults"]
        file.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Missing"):
            self.store.load("wait")
        document["steps"] = [{"type": "wait"}] * 4097
        file.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "4096"):
            self.store.load("wait")


if __name__ == "__main__":
    unittest.main()
