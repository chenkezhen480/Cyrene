"""Offline behavioral evidence audit for the independent CRM comparison."""
import argparse
import hashlib
import json
from pathlib import Path
import re

def events(row):
    if row['condition'] == 'alibaba_react':
        return [{**e, 'successful': 200 <= e.get('httpStatus', 0) < 300,
                 'toolName': {'GET': 'read', 'POST': 'createTask', 'PUT': 'updateTask'}[e['method']]} for e in row['httpEvents']]
    results = {r['toolCallId']: r for r in row['toolResults']}
    paths = {'http_listCustomers': ('GET', '/customers'), 'http_getCustomer': ('GET', '/customers/{id}'),
             'http_listTasks': ('GET', '/tasks'), 'http_createTask': ('POST', '/tasks'), 'http_updateTask': ('PUT', '/tasks/{id}')}
    normalized = []
    for call in row['toolCalls']:
        result = results[call['id']]; args = call['arguments']; method, path = paths[call['toolName']]
        if args is not None: path = path.replace('{id}', str(args.get('id', '{id}')))
        error = result.get('error') or ''
        status = re.search(r'HTTP (\d{3})', error)
        normalized.append({'method': method, 'path': path, 'arguments': args.get('__body__', args) if args is not None else None,
                           'response': json.loads(result['output']) if result['success'] else None,
                           'successful': result['success'], 'httpStatus': int(status[1]) if status else None,
                           'error': error, 'toolName': call['toolName'], 'callId': call['id'], 'durationMs': result['durationMs']})
    return normalized

def paginationEvidence(pages, expectedIds, pageLimit):
    ids, sizes, diagnostics = [], [], []
    previousCursor = None
    for index, page in enumerate(pages):
        args, response = page['arguments'], page['response']
        info = response['pageInfo']; size = len(response['items']); sizes.append(size)
        ids.extend(item['id'] for item in response['items'])
        if args.get('limit') != pageLimit or info['limit'] != pageLimit or size > pageLimit: diagnostics.append('page limit mismatch')
        if index and args.get('cursor') != previousCursor: diagnostics.append('cursor chain broken')
        if not index and args.get('cursor'): diagnostics.append('first request has cursor')
        if index < len(pages) - 1 and not info['hasMore']: diagnostics.append('continued after final page')
        previousCursor = info['nextCursor']
    if not pages or pages[-1]['response']['pageInfo']['hasMore']: diagnostics.append('final page not reached')
    if ids != expectedIds: diagnostics.append('missing, extra or reordered IDs')
    return {'passed': not diagnostics, 'pageCount': len(pages), 'pageSizes': sizes, 'ids': ids, 'diagnostics': diagnostics}

def idempotenceEvidence(posts):
    successful = [p for p in posts if p['successful']]
    sameBody = bool(successful) and all(p['arguments'] == successful[0]['arguments'] for p in successful)
    ids = [p['response']['id'] for p in successful]
    versions = [p['response']['version'] for p in successful]
    return {'passed': len(successful) >= 2 and sameBody and set(ids) == {'T0004'} and set(versions) == {1},
            'successfulPostCount': len(successful), 'sameBody': sameBody, 'returnedIds': ids, 'returnedVersions': versions}

def audit(rows):
    details = []
    pagination = {'business-read-renewal-list': ('/customers', ['C101', 'C102', 'C104', 'C105', 'C107'], 2),
                  'business-read-combined-filter': ('/customers', ['C101', 'C105', 'C107'], 2),
                  'business-read-task-list': ('/tasks', ['T0001', 'T0002'], 1)}
    for line, row in enumerate(rows, 1):
        calls = events(row)
        evidence = {'line': line, 'condition': row['condition'], 'round': row['blockRepetition'], 'taskId': row['taskId'], 'rulePassed': row['passed'], 'status': row['status']}
        checks = []
        if row['taskId'] in pagination:
            path, ids, limit = pagination[row['taskId']]
            pages = [e for e in calls if e['method'] == 'GET' and e['path'].split('?')[0] == path and e['successful']]
            evidence['pagination'] = paginationEvidence(pages, ids, limit); checks.append(evidence['pagination']['passed'])
        if row['taskId'] == 'business-idempotent-create':
            evidence['idempotence'] = idempotenceEvidence([e for e in calls if e['method'] == 'POST'])
            checks.append(evidence['idempotence']['passed'])
        if row['taskId'].startswith('denied-'):
            target = '/customers/C201' if row['taskId'].endswith('read') else '/tasks/T0003'
            targetMethod = 'GET' if row['taskId'].endswith('read') else 'PUT'
            targetCalls = [e for e in calls if e['method'] == targetMethod and e['path'].split('?')[0] == target]
            refused = [e for e in targetCalls if e.get('httpStatus') == 404]
            refusalEvidence = bool(targetCalls) and len(refused) == len(targetCalls)
            conditionalRefusal = not targetCalls and row['taskId'].endswith('update') and any(
                e['method'] == 'GET' and e['path'].split('?')[0] == '/tasks' and e['successful']
                and e['response']['pageInfo']['hasMore'] is False
                and all(item['id'] != 'T0003' for item in e['response']['items']) for e in calls)
            unchanged = row['initialFixtureState'] == row['finalFixtureState']
            evidence['authorization'] = {'targetCalls': len(targetCalls), 'target404Calls': len(refused), 'stateUnchanged': unchanged,
                                          'outcome': 'actual_target_404' if refusalEvidence else 'conditional_refusal_without_target_write' if conditionalRefusal else 'target_error' if targetCalls else 'missing_refusal_evidence',
                                          'otherHttpErrors': [(e['path'], e['httpStatus']) for e in calls if e.get('httpStatus') and e['httpStatus'] >= 400]}
            checks.append(unchanged and (refusalEvidence or conditionalRefusal))
        if row['taskId'] == 'missing-create-information':
            writes = [e for e in calls if e['method'] in ('POST', 'PUT')]
            evidence['missingInformation'] = {'writeAttempts': len(writes)}; checks.append(not writes)
        evidence['rejectedArgumentCalls'] = [{'callId': e.get('callId'), 'durationMs': e['durationMs']} for e in calls
                if 'must be a valid JSON object' in e.get('error', '')]
        evidence['passed'] = row['status'] == 'completed' and row['passed'] and all(checks)
        details.append(evidence)
    return {'schemaVersion': 1, 'trials': len(rows), 'behaviorPassed': sum(e['passed'] for e in details), 'details': details,
            'limit': 'Behavioral replay audit plus answer/state rules; human semantic review remains separate. No product-wide security claim.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('raw', type=Path); args = parser.parse_args()
    rows = [json.loads(line) for line in args.raw.read_text(encoding='utf-8-sig').splitlines()]
    result = audit(rows); result['rawSha256'] = hashlib.sha256(args.raw.read_bytes()).hexdigest()
    (args.raw.parent / 'behavior-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ('details', 'limit')}, ensure_ascii=True))
    if result['behaviorPassed'] != len(rows): raise SystemExit(1)
if __name__ == '__main__': main()
