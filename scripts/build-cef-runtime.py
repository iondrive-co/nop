#!/usr/bin/env python3
"""Package Linux CEF with its optional malloc dump metrics disabled.

Only an exact, reviewed upstream library is accepted. Both the original and
patched library hashes are pinned; no installed runtime is modified in place.
"""
import argparse
import copy
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / 'native/cef/runtime.properties'


def properties(path):
    return dict(line.strip().split('=', 1) for line in path.read_text().splitlines()
                if line.strip() and not line.lstrip().startswith('#'))


def patch_config(config, arch):
    prefix = 'linux.' + arch + '.'
    try:
        values = {key: config[prefix + key] for key in
                  ['originalSha256', 'patchedSha256', 'patchOffset', 'patchBefore', 'patchAfter']}
    except KeyError:
        raise RuntimeError('No reviewed CEF malloc-provider patch for Linux ' + arch) from None
    if not all(re.fullmatch('[0-9a-f]{64}', values[key])
               for key in ['originalSha256', 'patchedSha256']):
        raise RuntimeError('Invalid CEF library checksum')
    return values


def disable_malloc_metrics(source, target, config):
    shutil.copyfileobj(source, target, 1024 * 1024)
    target.seek(0)
    if hashlib.file_digest(target, 'sha256').hexdigest() != config['originalSha256']:
        raise RuntimeError('Unrecognised upstream libcef.so; refusing to patch it')
    offset = int(config['patchOffset'], 0)
    before, after = bytes.fromhex(config['patchBefore']), bytes.fromhex(config['patchAfter'])
    if not before or len(before) != len(after) or before == after:
        raise RuntimeError('Invalid CEF provider patch')
    target.seek(offset)
    if target.read(len(before)) != before:
        raise RuntimeError('Unexpected CEF malloc-provider initializer')
    target.seek(offset)
    target.write(after)
    target.seek(0)
    if hashlib.file_digest(target, 'sha256').hexdigest() != config['patchedSha256']:
        raise RuntimeError('Patched CEF library checksum mismatch')
    target.seek(0)


def package(base_jar, output, config, arch):
    patch = patch_config(config, arch)
    metadata = {'revision': config['revision'], 'version': config['version'],
                'librarySha256': patch['patchedSha256']}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.tmp')
    try:
        with zipfile.ZipFile(base_jar) as source, zipfile.ZipFile(temporary, 'w') as target:
            archives = [entry for entry in source.infolist() if entry.filename.endswith('.tar.gz')]
            if len(archives) != 1:
                raise RuntimeError('Expected one upstream CEF native archive')
            for entry in source.infolist():
                if entry != archives[0]:
                    target.writestr(copy.copy(entry), source.read(entry))
                    continue
                with target.open(copy.copy(entry), 'w', force_zip64=True) as raw:
                    with gzip.GzipFile(fileobj=raw, mode='wb', mtime=0) as compressed:
                        with tarfile.open(fileobj=compressed, mode='w|') as result:
                            with source.open(entry) as original, tarfile.open(fileobj=original, mode='r|gz') as archive:
                                replaced = 0
                                for member in archive:
                                    if Path(member.name).name == 'libcef.so' and member.isfile():
                                        with tempfile.TemporaryFile() as fixed:
                                            disable_malloc_metrics(archive.extractfile(member), fixed, patch)
                                            result.addfile(member, fixed)
                                        replaced += 1
                                    else:
                                        result.addfile(member, archive.extractfile(member) if member.isfile() else None)
                                if replaced != 1:
                                    raise RuntimeError('Expected one libcef.so in the native archive')
            target.writestr(zipfile.ZipInfo('META-INF/nop-cef-runtime.json'), json.dumps(metadata, sort_keys=True))
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-native', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--metadata', type=Path, required=True)
    parser.add_argument('--arch', required=True)
    args = parser.parse_args()
    config = properties(CONFIG)
    metadata = package(args.base_native, args.output, config, args.arch)
    args.metadata.parent.mkdir(parents=True, exist_ok=True)
    args.metadata.write_text('directory=runtime-' + config['version'] + '-' + config['revision'] + '-' + metadata['librarySha256'][:12] + '\n' +
                             'linuxLibcefSha256=' + metadata['librarySha256'] + '\n')
    print('CEF malloc diagnostics disabled:', args.output, flush=True)


if __name__ == '__main__':
    main()
