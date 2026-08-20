import json
import shutil
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from receiver import ReceiverCore, ReusableThreadingHTTPServer, load_config, make_handler, save_config


PNG = b"\x89PNG\r\n\x1a\n" + b"test-payload" + b"IEND" + b"\x00" * 8


class ReceiverTests(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parent / ".test-tmp"
        scratch.mkdir(exist_ok=True)
        self.temp_path = scratch / f"case-{id(self)}"
        self.temp_path.mkdir(exist_ok=True)
        self.core = ReceiverCore(self.temp_path, "test-pair-token")
        self.server = ReusableThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.core))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        shutil.rmtree(self.temp_path, ignore_errors=True)

    def request(self, path, data=b"", token="test-pair-token", session=None):
        request = urllib.request.Request(self.base + path, data=data, method="POST")
        request.add_header("Authorization", f"Bearer {token}")
        if session:
            request.add_header("X-Session-Id", session)
        with urllib.request.urlopen(request) as response:
            return response.status, json.loads(response.read())

    def test_complete_flow_and_idempotency(self):
        status, body = self.request("/api/v1/session")
        self.assertEqual(201, status)
        session = body["sessionId"]
        status, body = self.request("/api/v1/video", session=session)
        self.assertEqual(1, body["video"])
        status, body = self.request("/api/v1/frame?video=1&frame=1", PNG, session=session)
        self.assertEqual("saved", body["result"])
        status, body = self.request("/api/v1/frame?video=1&frame=1", PNG, session=session)
        self.assertEqual("duplicate", body["result"])
        self.assertEqual(PNG, (self.temp_path / "video_000001" / "frame_000001.png").read_bytes())

    def test_rejects_bad_token(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.request("/api/v1/session", token="wrong")
        self.assertEqual(401, caught.exception.code)

    def test_restart_continues_numbering(self):
        (self.temp_path / "video_000042").mkdir()
        restarted = ReceiverCore(self.temp_path, "test-pair-token")
        session = restarted.new_session()
        self.assertEqual(43, restarted.new_video(session))

    def test_portable_dataset_path_survives_folder_move(self):
        first_root = self.temp_path / "first"
        first_root.mkdir()
        config_path = first_root / "receiver-config.json"
        save_config(config_path, {
            "host": "0.0.0.0", "port": 8765, "token": "test-pair-token",
            "dataset": str(first_root / "dataset"),
        }, first_root)
        saved = json.loads(config_path.read_text(encoding="utf-8"))
        self.assertEqual("dataset", saved["dataset"])

        moved_root = self.temp_path / "moved"
        moved_root.mkdir()
        moved_config = moved_root / "receiver-config.json"
        moved_config.write_text(config_path.read_text(encoding="utf-8"), encoding="utf-8")
        loaded = load_config(moved_config, moved_root)
        self.assertEqual((moved_root / "dataset").resolve(), Path(loaded["dataset"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
