from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from textwrap import dedent
import unittest

ROOT = Path(__file__).resolve().parents[3]
RELEASE_WORKFLOW = ROOT / '.github/workflows/release.yml'
CI_WORKFLOW = ROOT / '.github/workflows/ci.yml'


def split_jobs(yaml_text: str) -> tuple[str, dict[str, str]]:
    lines = yaml_text.splitlines()
    jobs_index = next(i for i, line in enumerate(lines) if line.strip() == 'jobs:')
    header = '\n'.join(lines[:jobs_index])
    jobs: dict[str, list[str]] = {}
    current: str | None = None
    for line in lines[jobs_index + 1:]:
        match = re.match(r'^  ([A-Za-z0-9_-]+):\s*$', line)
        if match:
            current = match.group(1)
            jobs[current] = []
        elif current is not None:
            jobs[current].append(line)
    return header, {name: '\n'.join(body) for name, body in jobs.items()}


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.text = RELEASE_WORKFLOW.read_text()
        self.header, self.jobs = split_jobs(self.text)

    def test_release_publishing_is_isolated_to_publish_job_gated_on_all_checks(self):
        self.assertNotIn('contents: write', self.header,
                         'top-level permissions must not let build or test jobs publish releases')
        self.assertIn('test', self.jobs)
        self.assertIn('build', self.jobs)
        self.assertIn('publish', self.jobs)

        self.assertNotIn('action-gh-release', self.jobs['build'],
                         'matrix build legs must never publish directly to a GitHub release')
        self.assertNotIn('action-gh-release', self.jobs['test'])

        publish = self.jobs['publish']
        self.assertIn('action-gh-release', publish)
        self.assertIn('contents: write', publish)
        needs = re.search(r'^\s*needs:\s*\[([^\]]+)\]', publish, re.MULTILINE)
        self.assertIsNotNone(needs, 'publish job must declare needs: [test, build]')
        required = {item.strip() for item in needs.group(1).split(',')}
        self.assertEqual({'test', 'build'}, required)
        self.assertNotIn('always()', publish)
        self.assertNotIn('failure()', publish)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", publish)

    def test_publish_verifies_every_platform_artifact_before_publishing(self):
        publish = self.jobs['publish']
        verify_step = publish.split('- name: Verify release artifacts\n', 1)[1].split('      - name:', 1)[0]
        script = dedent(verify_step.split('run: |\n', 1)[1])
        required = ('nop.deb', 'nop.msi', 'nop.dmg', 'nop-3.2.1-linux-x64.tar.gz',
                    'update-linux-x64.json', 'update-linux-x64.json.sig')
        with TemporaryDirectory() as scratch:
            release = Path(scratch, 'release')
            release.mkdir()
            for name in required:
                (release / name).write_bytes(b'fixture')

            def verify():
                return subprocess.run(['bash', '-c', script], cwd=scratch, capture_output=True, text=True)

            result = verify()
            self.assertEqual(result.returncode, 0, result.stderr)
            for name in required:
                with self.subTest(missing=name):
                    path = release / name
                    path.unlink()
                    result = verify()
                    self.assertNotEqual(result.returncode, 0, result.stdout)
                    self.assertIn('Missing required release artifact', result.stderr)
                    path.write_bytes(b'fixture')
            (release / 'update-linux-x64.json.sig').write_bytes(b'')
            result = verify()
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn('Invalid release artifact', result.stderr)

    def test_release_workflow_runs_unit_tests_and_matches_ci_local_expectations(self):
        ci_test = 'xvfb-run --auto-servernum ./gradlew test --no-daemon'
        self.assertIn(ci_test, CI_WORKFLOW.read_text())
        self.assertIn(ci_test, self.jobs['test'])
        for expected in (
            'xvfb-run -a ./gradlew testCefPackaging testCefMemory testCefShutdown testSlackSidebar --no-daemon',
            './gradlew ${{ matrix.task }} --no-daemon',
            'Self-update bundle (Linux)',
            'task: packageDeb',
        ):
            self.assertIn(expected, self.jobs['build'])


if __name__ == '__main__':
    unittest.main()
