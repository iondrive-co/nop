import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('previous_manifest', ROOT / 'scripts/previous-update-manifest.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PreviousManifestTest(unittest.TestCase):
    def invoke(self, replies):
        output = io.BytesIO()
        errors = io.StringIO()
        with patch.object(module, 'get', side_effect=replies), \
                patch.object(module.sys, 'stdout', SimpleNamespace(buffer=output)), \
                patch.object(module.sys, 'stderr', errors):
            module.main(['previous-update-manifest.py', 'example/widget', 'v2.4.2', 'linux-x64'])
        return output.getvalue(), errors.getvalue()

    def releases(self):
        return json.dumps([{'tag_name': 'v2.4.1', 'assets': [{
            'name': 'update-linux-x64.json',
            'browser_download_url': 'https://downloads.example.invalid/previous.json',
        }]}]).encode()

    def test_previous_manifest_is_preserved_byte_for_byte(self):
        manifest = b'{"version":"2.4.1","files":{}}\n'
        output, errors = self.invoke([self.releases(), manifest])
        self.assertEqual(manifest, output)
        self.assertEqual('', errors)

    def test_unavailable_manifest_allows_a_full_update_without_a_delta(self):
        output, errors = self.invoke([self.releases(), OSError('fixture download unavailable')])
        self.assertEqual(b'', output)
        self.assertIn('could not read update manifest', errors)

    def test_unavailable_release_list_allows_a_full_update(self):
        output, errors = self.invoke([OSError('fixture listing unavailable')])
        self.assertEqual(b'', output)
        self.assertIn('could not list releases', errors)

    def test_no_previous_manifest_produces_no_delta(self):
        output, errors = self.invoke([b'[]'])
        self.assertEqual(b'', output)
        self.assertEqual('', errors)


if __name__ == '__main__':
    unittest.main()
