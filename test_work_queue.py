import copy
import json
import pathlib
import tempfile
import unittest

from work_queue import QueueRunner, save_json


class FakeBridge:
    def __init__(self):
        self.job = {"id": "baseline", "state": "completed"}
        self.world = {"connected": True, "player": {"alive": True, "health": 20, "dimension": "own"}, "menu": {"carried": {}}, "inventory": []}
        self.posts = []
        self.fail_post = False

    def request(self, path, method="GET", body=None):
        if path == "/job": return self.job.copy()
        if path == "/state": return copy.deepcopy(self.world)
        if path == "/jobs":
            self.posts.append(body)
            if self.fail_post: raise RuntimeError("timeout after submit")
            self.job = {"id": "job-" + str(len(self.posts)), "state": "running"}
            return self.job.copy()
        raise AssertionError(path)


class WorkQueueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.plan = pathlib.Path(self.temp.name) / "plan.json"
        self.checkpoint = pathlib.Path(self.temp.name) / "state.json"
        self.bridge = FakeBridge()
        self.data = {"enabled": True, "generation": "first", "afterJob": "baseline", "stages": [self.stage("one"), self.stage("two")]}
        save_json(self.plan, self.data)
        self.runner = QueueRunner(self.bridge, self.plan, self.checkpoint)

    @staticmethod
    def stage(name):
        return {"id": name, "steps": [{"type": "wait", "ticks": 1}], "before": {"dimension": "own"}, "after": {"dimension": "own"}}

    def test_completed_batch_dispatches_next_in_same_poll(self):
        self.runner.tick()
        self.assertEqual(1, len(self.bridge.posts))
        self.bridge.job["state"] = "completed"
        self.runner.tick()
        self.assertEqual(["one"], self.runner.state["completed"])
        self.assertEqual(2, len(self.bridge.posts))
        self.assertLess(self.runner.state["lastDispatchGapSeconds"], .2)

    def test_watcher_picks_up_new_stages_after_queue_drains(self):
        self.data["stages"] = [self.stage("one")];save_json(self.plan, self.data)
        self.runner.tick();self.bridge.job["state"] = "completed";self.runner.tick()
        self.assertEqual("awaiting_plan", self.runner.state["status"])
        self.data["stages"].append(self.stage("two"));save_json(self.plan, self.data)
        self.runner.tick();self.assertEqual(2, len(self.bridge.posts))

    def test_crash_after_accept_resumes_observation_without_replaying(self):
        self.runner.tick()
        restarted = QueueRunner(self.bridge, self.plan, self.checkpoint)
        restarted.tick();self.assertEqual(1, len(self.bridge.posts))
        self.bridge.job["state"] = "completed";restarted.tick()
        self.assertEqual(2, len(self.bridge.posts))

    def test_uncertain_post_is_never_retried_after_restart(self):
        self.bridge.fail_post = True;self.runner.tick()
        restarted = QueueRunner(self.bridge, self.plan, self.checkpoint)
        restarted.tick();self.assertEqual(1, len(self.bridge.posts))
        self.assertEqual("needs_replan", restarted.state["status"])

    def test_explicit_stop_or_changed_job_never_runs_followup(self):
        for replacement in ({"id": "baseline", "state": "stopped", "message": "F8"}, {"id": "external", "state": "completed"}):
            self.runner.state = {};self.bridge.job = replacement
            self.runner.tick();self.assertEqual("needs_replan", self.runner.state["status"])
        self.assertEqual([], self.bridge.posts)

    def test_cursor_health_dimension_and_stock_are_checked_before_dispatch(self):
        scenarios = [
            {"menu": {"carried": {"count": 1}}},
            {"player": {"alive": True, "health": 10, "dimension": "own"}},
            {"player": {"alive": True, "health": 20, "dimension": "other"}},
            {"survivalReflex": {"manualHold": True}},
        ]
        for changes in scenarios:
            bridge = FakeBridge();bridge.world.update(changes)
            self.checkpoint.unlink(missing_ok=True)
            runner = QueueRunner(bridge, self.plan, self.checkpoint);runner.tick()
            self.assertEqual("needs_replan", runner.state["status"]);self.assertFalse(bridge.posts)

    def test_accepted_stage_edits_and_failed_postconditions_pause(self):
        self.runner.tick()
        self.data["stages"][0]["steps"][0]["ticks"] = 2;save_json(self.plan, self.data)
        self.runner.tick();self.assertEqual("needs_replan", self.runner.state["status"])
        self.assertEqual(1, len(self.bridge.posts))

    def test_missing_material_and_failed_result_never_dispatch_followup(self):
        self.data["stages"][0]["after"] = {"items": {"ae2:import_bus": 1}}
        save_json(self.plan, self.data);self.runner.tick()
        self.bridge.job["state"] = "completed";self.runner.tick()
        self.assertEqual("needs_replan", self.runner.state["status"])
        self.assertEqual(1, len(self.bridge.posts))

    def test_disabled_queue_can_be_enabled_without_restarting_watcher(self):
        self.data["enabled"] = False;save_json(self.plan, self.data);self.runner.tick()
        self.assertFalse(self.bridge.posts)
        self.data["enabled"] = True;save_json(self.plan, self.data);self.runner.tick()
        self.assertEqual(1, len(self.bridge.posts))

    def test_gui_stage_cannot_click_a_different_menu(self):
        self.data["stages"][0]["before"]["menuClass"] = "own.QuantumMenu"
        save_json(self.plan, self.data);self.runner.tick()
        self.assertEqual("needs_replan", self.runner.state["status"])
        self.assertFalse(self.bridge.posts)

    def test_removing_an_inflight_stage_preserves_checkpoint(self):
        self.runner.tick();self.data["stages"].pop(0);save_json(self.plan, self.data)
        self.runner.tick();self.assertEqual("needs_replan", self.runner.state["status"])
        self.assertEqual("job-1", self.runner.state["inflight"]["job"])
        self.assertEqual(1, len(self.bridge.posts))


if __name__ == "__main__":
    unittest.main()
