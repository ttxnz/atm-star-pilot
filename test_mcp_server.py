import io
import json
import pathlib
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from mcp_server import Bridge, Server, TOOLS


class MockBridge:
    def call(self, name, args, cancelled):
        return {"name": name, "args": args}


class ProtocolTests(unittest.TestCase):
    def test_compact_live_observation_uses_its_own_endpoint(self):
        bridge = Bridge(pathlib.Path("unused.json"))
        calls = []
        bridge.request = lambda path: calls.append(path) or {"clientTick": 123}
        self.assertEqual({"clientTick": 123}, bridge.call("minecraft_live", {"radius": 8}, threading.Event()))
        self.assertEqual(["/live?radius=8"], calls)
        with self.assertRaises(ValueError):
            bridge.call("minecraft_live", {"radius": 65}, threading.Event())

    def test_initialization_tools_and_tool_call(self):
        output = io.StringIO()
        server = Server(MockBridge(), output)
        lines = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-11-25"}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "minecraft_status", "arguments": {}}},
        ]
        server.run(io.StringIO("\n".join(json.dumps(line) for line in lines)))
        responses = {r["id"]: r for r in map(json.loads, output.getvalue().splitlines())}
        self.assertEqual("2025-11-25", responses[1]["result"]["protocolVersion"])
        self.assertEqual(len(TOOLS), len(responses[2]["result"]["tools"]))
        self.assertEqual("minecraft_status", json.loads(responses[3]["result"]["content"][0]["text"])["name"])

    def test_unsupported_method_has_json_rpc_error(self):
        output = io.StringIO()
        server = Server(MockBridge(), output)
        server.run(io.StringIO('{"jsonrpc":"2.0","id":4,"method":"missing"}\n'))
        self.assertEqual(-32601, json.loads(output.getvalue())["error"]["code"])

    def test_no_game_is_a_readable_status(self):
        bridge = Bridge(pathlib.Path("nonexistent-atmpilot-test-session.json"))
        result = bridge.call("minecraft_status", {}, threading.Event())
        self.assertFalse(result["connected"])

    def test_remote_session_endpoint_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = pathlib.Path(directory) / "session.json"
            file.write_text(json.dumps({"url": "http://example.com:80", "token": "private"}))
            with self.assertRaisesRegex(RuntimeError, "Invalid local"):
                Bridge(file).request("/state")

    def test_proxy_sends_auth_and_does_not_retry_mutations(self):
        received = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append((self.headers.get("Authorization"), self.path))
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                self.send_response(409)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"error":"busy"}')
            def log_message(self, *_):
                pass
        http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                file = pathlib.Path(directory) / "session.json"
                file.write_text(json.dumps({"url": f"http://127.0.0.1:{http.server_port}", "token": "test-token"}))
                with self.assertRaisesRegex(RuntimeError, "busy"):
                    Bridge(file).request("/jobs", "POST", {"type": "wait", "ticks": 1})
            self.assertEqual([("Bearer test-token", "/jobs")], received)
        finally:
            http.shutdown()
            http.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
