import base64
import unittest
from types import SimpleNamespace

from llm_clients import AnthropicOpenAiAdapter, ObservedVisionClient


class FakeAnthropicMessages:

    def __init__(self):
        self.request = None

    def create(self, **kwargs):
        self.request = kwargs
        return SimpleNamespace(content=[
            SimpleNamespace(type="text", text="recognized text")])


class AnthropicOpenAiAdapterTest(unittest.TestCase):

    def test_translates_openai_image_message_and_response(self):
        messages = FakeAnthropicMessages()
        client = SimpleNamespace(messages=messages)
        adapter = AnthropicOpenAiAdapter(client, maxTokens=2048)
        encoded = base64.b64encode(b"image").decode("ascii")

        response = adapter.chat.completions.create(
            model="claude-vision",
            messages=[{
                "role": "user",
                "content": [
                    {"type": "text", "text": "extract"},
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/png;base64,{encoded}"},
                    },
                ],
            }],
        )

        content = messages.request["messages"][0]["content"]
        self.assertEqual("image", content[1]["type"])
        self.assertEqual("image/png", content[1]["source"]["media_type"])
        self.assertEqual("recognized text", response.choices[0].message.content)


class ObservedVisionClientTest(unittest.TestCase):

    def test_records_failure_that_upstream_plugin_may_swallow(self):
        def fail(**kwargs):
            raise RuntimeError("vision unavailable")

        delegate = SimpleNamespace(
            chat=SimpleNamespace(
                completions=SimpleNamespace(create=fail)))
        client = ObservedVisionClient(delegate)

        with client.observe() as observation:
            with self.assertRaisesRegex(RuntimeError, "vision unavailable"):
                client.chat.completions.create(model="vision", messages=[])

        self.assertEqual(1, observation.calls)
        self.assertEqual(["RuntimeError"], observation.failures)

    def test_rejects_empty_vision_response(self):
        response = SimpleNamespace(
            choices=[SimpleNamespace(
                message=SimpleNamespace(content="  "))])
        delegate = SimpleNamespace(
            chat=SimpleNamespace(
                completions=SimpleNamespace(create=lambda **kwargs: response)))
        client = ObservedVisionClient(delegate)

        with client.observe() as observation:
            with self.assertRaisesRegex(RuntimeError, "empty content"):
                client.chat.completions.create(model="vision", messages=[])

        self.assertEqual(1, len(observation.failures))


if __name__ == "__main__":
    unittest.main()
