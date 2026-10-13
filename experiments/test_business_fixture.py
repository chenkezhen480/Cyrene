import concurrent.futures
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from contextlib import closing
from pathlib import Path

import business_fixture


class BusinessFixtureTest(unittest.TestCase):
    def setUp(self):
        self.tempDirectory = tempfile.TemporaryDirectory()
        self.databasePath = Path(self.tempDirectory.name) / "isolated-fixture.sqlite3"
        self.store = business_fixture.SqliteStore(self.databasePath)
        self.tokens = {"ownerAlpha": "synthetic-alpha-test-token", "ownerBeta": "synthetic-beta-test-token", "admin": "synthetic-admin-test-token"}
        self.service = business_fixture.BusinessService(self.store, self.tokens)
        self.server = business_fixture.createServer("127.0.0.1", 0, self.service)
        self.serverThread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.serverThread.start()
        self.baseUrl = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.serverThread.join()
        self.store.close()
        self.tempDirectory.cleanup()

    def request(self, path, method="GET", body=None, principal="ownerAlpha"):
        headers = {}
        if principal is not None:
            headers["Authorization"] = "Bearer " + self.tokens[principal]
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.baseUrl + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def state(self):
        status, state = self.request("/_experiment/state", principal="admin")
        self.assertEqual(200, status)
        return state

    def createTask(self, **overrides):
        body = {"customerId": "C101", "title": "Synthetic renewal contact", "dueDate": "2026-10-14", "idempotencyKey": "test-create-1"}
        body.update(overrides)
        return self.request("/tasks", "POST", body)

    def test_customer_pagination_visits_only_current_owner_in_stable_order(self):
        cursor = ""
        ids = []
        while True:
            status, page = self.request("/customers?limit=2&cursor=" + cursor)
            self.assertEqual(200, status)
            self.assertEqual({"items", "pageInfo"}, set(page))
            self.assertEqual({"limit", "nextCursor", "hasMore"}, set(page["pageInfo"]))
            self.assertEqual(2, page["pageInfo"]["limit"])
            self.assertLessEqual(len(page["items"]), 2)
            self.assertTrue(all(item["ownerId"] == "ownerAlpha" for item in page["items"]))
            ids.extend(item["id"] for item in page["items"])
            if not page["pageInfo"]["hasMore"]:
                self.assertEqual("", page["pageInfo"]["nextCursor"])
                break
            cursor = page["pageInfo"]["nextCursor"]
            self.assertTrue(cursor)
        self.assertEqual(["C101", "C102", "C103", "C104", "C105", "C106", "C107"], ids)

    def test_date_filters_are_strict_and_compose_before_pagination(self):
        status, page = self.request("/customers?limit=2&expiresBefore=2026-11-01&inactiveBefore=2026-09-15")
        self.assertEqual(200, status)
        self.assertEqual(["C101", "C105"], [item["id"] for item in page["items"]])
        status, page = self.request("/customers?limit=2&expiresBefore=2026-11-01&inactiveBefore=2026-09-15&cursor=" + page["pageInfo"]["nextCursor"])
        self.assertEqual(200, status)
        self.assertEqual(["C107"], [item["id"] for item in page["items"]])
        self.assertFalse(page["pageInfo"]["hasMore"])
        status, page = self.request("/customers?expiresBefore=2026-10-15")
        self.assertEqual(200, status)
        self.assertEqual(["C107"], [item["id"] for item in page["items"]])

    def test_owner_isolation_applies_to_detail_tasks_and_writes(self):
        before = self.state()
        for path in ("/customers/C201", "/tasks?customerId=C201"):
            status, error = self.request(path)
            self.assertEqual(404, status)
            self.assertEqual("NOT_FOUND", error["code"])
            self.assertEqual({"code", "message", "details"}, set(error))
        self.assertEqual(404, self.createTask(customerId="C201")[0])
        self.assertEqual(404, self.request("/tasks/T0003", "PUT", {"status": "done", "expectedVersion": 1})[0])
        status, page = self.request("/customers", principal="ownerBeta")
        self.assertEqual(200, status)
        self.assertEqual(["C201", "C202", "C203"], [item["id"] for item in page["items"]])
        self.assertEqual(before, self.state())

    def test_task_list_uses_cursor_and_customer_filter(self):
        status, page = self.request("/tasks?limit=1")
        self.assertEqual(200, status)
        self.assertEqual(["T0001"], [task["id"] for task in page["items"]])
        self.assertTrue(page["pageInfo"]["hasMore"])
        status, page = self.request("/tasks?limit=1&cursor=" + page["pageInfo"]["nextCursor"])
        self.assertEqual(200, status)
        self.assertEqual(["T0002"], [task["id"] for task in page["items"]])
        self.assertFalse(page["pageInfo"]["hasMore"])
        status, page = self.request("/tasks?customerId=C101")
        self.assertEqual(200, status)
        self.assertEqual(["T0001"], [task["id"] for task in page["items"]])

    def test_concurrent_idempotent_creation_commits_exactly_one_task(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(lambda index: self.createTask(), range(8)))
        self.assertEqual(1, sum(status == 201 for status, body in results))
        self.assertTrue(all(status in (200, 201) for status, body in results))
        self.assertTrue(all(body == results[0][1] for status, body in results))
        created = results[0][1]
        self.assertEqual("T0004", created["id"])
        self.assertEqual(1, created["version"])
        self.assertEqual("open", created["status"])
        self.assertEqual("ownerAlpha", created["ownerId"])
        self.assertEqual(4, len(self.state()["tasks"]))

    def test_idempotency_conflict_and_invalid_customer_do_not_mutate_state(self):
        self.assertEqual(201, self.createTask()[0])
        before = self.state()
        status, error = self.createTask(title="Different request for same key")
        self.assertEqual(409, status)
        self.assertEqual("IDEMPOTENCY_CONFLICT", error["code"])
        self.assertEqual(404, self.createTask(customerId="missing", idempotencyKey="missing-create")[0])
        self.assertEqual(before, self.state())

    def test_update_requires_current_version_and_preserves_rejected_state(self):
        status, task = self.request("/tasks/T0001", "PUT", {"status": "done", "expectedVersion": 1})
        self.assertEqual(200, status)
        self.assertEqual("done", task["status"])
        self.assertEqual(2, task["version"])
        before = self.state()
        status, error = self.request("/tasks/T0001", "PUT", {"status": "cancelled", "expectedVersion": 1})
        self.assertEqual(409, status)
        self.assertEqual("STALE_VERSION", error["code"])
        self.assertEqual({"expectedVersion": 1, "currentVersion": 2}, error["details"])
        self.assertEqual(before, self.state())

    def test_invalid_input_is_explicit_and_never_creates_a_task(self):
        before = self.state()
        for overrides in ({"dueDate": "2026-02-30"}, {"title": " "}, {"ownerId": "ownerBeta"}, {"idempotencyKey": ""}):
            status, error = self.createTask(**overrides)
            self.assertEqual(400, status)
            self.assertEqual("INVALID_INPUT", error["code"])
        for path in ("/customers?limit=0", "/customers?limit=101", "/customers?cursor=invalid", "/customers?expiresBefore=yesterday", "/customers?ownerId=ownerBeta", "/tasks?limit=1&limit=2"):
            status, error = self.request(path)
            self.assertEqual(400, status)
            self.assertEqual("INVALID_INPUT", error["code"])
        self.assertEqual(before, self.state())

    def test_out_of_range_task_id_is_a_client_error_without_a_write(self):
        before = self.state()
        status, error = self.request("/tasks/T99999999999999999999999", "PUT", {"status": "done", "expectedVersion": 1})
        self.assertEqual(400, status)
        self.assertEqual("INVALID_INPUT", error["code"])
        self.assertEqual(before, self.state())

    def test_cursor_is_bound_to_current_owner_and_filters(self):
        status, page = self.request("/customers?limit=2&expiresBefore=2026-11-01")
        self.assertEqual(200, status)
        cursor = page["pageInfo"]["nextCursor"]
        self.assertEqual(400, self.request("/customers?limit=2&cursor=" + cursor)[0])
        self.assertEqual(400, self.request("/customers?limit=2&expiresBefore=2026-11-01&cursor=" + cursor, principal="ownerBeta")[0])

    def test_reset_and_state_are_admin_only_and_reset_is_deterministic(self):
        before = self.state()
        self.assertEqual(201, self.createTask()[0])
        for principal, wanted in ((None, 401), ("ownerAlpha", 403), ("ownerBeta", 403)):
            self.assertEqual(wanted, self.request("/_experiment/state", principal=principal)[0])
            self.assertEqual(wanted, self.request("/_experiment/reset", "POST", {}, principal=principal)[0])
        status, reset = self.request("/_experiment/reset", "POST", {}, principal="admin")
        self.assertEqual(200, status)
        self.assertEqual({"status": "reset", "benchmarkDate": "2026-10-12"}, reset)
        self.assertEqual(before, self.state())
        self.assertEqual("T0004", self.createTask()[1]["id"])

    def test_reset_failure_rolls_back_customers_and_tasks_together(self):
        self.assertEqual(201, self.createTask()[0])
        before = self.state()
        with self.store.transaction() as connection:
            connection.execute("CREATE TRIGGER reject_seed BEFORE INSERT ON customers WHEN NEW.id = 'C104' BEGIN SELECT RAISE(ABORT, 'synthetic reset failure'); END")
        status, error = self.request("/_experiment/reset", "POST", {}, principal="admin")
        self.assertEqual(503, status)
        self.assertEqual("STORAGE_FAILURE", error["code"])
        self.assertEqual(before, self.state())
        with self.store.transaction() as connection:
            connection.execute("DROP TRIGGER reject_seed")
        self.assertEqual(200, self.request("/_experiment/reset", "POST", {}, principal="admin")[0])
        self.assertEqual(3, len(self.state()["tasks"]))

    def test_openapi_exposes_only_business_routes_and_no_credentials(self):
        self.assertEqual(200, self.request("/health", principal=None)[0])
        status, schema = self.request("/openapi.json", principal=None)
        self.assertEqual(200, status)
        self.assertEqual({"/customers", "/customers/{id}", "/tasks", "/tasks/{id}"}, set(schema["paths"]))
        encoded = json.dumps(schema)
        self.assertNotIn("/_experiment", encoded)
        self.assertTrue(all(token not in encoded for token in self.tokens.values()))
        self.assertEqual(401, self.request("/customers", principal=None)[0])
        self.assertEqual(403, self.request("/customers", principal="admin")[0])

    def test_unrelated_sqlite_database_cannot_be_reset_or_initialized(self):
        import sqlite3
        path = Path(self.tempDirectory.name) / "unrelated.sqlite3"
        with closing(sqlite3.connect(path)) as connection:
            connection.execute("CREATE TABLE unrelated_data (value TEXT)")
            connection.execute("INSERT INTO unrelated_data VALUES ('preserve-me')")
            connection.commit()
        with self.assertRaises(ValueError):
            business_fixture.SqliteStore(path)
        with closing(sqlite3.connect(path)) as connection:
            self.assertEqual([("preserve-me",)], connection.execute("SELECT value FROM unrelated_data").fetchall())


if __name__ == "__main__":
    unittest.main()
