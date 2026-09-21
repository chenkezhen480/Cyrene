import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from playwright.sync_api import sync_playwright
import app


class PageHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(404 if self.path == "/missing" else 200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(b'<html><head><title>Reader test</title></head><body>'
                         b'<main id="text"></main><script>'
                         b'document.getElementById("text").textContent="rendered browser text";'
                         b'</script></body></html>')

    def log_message(self, *args):
        pass


class BrowserReaderTest(unittest.TestCase):
    def test_real_rendering_pagination_errors_and_context_cleanup(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), PageHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with sync_playwright() as playwright, patch.object(app, "ALLOW_PRIVATE_NETWORKS", True):
                browser = playwright.chromium.launch(headless=True)
                runtime = object.__new__(app.BrowserRuntime)
                url = f"http://127.0.0.1:{server.server_port}/"
                payload = {"action": "open", "url": url, "maxChars": 8}
                first = runtime._dispatch(browser, payload)
                self.assertEqual(first["content"], "rendered")
                self.assertEqual(first["title"], "Reader test")
                self.assertTrue(first["hasMore"])
                second = runtime._dispatch(browser, dict(payload, cursor=first["nextCursor"], maxChars=100))
                self.assertEqual(first["content"] + second["content"], "rendered browser text")
                self.assertFalse(second["hasMore"])
                self.assertEqual(browser.contexts, [])
                with self.assertRaisesRegex(app.BrowserWorkerError, "Only opening"):
                    runtime._dispatch(browser, dict(payload, action="click"))
                with self.assertRaisesRegex(app.BrowserWorkerError, "HTTP 404"):
                    runtime._dispatch(browser, dict(payload, url=url + "missing"))
                with self.assertRaisesRegex(app.BrowserWorkerError, "stale pagination"):
                    runtime._dispatch(browser, dict(payload, cursor="invalid"))
                self.assertEqual(browser.contexts, [])
                browser.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
