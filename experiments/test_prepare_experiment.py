import unittest

from prepare_experiment import parsePublishedPort, readModelAssignments, sanitizedModelMetadata


class PrepareExperimentTest(unittest.TestCase):
    def testPublishedPortRequiresLoopbackAndValidPort(self):
        self.assertEqual(parsePublishedPort('127.0.0.1:32781\n'), 32781)
        for address in ['0.0.0.0:32781', '127.0.0.1:0', '127.0.0.1:99999', 'invalid']:
            with self.subTest(address=address), self.assertRaises(ValueError):
                parsePublishedPort(address)

    def testAssignmentsDecodeQuotedValuesWithoutPrintingCredentials(self):
        assignments = readModelAssignments('# comment\nchat.provider=openai\nchat.model="model x"\nchat.apiKey="secret\\\"value"\n')
        self.assertEqual(assignments['chat.model'], 'model x')
        self.assertEqual(assignments['chat.apiKey'], 'secret"value')
        with self.assertRaises(ValueError):
            readModelAssignments('chat.model=x\nchat.model=y')

    def testMetadataExcludesKeysAndEndpoints(self):
        config = {'chat.provider': 'openai', 'chat.model': 'test-model',
                  'chat.apiKey': 'secret-key', 'chat.baseUrl': 'https://secret.example/?token=private',
                  'routing.provider': 'jev', 'routing.model': 'jev-latest', 'routing.apiKey': 'other-secret'}
        metadata = sanitizedModelMetadata(config)
        self.assertEqual(metadata['chatModel'], 'test-model')
        self.assertEqual(metadata['routingProvider'], 'jev')
        self.assertNotIn('secret', str(metadata))
        self.assertNotIn('baseUrl', str(metadata))


if __name__ == '__main__':
    unittest.main()
