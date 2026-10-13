import unittest
from run_business_comparison import businessTasks, blockOrder

class BusinessComparisonTest(unittest.TestCase):
    def test_business_subset_is_exact_and_keeps_contract(self):
        import json
        from pathlib import Path
        original = json.loads((Path(__file__).parent / 'tasks.json').read_text(encoding='utf-8'))
        filtered = businessTasks(original)
        self.assertEqual(11, len(filtered['tasks']))
        self.assertEqual(original['benchmarkDate'], filtered['benchmarkDate'])
        self.assertFalse(any(task['category'] in ('knowledge', 'mixed') for task in filtered['tasks']))
        for task in filtered['tasks']:
            self.assertIn(task, original['tasks'])
        self.assertEqual(16, len(original['tasks']))

    def test_each_round_contains_each_framework_once_and_first_rotates(self):
        self.assertEqual([('cyrene_business', 'alibaba_react'), ('alibaba_react', 'cyrene_business'),
                          ('cyrene_business', 'alibaba_react')], blockOrder(3))
        with self.assertRaises(ValueError): blockOrder(0)

if __name__ == '__main__': unittest.main()
