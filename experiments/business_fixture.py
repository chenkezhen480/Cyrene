"""Isolated synthetic CRM API; compose a store/service/server with supplied credentials."""

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
from contextlib import contextmanager
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit


BENCHMARK_DATE = "2026-10-12"
TENANT_ID = "fixtureTenant"
FIXTURE_MARKER = "cyrene-synthetic-crm-v1"
CUSTOMERS = [
    ("C101", "ownerAlpha", "月棱机械A17", "gold", "EAST", "2026-10-20", "2026-09-01", 0),
    ("C102", "ownerAlpha", "纸鹭科技B24", "silver", "NORTH", "2026-10-25", "2026-10-10", 0),
    ("C103", "ownerAlpha", "蓝榫物流C36", "platinum", "SOUTH", "2026-11-15", "2026-08-01", 0),
    ("C104", "ownerAlpha", "霜桨商贸D48", "silver", "NORTH", "2026-10-15", "2026-09-20", 0),
    ("C105", "ownerAlpha", "虹螺设备E59", "gold", "WEST", "2026-10-29", "2026-08-20", 0),
    ("C106", "ownerAlpha", "苔炬能源F61", "silver", "EAST", "2026-11-30", "2026-10-01", 0),
    ("C107", "ownerAlpha", "雪舟工坊G73", "platinum", "WEST", "2026-10-14", "2026-09-03", 1),
    ("C201", "ownerBeta", "隐穗材料H82", "gold", "EAST", "2026-10-16", "2026-08-11", 0),
    ("C202", "ownerBeta", "墨翅电子J94", "silver", "SOUTH", "2026-12-01", "2026-10-02", 0),
    ("C203", "ownerBeta", "玉鲟零件K05", "platinum", "NORTH", "2026-10-28", "2026-08-09", 0),
]
SEED_TASKS = [
    (1, "C101", "既有续约回访A17", "2026-10-14", "inProgress", 1, "seed-alpha-1"),
    (2, "C104", "既有报价确认D48", "2026-10-18", "open", 1, "seed-alpha-2"),
    (3, "C201", "其他负责人私有待办H82", "2026-10-14", "open", 1, "seed-beta-1"),
]
CUSTOMER_FIELDS = "id, tenant_id AS tenantId, owner_id AS ownerId, name, tier, region, renewal_date AS renewalDate, last_contact_date AS lastContactDate, billing_hold AS billingHold"
TASK_FIELDS = "printf('T%04d', task_seq) AS id, tenant_id AS tenantId, owner_id AS ownerId, customer_id AS customerId, title, due_date AS dueDate, status, version, idempotency_key AS idempotencyKey"


class ApiError(Exception):
    def __init__(self, status, code, message, details=None):
        super().__init__(message)
        self.status = status
        self.payload = {"code": code, "message": message, "details": {} if details is None else details}


def invalid(message, details=None):
    raise ApiError(400, "INVALID_INPUT", message, details)


def requireFields(body, fields):
    if not isinstance(body, dict) or set(body) != set(fields):
        invalid("JSON object must contain exactly the documented fields", {"requiredFields": fields})


def requireText(value, field, maximum=200):
    if not isinstance(value, str) or not value.strip() or value != value.strip() or len(value) > maximum:
        invalid("Invalid text field", {"field": field, "maximumLength": maximum})
    return value


def requireDate(value, field):
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        invalid("Date must be YYYY-MM-DD", {"field": field})
    try:
        date.fromisoformat(value)
    except ValueError:
        invalid("Date does not exist", {"field": field})
    return value


class SqliteStore:
    def __init__(self, databasePath):
        self.databasePath = Path(databasePath).resolve()
        self.databasePath.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.connection = sqlite3.connect(self.databasePath, check_same_thread=False, isolation_level=None)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys = ON")
        try:
            tables = {row[0] for row in self.connection.execute("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")}
            if tables:
                if tables != {"fixture_metadata", "customers", "tasks"}:
                    raise ValueError("Database contains unrelated tables; refusing fixture initialization")
                marker = self.connection.execute("SELECT marker FROM fixture_metadata").fetchall()
                if len(marker) != 1 or marker[0][0] != FIXTURE_MARKER:
                    raise ValueError("Database is not a recognized fixture; refusing initialization")
            else:
                with self.transaction() as connection:
                    connection.execute("CREATE TABLE fixture_metadata (marker TEXT NOT NULL)")
                    connection.execute("INSERT INTO fixture_metadata VALUES (?)", (FIXTURE_MARKER,))
                    connection.execute("CREATE TABLE customers (id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL, owner_id TEXT NOT NULL, name TEXT NOT NULL, tier TEXT NOT NULL, region TEXT NOT NULL, renewal_date TEXT NOT NULL, last_contact_date TEXT NOT NULL, billing_hold INTEGER NOT NULL CHECK (billing_hold IN (0,1)))")
                    connection.execute("CREATE INDEX customer_scope_id ON customers (tenant_id, owner_id, id)")
                    connection.execute("CREATE TABLE tasks (task_seq INTEGER PRIMARY KEY AUTOINCREMENT, tenant_id TEXT NOT NULL, owner_id TEXT NOT NULL, customer_id TEXT NOT NULL REFERENCES customers(id), title TEXT NOT NULL, due_date TEXT NOT NULL, status TEXT NOT NULL CHECK (status IN ('open','inProgress','done','cancelled')), version INTEGER NOT NULL CHECK (version > 0), idempotency_key TEXT NOT NULL, UNIQUE(tenant_id, owner_id, idempotency_key))")
                    connection.execute("CREATE INDEX task_scope_id ON tasks (tenant_id, owner_id, task_seq)")
                    self.seed(connection)
        except Exception:
            self.connection.close()
            raise

    @contextmanager
    def transaction(self):
        with self.lock:
            self.connection.execute("BEGIN IMMEDIATE")
            try:
                yield self.connection
                self.connection.commit()
            except Exception:
                self.connection.rollback()
                raise

    def seed(self, connection):
        connection.executemany("INSERT INTO customers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", [(customer[0], TENANT_ID, *customer[1:]) for customer in CUSTOMERS])
        connection.executemany("INSERT INTO tasks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", [(task[0], TENANT_ID, "ownerAlpha" if task[1].startswith("C1") else "ownerBeta", *task[1:]) for task in SEED_TASKS])

    def close(self):
        with self.lock:
            self.connection.close()

    def reset(self):
        with self.transaction() as connection:
            marker = connection.execute("SELECT marker FROM fixture_metadata").fetchone()
            if marker is None or marker[0] != FIXTURE_MARKER:
                raise ValueError("Refusing reset of an unrecognized fixture database")
            connection.execute("DELETE FROM tasks")
            connection.execute("DELETE FROM customers")
            connection.execute("DELETE FROM sqlite_sequence WHERE name = 'tasks'")
            self.seed(connection)

    def state(self):
        with self.lock:
            customers = [dict(row) for row in self.connection.execute("SELECT " + CUSTOMER_FIELDS + " FROM customers ORDER BY id")]
            tasks = [dict(row) for row in self.connection.execute("SELECT " + TASK_FIELDS + " FROM tasks ORDER BY task_seq")]
            for customer in customers:
                customer["billingHold"] = bool(customer["billingHold"])
            return {"benchmarkDate": BENCHMARK_DATE, "customers": customers, "tasks": tasks}

    def customer(self, ownerId, customerId, connection=None):
        with self.lock:
            activeConnection = self.connection if connection is None else connection
            row = activeConnection.execute("SELECT " + CUSTOMER_FIELDS + " FROM customers WHERE tenant_id = ? AND owner_id = ? AND id = ?", (TENANT_ID, ownerId, customerId)).fetchone()
            if row is None:
                raise ApiError(404, "NOT_FOUND", "Customer not found in current-user scope")
            result = dict(row)
            result["billingHold"] = bool(result["billingHold"])
            return result

    def listItems(self, entity, ownerId, afterId, limit, filters):
        with self.lock:
            parameters = [TENANT_ID, ownerId]
            clauses = ["tenant_id = ?", "owner_id = ?"]
            if entity == "customers":
                selectFields, orderField, table = CUSTOMER_FIELDS, "id", "customers"
                if afterId:
                    clauses.append("id > ?")
                    parameters.append(afterId)
                for key, column in (("expiresBefore", "renewal_date"), ("inactiveBefore", "last_contact_date")):
                    if key in filters:
                        clauses.append(column + " < ?")
                        parameters.append(filters[key])
            else:
                selectFields, orderField, table = TASK_FIELDS, "task_seq", "tasks"
                if afterId:
                    clauses.append("task_seq > ?")
                    parameters.append(int(afterId[1:]))
                if "customerId" in filters:
                    self.customer(ownerId, filters["customerId"])
                    clauses.append("customer_id = ?")
                    parameters.append(filters["customerId"])
            parameters.append(limit + 1)
            rows = self.connection.execute("SELECT " + selectFields + " FROM " + table + " WHERE " + " AND ".join(clauses) + " ORDER BY " + orderField + " LIMIT ?", parameters).fetchall()
            items = [dict(row) for row in rows[:limit]]
            if entity == "customers":
                for item in items:
                    item["billingHold"] = bool(item["billingHold"])
            return items, len(rows) > limit

    def createTask(self, ownerId, body):
        with self.transaction() as connection:
            self.customer(ownerId, body["customerId"], connection)
            prior = connection.execute("SELECT " + TASK_FIELDS + " FROM tasks WHERE tenant_id = ? AND owner_id = ? AND idempotency_key = ?", (TENANT_ID, ownerId, body["idempotencyKey"])).fetchone()
            if prior is not None:
                result = dict(prior)
                if any(result[field] != body[field] for field in ("customerId", "title", "dueDate")):
                    raise ApiError(409, "IDEMPOTENCY_CONFLICT", "Idempotency key has a different request")
                return 200, result
            inserted = connection.execute("INSERT INTO tasks (tenant_id, owner_id, customer_id, title, due_date, status, version, idempotency_key) VALUES (?, ?, ?, ?, ?, 'open', 1, ?)", (TENANT_ID, ownerId, body["customerId"], body["title"], body["dueDate"], body["idempotencyKey"]))
            result = connection.execute("SELECT " + TASK_FIELDS + " FROM tasks WHERE task_seq = ?", (inserted.lastrowid,)).fetchone()
            return 201, dict(result)

    def updateTask(self, ownerId, taskId, body):
        with self.transaction() as connection:
            taskSeq = int(taskId[1:])
            row = connection.execute("SELECT version FROM tasks WHERE tenant_id = ? AND owner_id = ? AND task_seq = ?", (TENANT_ID, ownerId, taskSeq)).fetchone()
            if row is None:
                raise ApiError(404, "NOT_FOUND", "Task not found in current-user scope")
            if row["version"] != body["expectedVersion"]:
                raise ApiError(409, "STALE_VERSION", "Task version changed; read the current task before retrying", {"expectedVersion": body["expectedVersion"], "currentVersion": row["version"]})
            connection.execute("UPDATE tasks SET status = ?, version = version + 1 WHERE tenant_id = ? AND owner_id = ? AND task_seq = ?", (body["status"], TENANT_ID, ownerId, taskSeq))
            return dict(connection.execute("SELECT " + TASK_FIELDS + " FROM tasks WHERE task_seq = ?", (taskSeq,)).fetchone())


class BusinessService:
    def __init__(self, store, tokens):
        if set(tokens) != {"ownerAlpha", "ownerBeta", "admin"} or any(not isinstance(token, str) or len(token) < 16 or token != token.strip() for token in tokens.values()) or len(set(tokens.values())) != 3:
            raise ValueError("Supply three distinct tokens of at least 16 characters for ownerAlpha, ownerBeta and admin")
        self.store = store
        self.tokens = dict(tokens)
        self.cursorKey = secrets.token_bytes(32)

    def principal(self, authorization, admin=False):
        if not isinstance(authorization, str) or not authorization.startswith("Bearer "):
            raise ApiError(401, "UNAUTHORIZED", "Bearer authentication required")
        supplied = authorization[7:]
        principal = next((owner for owner, token in self.tokens.items() if hmac.compare_digest(token.encode("utf-8"), supplied.encode("utf-8"))), None)
        if principal is None:
            raise ApiError(401, "UNAUTHORIZED", "Invalid authentication token")
        if (principal == "admin") != admin:
            raise ApiError(403, "FORBIDDEN", "Token is not authorized for this route")
        return principal

    def page(self, entity, ownerId, query):
        filterKeys = ("expiresBefore", "inactiveBefore") if entity == "customers" else ("customerId",)
        if set(query) - {"limit", "cursor", *filterKeys} or any(len(values) != 1 for values in query.values()):
            invalid("Unknown or repeated query parameter")
        rawLimit = query.get("limit", ["50"])[0]
        if not re.fullmatch(r"[1-9]\d*", rawLimit) or len(rawLimit) > 3 or not 1 <= int(rawLimit) <= 100:
            invalid("limit must be an integer between 1 and 100")
        limit = int(rawLimit)
        filters = {key: query[key][0] for key in filterKeys if key in query}
        for key, value in filters.items():
            requireDate(value, key) if key != "customerId" else requireText(value, key, 30)
        cursorScope = {"entity": entity, "ownerId": ownerId, "tenantId": TENANT_ID, "filters": filters}
        cursor = query.get("cursor", [""])[0]
        afterId = ""
        if cursor:
            try:
                encoded, signature = cursor.split(".")
                expectedSignature = hmac.new(self.cursorKey, encoded.encode("ascii"), hashlib.sha256).hexdigest()
                if not hmac.compare_digest(signature, expectedSignature):
                    invalid("Cursor signature is invalid")
                decoded = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
                afterId = decoded.pop("afterId")
                pattern = r"C\d{3}" if entity == "customers" else r"T\d{4,}"
                if decoded != cursorScope or not isinstance(afterId, str) or not re.fullmatch(pattern, afterId):
                    invalid("Cursor does not match the current-user scope and filters")
            except (ValueError, KeyError, TypeError, AttributeError, UnicodeError):
                invalid("Invalid cursor")
        items, hasMore = self.store.listItems(entity, ownerId, afterId, limit, filters)
        nextCursor = ""
        if hasMore:
            encoded = base64.urlsafe_b64encode(json.dumps({**cursorScope, "afterId": items[-1]["id"]}, sort_keys=True, separators=(",", ":")).encode("utf-8")).decode("ascii").rstrip("=")
            nextCursor = encoded + "." + hmac.new(self.cursorKey, encoded.encode("ascii"), hashlib.sha256).hexdigest()
        return {"items": items, "pageInfo": {"limit": limit, "nextCursor": nextCursor, "hasMore": hasMore}}

    def route(self, method, path, query, authorization, body):
        if method == "GET" and path == "/health":
            return 200, {"status": "ok", "fixture": FIXTURE_MARKER, "benchmarkDate": BENCHMARK_DATE}
        if method == "GET" and path == "/openapi.json":
            return 200, openapiSpec()
        if path in ("/_experiment/reset", "/_experiment/state"):
            self.principal(authorization, admin=True)
            if query:
                invalid("Admin routes do not accept query parameters")
            if method == "GET" and path == "/_experiment/state":
                return 200, self.store.state()
            if method == "POST" and path == "/_experiment/reset":
                requireFields(body, [])
                self.store.reset()
                return 200, {"status": "reset", "benchmarkDate": BENCHMARK_DATE}
            raise ApiError(405, "METHOD_NOT_ALLOWED", "Method is not supported for this route")
        ownerId = self.principal(authorization)
        if method == "GET" and path in ("/customers", "/tasks"):
            return 200, self.page(path[1:], ownerId, query)
        if query:
            invalid("This route does not accept query parameters")
        customerMatch = re.fullmatch(r"/customers/(C\d{3})", path)
        if method == "GET" and customerMatch:
            return 200, self.store.customer(ownerId, customerMatch[1])
        if method == "POST" and path == "/tasks":
            requireFields(body, ["customerId", "title", "dueDate", "idempotencyKey"])
            for field, maximum in (("customerId", 30), ("title", 200), ("idempotencyKey", 100)):
                requireText(body[field], field, maximum)
            requireDate(body["dueDate"], "dueDate")
            return self.store.createTask(ownerId, body)
        taskMatch = re.fullmatch(r"/tasks/(T\d{4,})", path)
        if method == "PUT" and taskMatch:
            if len(taskMatch[1]) > 20 or int(taskMatch[1][1:]) > 9223372036854775807:
                invalid("Task id is out of range")
            requireFields(body, ["status", "expectedVersion"])
            if body["status"] not in ("open", "inProgress", "done", "cancelled"):
                invalid("Unknown task status")
            if type(body["expectedVersion"]) is not int or body["expectedVersion"] < 1:
                invalid("expectedVersion must be a positive integer")
            return 200, self.store.updateTask(ownerId, taskMatch[1], body)
        raise ApiError(404, "NOT_FOUND", "Route not found")


def openapiSpec():
    textSchema = {"type": "string"}
    dateSchema = {"type": "string", "format": "date"}
    customerProperties = {field: textSchema for field in ("id", "tenantId", "ownerId", "name", "tier", "region")}
    customerProperties.update({"renewalDate": dateSchema, "lastContactDate": dateSchema, "billingHold": {"type": "boolean"}})
    taskProperties = {field: textSchema for field in ("id", "tenantId", "ownerId", "customerId", "title", "idempotencyKey")}
    taskProperties.update({"dueDate": dateSchema, "status": {"type": "string", "enum": ["open", "inProgress", "done", "cancelled"]}, "version": {"type": "integer", "minimum": 1}})

    def objectSchema(properties):
        return {"type": "object", "properties": properties, "required": list(properties), "additionalProperties": False}

    def reference(name):
        return {"$ref": "#/components/schemas/" + name}

    def response(name, description):
        return {"description": description, "content": {"application/json": {"schema": reference(name)}}}

    def operation(operationId, description, responseName, parameters=None, requestName=None, create=False):
        result = {"operationId": operationId, "description": description, "responses": {"200": response(responseName, "Successful result"), "400": response("ApiError", "Invalid request"), "401": response("ApiError", "Missing or invalid credential"), "403": response("ApiError", "Token not authorized"), "404": response("ApiError", "Resource absent from current-user scope"), "409": response("ApiError", "Idempotency or version conflict"), "503": response("ApiError", "Fixture storage unavailable")}}
        if create:
            result["responses"]["201"] = response(responseName, "Task created; retry with the same idempotency key and payload returns the same task")
        if parameters:
            result["parameters"] = parameters
        if requestName:
            result["requestBody"] = {"required": True, "content": {"application/json": {"schema": reference(requestName)}}}
        return result

    paging = [{"name": "limit", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 50}}, {"name": "cursor", "in": "query", "description": "Opaque cursor from pageInfo.nextCursor; preserve the same filters and user credential", "schema": textSchema}]
    customerFilters = [{"name": field, "in": "query", "description": "Strictly before this YYYY-MM-DD date", "schema": dateSchema} for field in ("expiresBefore", "inactiveBefore")]
    idParameter = [{"name": "id", "in": "path", "required": True, "schema": textSchema}]
    schemas = {"Customer": objectSchema(customerProperties), "Task": objectSchema(taskProperties), "PageInfo": objectSchema({"limit": {"type": "integer"}, "nextCursor": textSchema, "hasMore": {"type": "boolean"}}), "ApiError": objectSchema({"code": textSchema, "message": textSchema, "details": {"type": "object"}}), "CreateTask": objectSchema({"customerId": textSchema, "title": {"type": "string", "minLength": 1, "maxLength": 200}, "dueDate": dateSchema, "idempotencyKey": {"type": "string", "minLength": 1, "maxLength": 100}}), "UpdateTask": objectSchema({"status": taskProperties["status"], "expectedVersion": {"type": "integer", "minimum": 1}})}
    for entity in ("Customer", "Task"):
        schemas[entity + "Page"] = objectSchema({"items": {"type": "array", "items": reference(entity)}, "pageInfo": reference("PageInfo")})
    return {"openapi": "3.0.3", "info": {"title": "Synthetic Cyrene CRM fixture", "version": "1", "description": "Isolated fictional business fixture. Dates are fixed to benchmark date " + BENCHMARK_DATE + ". Authentication fixes tenant and owner; no owner or tenant override is accepted. Error NOT_FOUND does not reveal whether another owner has the record."}, "servers": [{"url": "/"}], "security": [{"bearerAuth": []}], "paths": {
        "/customers": {"get": operation("listCustomers", "List current user's customers ordered by id, with stable cursor pagination; expiresBefore compares renewalDate and inactiveBefore compares lastContactDate strictly before a fixed date", "CustomerPage", paging + customerFilters)},
        "/customers/{id}": {"get": operation("getCustomer", "Read one customer only in the authenticated current-user scope", "Customer", idParameter)},
        "/tasks": {"get": operation("listTasks", "List current user's tasks ordered by task id. customerId optionally restricts to an owned customer. Read task version here before updating", "TaskPage", paging + [{"name": "customerId", "in": "query", "schema": textSchema}]), "post": operation("createTask", "Create one current-user task atomically. Use a unique nonempty idempotencyKey; identical retries produce one task; changed payload with same key is rejected", "Task", requestName="CreateTask", create=True)},
        "/tasks/{id}": {"put": operation("updateTask", "Update owned task status using its current expectedVersion; increments version exactly once; stale version is rejected without a write", "Task", idParameter, "UpdateTask")}}, "components": {"securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer"}}, "schemas": schemas}}


def createServer(host, port, service):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass  # Credentials and query contents must never enter fixture request logs.

        def handleRequest(self):
            try:
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query, keep_blank_values=True, max_num_fields=10)
                body = None
                if self.command in ("POST", "PUT"):
                    if self.headers.get_content_type() != "application/json":
                        invalid("Content-Type must be application/json")
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 1 <= length <= 16384:
                        invalid("Request JSON size must be between 1 and 16384 bytes")
                    body = json.loads(self.rfile.read(length))
                status, payload = service.route(self.command, unquote(parsed.path), query, self.headers.get("Authorization"), body)
            except ApiError as error:
                status, payload = error.status, error.payload
            except (ValueError, UnicodeError):
                status, payload = 400, ApiError(400, "INVALID_INPUT", "Malformed request encoding or JSON").payload
            except sqlite3.Error:
                status, payload = 503, ApiError(503, "STORAGE_FAILURE", "Fixture database operation failed").payload
            except Exception as error:
                print("Fixture request failure: " + type(error).__name__, file=sys.stderr)
                status, payload = 500, ApiError(500, "INTERNAL_ERROR", "Fixture request failed").payload
            encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(encoded)

        do_GET = handleRequest
        do_POST = handleRequest
        do_PUT = handleRequest

    return ThreadingHTTPServer((host, port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--database", required=True)
    tokenSource = parser.add_mutually_exclusive_group()
    tokenSource.add_argument("--tokens-file", dest="tokensFile")
    tokenSource.add_argument("--tokens-json", dest="tokensJson")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("--port must be between 0 and 65535")
    tokensFile = args.tokensFile or os.environ.get("EXPERIMENT_FIXTURE_TOKENS_FILE")
    tokensJson = args.tokensJson or os.environ.get("EXPERIMENT_FIXTURE_TOKENS")
    if args.tokensJson:
        tokensFile = None
    if tokensFile:
        tokensJson = Path(tokensFile).read_text(encoding="utf-8")
    if not tokensJson:
        parser.error("Supply --tokens-file, --tokens-json, EXPERIMENT_FIXTURE_TOKENS_FILE or EXPERIMENT_FIXTURE_TOKENS")
    store = None
    server = None
    try:
        tokens = json.loads(tokensJson)
        if not isinstance(tokens, dict):
            raise ValueError("Token configuration must be a JSON object")
        store = SqliteStore(args.database)
        service = BusinessService(store, tokens)
        server = createServer(args.host, args.port, service)
        print(json.dumps({"status": "listening", "host": args.host, "port": server.server_port, "database": str(store.databasePath), "benchmarkDate": BENCHMARK_DATE}), flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if server is not None:
            server.server_close()
        if store is not None:
            store.close()


if __name__ == "__main__":
    main()
