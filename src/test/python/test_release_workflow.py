from pathlib import Path
import os
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
                    'update-linux-x64.json', 'update-linux-x64.json.sig',
                    'nop-3.2.1-macos-arm64.tar.gz', 'update-macos-arm64.json', 'update-macos-arm64.json.sig')
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
        ci_header, ci_jobs = split_jobs(CI_WORKFLOW.read_text())
        ci_test = 'xvfb-run --auto-servernum ./gradlew test --no-daemon'
        self.assertIn('workflow_call:', ci_header)
        self.assertIn(ci_test, ci_jobs['test'])
        self.assertIn('uses: ./.github/workflows/ci.yml', self.jobs['test'])
        ref_input = "ref: ${{ github.ref_type != 'tag' && inputs.tag || '' }}"
        self.assertIn(ref_input, self.jobs['test'])
        self.assertIn(ref_input, self.jobs['build'])
        for expected in (
            'xvfb-run -a ./gradlew testCefPackaging testCefMemory testCefShutdown testSlackSidebar --no-daemon',
            './gradlew ${{ matrix.task }} --no-daemon',
            'Self-update bundle (Linux and macOS)',
            'task: packageDeb',
        ):
            self.assertIn(expected, self.jobs['build'])

    def test_release_script_stops_before_tagging_if_local_checks_fail(self):
        with TemporaryDirectory() as scratch:
            repo = Path(scratch)
            scripts = repo / 'scripts'
            scripts.mkdir()
            (repo / 'build.gradle.kts').write_text('packageVersion = "3.2.1"\n')
            release = scripts / 'release.sh'
            release.write_bytes((ROOT / 'scripts/release.sh').read_bytes())

            git = repo / 'git'
            git.write_text(dedent('''\
                #!/bin/bash
                case "$*" in
                    'status --porcelain') exit 0 ;;
                    'rev-parse --abbrev-ref HEAD') echo main ;;
                    'rev-parse -q --verify '*) exit 1 ;;
                    'rev-parse HEAD') echo candidate ;;
                    *) printf 'git %s\\n' "$*" >> "$CI_CALLS"; exit 9 ;;
                esac
                '''))
            preflight = scripts / 'ci-local.sh'
            preflight.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$CI_CALLS"\nexit 1\n')
            for stub in (git, preflight):
                stub.chmod(0o755)
            calls = repo / 'calls'
            env = dict(os.environ, PATH=f'{repo}:{os.environ["PATH"]}', CI_CALLS=str(calls),
                       NOP_RELEASE_SKIP_LOCAL_CI='1')
            result = subprocess.run(['bash', str(release)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn('local Linux checks failed', result.stderr)
            self.assertEqual(calls.read_text().splitlines(), ['--commit candidate --version 3.2.2'])
            self.assertEqual((repo / 'build.gradle.kts').read_text(), 'packageVersion = "3.2.1"\n')

    def test_ci_local_keeps_temporary_files_on_the_scratch_disk(self):
        # A runner's /tmp is on disk; a local /tmp may be tmpfs.
        script = (ROOT / 'scripts/ci-local.sh').read_text()
        self.assertIn('JAVA_TOOL_OPTIONS="-Duser.home=$home -Djava.io.tmpdir=$run/tmp"', script)
        self.assertIn('TMPDIR="$run/tmp"', script)


if __name__ == '__main__':
    unittest.main()
