import unittest
from analyze_results import summarize


class AnalyzeResultsTest(unittest.TestCase):
    def testFailedTrialRemainsInDenominatorAndUnknownUsageIsNotZero(self):
        rows = [dict(condition='wiki_on', taskId='a', status='completed', passed=True,
                     durationMs=100, totalTokens=25, trace={'metadata': {
                         'llmUsageCallCount': '1', 'llmUsageCall1InputTokens': '20', 'llmUsageCall1OutputTokens': '5'}}),
                dict(condition='wiki_on', taskId='b', status='error', passed=False,
                     durationMs=300, totalTokens=None, trace=None)]
        result = summarize(rows).get('wiki_on', {})
        self.assertEqual(result.get('trials'), 2)
        self.assertEqual(result['passed'], 1)
        self.assertEqual(result['successRate'], 0.5)
        self.assertEqual(result['inputTokens']['observedTrials'], 1)
        self.assertEqual(result['inputTokens']['meanObserved'], 20)
        self.assertEqual(result['durationMs']['p95'], 300)

    def testUsageMayBeExplicitZeroAndConditionsRemainSeparate(self):
        rows = [dict(condition='wiki_off', taskId='a', status='completed', passed=True,
                     durationMs=10, totalTokens=0, trace={'metadata': {
                         'llmUsageCallCount': '1', 'llmUsageCall1InputTokens': '0', 'llmUsageCall1OutputTokens': '0'}}),
                dict(condition='wiki_on', taskId='a', status='completed', passed=False,
                     durationMs=20, totalTokens=7)]
        result = summarize(rows)
        self.assertEqual(result.get('wiki_off', {}).get('inputTokens', {}).get('observedTrials'), 1)
        self.assertEqual(result['wiki_off']['inputTokens']['meanObserved'], 0)
        self.assertIsNone(result['wiki_on']['inputTokens']['meanObserved'])
        self.assertEqual(result['wiki_on']['failureTaskIds'], ['a'])

    def testPartialCallsAndTraceDefaultZeroAreNotCompleteUsage(self):
        rows = [dict(condition='wiki_on', taskId='partial', status='completed', passed=True,
                     durationMs=10, totalTokens=20, trace={'metadata': {
                         'llmUsageCallCount': '2', 'llmInputTokens': '20',
                         'llmUsageCall1InputTokens': '20', 'llmUsageCall1OutputTokens': '5',
                         'llmUsageCall2OutputTokens': '7'}}),
                dict(condition='wiki_on', taskId='beforeModel', status='error', passed=False,
                     durationMs=20, totalTokens=0, trace={'metadata': {}})]
        result = summarize(rows)['wiki_on']
        self.assertIsNone(result['totalTokens']['meanObserved'])
        self.assertEqual(result['inputTokens']['observedTrials'], 0)
        self.assertEqual(result['inputTokens']['partialTrials'], 1)
        self.assertEqual(result['inputTokens']['unknownTrials'], 1)
        self.assertEqual(result['outputTokens']['meanObserved'], 12)

    def testNegativeApiUsageIsRejected(self):
        row = dict(condition='wiki_on', taskId='bad', status='completed', passed=True,
                   durationMs=1, trace={'metadata': {
                       'llmUsageCallCount': '1', 'llmUsageCall1InputTokens': '-1'}})
        with self.assertRaises(ValueError):
            summarize([row])

    def testTotalCoverageRequiresInputAndOutputFromTheSameCall(self):
        row = dict(condition='wiki_on', taskId='partial', status='completed', passed=True,
                   durationMs=1, trace={'metadata': {'llmUsageCallCount': '2',
                       'llmUsageCall1InputTokens': '20', 'llmUsageCall2OutputTokens': '5'}})
        result = summarize([row])['wiki_on']['totalTokens']
        self.assertEqual(result['observedCalls'], 0)
        self.assertEqual(result['partialTrials'], 1)

    def testKnowledgeUsageIsSeparatedFromBusinessUsage(self):
        rows = [dict(condition='wiki_on', taskId=str(index), category=category,
                     status='completed', passed=True, durationMs=tokens,
                     trace={'metadata': {'llmUsageCallCount': '1', 'llmUsageCall1InputTokens': str(tokens)}})
                for index, (category, tokens) in enumerate((('knowledge', 20), ('businessRead', 100)))]
        result = summarize(rows)['wiki_on']
        self.assertEqual(result['inputTokens']['meanObserved'], 60)
        self.assertEqual(result['categories']['knowledge']['inputTokens']['meanObserved'], 20)


if __name__ == '__main__':
    unittest.main()
