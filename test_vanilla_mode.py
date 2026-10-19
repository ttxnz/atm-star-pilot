import json
import pathlib
import tempfile
import threading
import unittest

from mcp_server import Bridge, TOOLS
from tools.install_vanilla import install


class VanillaIntegrationTests(unittest.TestCase):
    def test_status_and_mutations_use_dedicated_endpoints(self):
        bridge = Bridge(pathlib.Path("unused"))
        calls = []
        bridge.request = lambda *a: calls.append(a) or {"running": False}
        bridge.call("minecraft_vanilla_status", {}, threading.Event())
        args = {"confirmWorld": "local:test", "allowWorldChanges": True, "resume": True}
        bridge.call("minecraft_vanilla_start", args, threading.Event())
        bridge.call("minecraft_vanilla_stop", {}, threading.Event())
        self.assertEqual([("/vanilla",), ("/vanilla/start", "POST", args), ("/vanilla/stop", "POST", {})], calls)

    def test_start_requires_world_and_authorization_in_tool_schema(self):
        tool = next(t for t in TOOLS if t["name"] == "minecraft_vanilla_start")
        self.assertEqual({"confirmWorld", "allowWorldChanges"}, set(tool["inputSchema"]["required"]))
        self.assertFalse(tool["annotations"]["readOnlyHint"])

    def test_clean_install_does_not_copy_old_world_settings(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            instance = root / "vanilla"
            (instance / "mods").mkdir(parents=True)
            core = root / "core.jar"
            core.write_bytes(b"test core")
            target = install(instance, core)
            self.assertEqual(b"test core", target.read_bytes())
            rules = json.loads((instance / "config/atmpilot/server-rules.json").read_text())
            self.assertFalse(rules["allowExistingSurvivalFlight"])
            self.assertEqual([], rules["buildAreas"])
            self.assertFalse((instance / "config/atmpilot/session.json").exists())

    def test_atm_or_unconfirmed_replacement_is_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            instance = root / "instance"
            (instance / "mods").mkdir(parents=True)
            core = root / "core.jar"
            core.write_bytes(b"new")
            foreign = instance / "mods/mekanism.jar"
            foreign.write_bytes(b"existing")
            with self.assertRaisesRegex(ValueError, "separate"):
                install(instance, core, True)
            self.assertEqual(b"existing", foreign.read_bytes())
            foreign.unlink()
            target = instance / "mods/atm-star-pilot-0.1.0.jar"
            target.write_bytes(b"old")
            with self.assertRaisesRegex(ValueError, "Different"):
                install(instance, core)
            self.assertEqual(b"old", target.read_bytes())


if __name__ == "__main__":
    unittest.main()
