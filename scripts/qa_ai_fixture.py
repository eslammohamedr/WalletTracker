import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from qa_campaign_support import PACKAGE


class AiFixture:
    def __init__(self, ui, category="Shopping", failures=(), responses_by_marker=None):
        self.ui = ui
        self.category = category
        self.failures = set(failures)
        self.responses_by_marker = responses_by_marker or {}
        self.requests = []
        self.server = None

    def start(self):
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format_string, *arguments):
                pass

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                prompt = payload["messages"][-1]["content"]
                message = re.search(r'SMS: "(.*)"\s*$', prompt, re.DOTALL)
                provider = self.path.strip("/")
                authorized = self.headers.get("Authorization") == "Bearer qa-test"
                status = 403 if not authorized else 503 if provider in fixture.failures else 200
                fixture.requests.append({"provider": provider, "status": status,
                                         "sms": message.group(1) if message else "UNRECOGNIZED_PROMPT",
                                         "dummy_key_only": authorized})
                response_content = next((content for marker, content in fixture.responses_by_marker.items()
                                         if marker in prompt), fixture.category)
                response = {"error": {"message": "Controlled QA provider failure"}} if status != 200 else {
                    "choices": [{"message": {"role": "assistant", "content": response_content}}]}
                encoded = json.dumps(response).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

        self.server = ThreadingHTTPServer(("127.0.0.1", 8765), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.ui.adb("reverse", "tcp:8765", "tcp:8765")
        self.ui.adb("shell", f"run-as {PACKAGE} sh -c 'mkdir -p files; echo http://127.0.0.1:8765 > files/qa_ai_endpoint'")
        assert self.ui.adb("shell", "run-as", PACKAGE, "cat", "files/qa_ai_endpoint") == "http://127.0.0.1:8765"
        return self

    def close(self):
        try:
            self.ui.adb("shell", "run-as", PACKAGE, "rm", "-f", "files/qa_ai_endpoint")
            self.ui.adb("reverse", "--remove", "tcp:8765")
        finally:
            if self.server:
                self.server.shutdown()
                self.server.server_close()

    def verify(self, expected_providers, expected_sms=None):
        assert [request["provider"] for request in self.requests] == expected_providers, f"Wrong provider sequence: {self.requests}"
        assert all(request["dummy_key_only"] for request in self.requests), "Real credential unexpectedly sent to local fixture"
        if expected_sms is not None:
            assert [request["sms"] for request in self.requests] == expected_sms, f"Unexpected SMS sent to AI: {self.requests}"
