import unittest

from security import UrlPolicyError, origin_of, validate_http_url


class UrlPolicyTest(unittest.TestCase):

    def test_blocks_private_ip(self):
        with self.assertRaises(UrlPolicyError):
            validate_http_url("http://127.0.0.1/admin")

    def test_allows_private_ip_only_when_explicit(self):
        parsed = validate_http_url(
            "http://127.0.0.1:8080/page", allow_private_networks=True)
        self.assertEqual("127.0.0.1", parsed.hostname)

    def test_rejects_embedded_credentials(self):
        with self.assertRaises(UrlPolicyError):
            validate_http_url(
                "https://user:password@example.com/",
                allow_private_networks=True,
            )

    def test_normalizes_default_origin_port(self):
        self.assertEqual("https://example.com:443", origin_of("https://example.com/a"))
        self.assertEqual(
            "http://example.com:8080",
            origin_of("http://example.com:8080/a"),
        )


if __name__ == "__main__":
    unittest.main()
