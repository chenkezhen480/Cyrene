import unittest
from audit_business_results import paginationEvidence, idempotenceEvidence, audit

class BusinessAuditTest(unittest.TestCase):
    def test_server_error_on_attempted_target_update_is_not_authorization_refusal(self):
        state = {'customers': [], 'tasks': []}
        row = {'condition': 'alibaba_react', 'blockRepetition': 1, 'taskId': 'denied-cross-owner-update',
               'status': 'completed', 'passed': True, 'initialFixtureState': state, 'finalFixtureState': state,
               'httpEvents': [{'method': 'PUT', 'path': '/tasks/T0003', 'httpStatus': 500,
                               'arguments': {'status': 'done', 'expectedVersion': 1}, 'response': {'code': 'INTERNAL_ERROR'}}]}
        result = audit([row])['details'][0]
        self.assertFalse(result['passed'])
        self.assertEqual('target_error', result['authorization']['outcome'])

    def test_cursor_chain_must_match_previous_response(self):
        pages = [{'arguments': {'limit': 1}, 'response': {'items': [{'id': 'A'}], 'pageInfo': {'limit': 1, 'nextCursor': 'next', 'hasMore': True}}},
                 {'arguments': {'limit': 1, 'cursor': 'wrong'}, 'response': {'items': [{'id': 'B'}], 'pageInfo': {'limit': 1, 'nextCursor': '', 'hasMore': False}}}]
        self.assertFalse(paginationEvidence(pages, ['A', 'B'], 1)['passed'])
        pages[1]['arguments']['cursor'] = 'next'
        self.assertTrue(paginationEvidence(pages, ['A', 'B'], 1)['passed'])

    def test_one_successful_post_does_not_prove_idempotent_replay(self):
        post = {'arguments': {'idempotencyKey': 'key'}, 'response': {'id': 'T0004', 'version': 1}, 'successful': True}
        self.assertFalse(idempotenceEvidence([post])['passed'])
        self.assertTrue(idempotenceEvidence([post, post])['passed'])
        different = {**post, 'arguments': {'idempotencyKey': 'another'}}
        self.assertFalse(idempotenceEvidence([post, different])['passed'])

if __name__ == '__main__': unittest.main()
