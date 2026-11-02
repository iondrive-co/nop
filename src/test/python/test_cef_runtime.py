import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('cef_runtime', ROOT / 'scripts/build-cef-runtime.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CefBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.base = self.directory / 'upstream.jar'
        self.output = self.directory / 'corrected.jar'
        self.original = b'invented native code: metrics=1; other code'
        self.corrected = self.original.replace(b'metrics=1', b'metrics=0')
        self.config = {
            'version': '1.0', 'revision': 'metrics-off',
            'linux.amd64.originalSha256': hashlib.sha256(self.original).hexdigest(),
            'linux.amd64.patchedSha256': hashlib.sha256(self.corrected).hexdigest(),
            'linux.amd64.patchOffset': str(self.original.index(b'metrics=1')),
            'linux.amd64.patchBefore': b'metrics=1'.hex(),
            'linux.amd64.patchAfter': b'metrics=0'.hex(),
        }

    def bundle(self, members=None):
        if members is None:
            members = {'libcef.so': self.original, 'libjcef.so': b'invented JNI library',
                       'icudtl.dat': b'invented browser resource'}
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w:gz') as archive:
            for name, content in members.items():
                info = tarfile.TarInfo(name)
                info.size = len(content)
                info.mode = 0o755 if name.endswith('.so') else 0o644
                archive.addfile(info, io.BytesIO(content))
        with zipfile.ZipFile(self.base, 'w') as archive:
            archive.writestr('native.tar.gz', data.getvalue())
            archive.writestr('me/friwi/jcefmaven/CefNativeBundle.class', b'invented discovery class')

    def test_changes_only_provider_flag_and_preserves_native_resources(self):
        self.bundle()
        original_jar = self.base.read_bytes()
        metadata = module.package(self.base, self.output, self.config, 'amd64')
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(b'invented discovery class', archive.read('me/friwi/jcefmaven/CefNativeBundle.class'))
            self.assertEqual(metadata, json.loads(archive.read('META-INF/nop-cef-runtime.json')))
            with tarfile.open(fileobj=io.BytesIO(archive.read('native.tar.gz')), mode='r:gz') as native:
                self.assertEqual(self.corrected, native.extractfile('libcef.so').read())
                self.assertEqual(b'invented JNI library', native.extractfile('libjcef.so').read())
                self.assertEqual(b'invented browser resource', native.extractfile('icudtl.dat').read())
                self.assertEqual(0o755, native.getmember('libcef.so').mode)
        self.assertEqual(original_jar, self.base.read_bytes())
        self.assertEqual(hashlib.sha256(self.corrected).hexdigest(), metadata['librarySha256'])
        first_output = self.output.read_bytes()
        module.package(self.base, self.output, self.config, 'amd64')
        self.assertEqual(first_output, self.output.read_bytes())

    def test_unknown_native_binary_is_rejected_without_replacing_existing_output(self):
        self.bundle({'libcef.so': b'unknown native binary'})
        self.output.write_bytes(b'existing bundle')
        with self.assertRaisesRegex(RuntimeError, 'Unrecognised upstream'):
            module.package(self.base, self.output, self.config, 'amd64')
        self.assertEqual(b'existing bundle', self.output.read_bytes())
        self.assertFalse(self.output.with_suffix('.tmp').exists())

    def test_wrong_initializer_or_patched_hash_is_rejected(self):
        self.bundle()
        cases = [('patchOffset', '0', 'initializer'),
                 ('patchedSha256', '0' * 64, 'checksum mismatch')]
        for key, value, message in cases:
            with self.subTest(key=key):
                config = dict(self.config, **{'linux.amd64.' + key: value})
                with self.assertRaisesRegex(RuntimeError, message):
                    module.package(self.base, self.output, config, 'amd64')
                self.assertFalse(self.output.exists())

    def test_missing_or_ambiguous_cef_library_is_rejected(self):
        for members in [{'libjcef.so': b'JNI'}, {'libcef.so': self.original, 'other/libcef.so': self.original}]:
            self.bundle(members)
            with self.assertRaisesRegex(RuntimeError, 'Expected one libcef.so'):
                module.package(self.base, self.output, self.config, 'amd64')
            self.assertFalse(self.output.exists())

    def test_unreviewed_architecture_has_no_stock_runtime_fallback(self):
        self.bundle()
        with self.assertRaisesRegex(RuntimeError, 'No reviewed CEF'):
            module.package(self.base, self.output, self.config, 'arm64')
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
