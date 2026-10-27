import base64
import http.client
import importlib.util
import json
from pathlib import Path
import re
import threading
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("preview_server", ROOT / "server/preview_server.py")
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)


class RelayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = relay.ThreadingHTTPServer(("127.0.0.1", 0), relay.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def test_viewer_and_profiles(self):
        self.assertEqual(self.request("GET", "/")[0], 200)
        status, _, body = self.request("GET", "/cutouts.json")
        self.assertEqual(status, 200)
        self.assertEqual(len(json.loads(body)), 20)
        self.assertEqual(self.request("GET", "/missing")[0], 404)

    def test_raw_and_base64_frames(self):
        # Protocol fixture; decoding JPEG pixels is the viewer's responsibility.
        jpeg = b"\xff\xd8test\xff\xd9"
        for payload in [jpeg, base64.b64encode(jpeg), b"data:image/jpeg;base64," + base64.b64encode(jpeg)]:
            status, _, _ = self.request("POST", "/frame", payload)
            self.assertEqual(status, 200)
            status, headers, body = self.request("GET", "/frame?v=0")
            self.assertEqual(status, 200)
            self.assertEqual(body, jpeg)
            self.assertIn("X-Version", headers)

    def test_invalid_uploads_do_not_replace_frame(self):
        version = relay.frame["version"]
        for payload in [b"", b"not base64!", base64.b64encode(b"not a JPEG")]:
            self.assertEqual(self.request("POST", "/frame", payload)[0], 400)
        for length, expected in [("wrong", 400), ("-1", 400), (str(relay.MAX_BODY + 1), 413)]:
            self.assertEqual(self.request("POST", "/frame", headers={"Content-Length": length})[0], expected)
        self.assertEqual(relay.frame["version"], version)


class LocalizationTest(unittest.TestCase):
    def test_android_resources(self):
        for folder in ["android", "android-overlay"]:
            def strings(locale):
                return {s.attrib["name"]: s.text for s in ET.parse(ROOT / folder / "res" / locale / "strings.xml").getroot()}
            en, ru = strings("values"), strings("values-ru")
            self.assertEqual(en.keys(), ru.keys())
            for key in en:
                self.assertEqual(re.findall(r"%\d+\$[ds]", en[key]), re.findall(r"%\d+\$[ds]", ru[key]), key)
                self.assertFalse(re.search("[А-Яа-яЁё]", en[key]), key)
            for source in (ROOT / folder / "src").rglob("*.java"):
                for key in re.findall(r"R\.string\.(\w+)", source.read_text()):
                    self.assertIn(key, en, source)

    def test_profiles_have_english_labels(self):
        profiles = json.loads((ROOT / "server/cutouts.json").read_text())
        self.assertEqual(len({p["id"] for p in profiles}), len(profiles))
        for p in profiles:
            self.assertTrue(p["kind_en"])
            for key in ["short", "kind", "device"]:
                self.assertFalse(re.search("[А-Яа-яЁё]", p.get(key + "_en", p[key])))


if __name__ == "__main__":
    unittest.main()
