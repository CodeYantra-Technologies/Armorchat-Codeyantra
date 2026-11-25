#!/usr/bin/env python3
"""Offline license evidence inventory; lockfile entries are not a shipped SBOM."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen
from urllib.parse import quote
import base64
import tarfile
import hashlib
import json
from pathlib import Path
import subprocess
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def inventory():
    result = {'scope': 'Lockfile and local-cache evidence, not resolved release SBOM or legal clearance',
              'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'authors': sorted(set(subprocess.check_output(['git', 'log', '--all', '--format=%an <%ae>'], cwd=ROOT, text=True).splitlines())),
              'rust': [], 'npm': [], 'gradle_cached_poms': [], 'native_binaries': []}
    registries = list((Path.home()/'.cargo/registry/src').glob('*'))
    archives = {p.name: p for p in (Path.home()/'.cargo/registry/cache').glob('*/*.crate')}
    npm_index = {}
    npm_cache = Path.home()/'.npm/_cacache'
    for index in (npm_cache/'index-v5').glob('*/*/*'):
        if not index.is_file():
            continue
        for line in index.read_text(errors='replace').splitlines():
            try:
                entry = json.loads(line.split('\t', 1)[1])
                if entry.get('integrity'):
                    npm_index[entry['key']] = entry['integrity']
            except (ValueError, IndexError):
                continue
    for lock in sorted(ROOT.glob('android/native/*/Cargo.lock')):
        for dep in tomllib.loads(lock.read_text()).get('package', []):
            if not dep.get('source'):
                continue
            manifests = [r/f"{dep['name']}-{dep['version']}"/'Cargo.toml' for r in registries]
            manifest = next((p for p in manifests if p.is_file()), None)
            meta = tomllib.loads(manifest.read_text())['package'] if manifest else {}
            evidence = 'cached Cargo.toml' if manifest else 'missing cache'
            archive = archives.get(f"{dep['name']}-{dep['version']}.crate")
            if archive and digest(archive) == dep.get('checksum'):
                with tarfile.open(archive) as tar:
                    member = tar.extractfile(f"{dep['name']}-{dep['version']}/Cargo.toml")
                    meta = tomllib.loads(member.read().decode())['package']
                evidence = 'cached crate Cargo.toml; archive SHA-256 matches lockfile'
            result['rust'].append({'lockfile': str(lock.relative_to(ROOT)), 'name': dep['name'], 'version': dep['version'],
                                   'source': dep.get('source'), 'checksum': dep.get('checksum'),
                                   'license': meta.get('license'), 'license_file': meta.get('license-file'),
                                   'evidence': evidence})
    for lock in [ROOT/'android/relay/package-lock.json', ROOT/'deal-backend/package-lock.json']:
        for name, dep in json.loads(lock.read_text()).get('packages', {}).items():
            if not name:
                continue
            license_value = dep.get('license')
            evidence = 'lockfile' if license_value else 'missing cache'
            local_manifest = lock.parent/name/'package.json'
            if local_manifest.is_file():
                local = json.loads(local_manifest.read_text())
                if local.get('version') == dep.get('version'):
                    license_value = local.get('license')
                    evidence = 'installed package.json; archive not verified'
            integrity = npm_index.get('make-fetch-happen:request-cache:'+dep.get('resolved', ''))
            if integrity and integrity == dep.get('integrity') and integrity.startswith('sha512-'):
                hexhash = base64.b64decode(integrity.split('-', 1)[1]).hex()
                content = npm_cache/'content-v2/sha512'/hexhash[:2]/hexhash[2:4]/hexhash[4:]
                if content.is_file() and hashlib.sha512(content.read_bytes()).hexdigest() == hexhash:
                    try:
                        with tarfile.open(content) as tar:
                            member = tar.extractfile('package/package.json')
                            package = json.loads(member.read())
                        if package.get('version') == dep.get('version'):
                            license_value = package.get('license')
                            evidence = 'cached package.json; tarball integrity matches lockfile'
                    except (tarfile.TarError, KeyError, ValueError):
                        pass
            result['npm'].append({'lockfile': str(lock.relative_to(ROOT)), 'path': name, 'name': dep.get('name', name.rsplit('node_modules/', 1)[1]),
                                  'version': dep.get('version'), 'license': license_value, 'evidence': evidence,
                                  'integrity': dep.get('integrity'), 'dev': dep.get('dev', False),
                                  'optional': dep.get('optional', False)})
    cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
    for pom in sorted(cache.glob('*/*/*/*/*.pom')):
        try:
            tree = ET.parse(pom)
            licenses = [e.text for e in tree.findall('.//{*}licenses/{*}license/{*}name')]
            parts = pom.relative_to(cache).parts
            result['gradle_cached_poms'].append({'coordinate': ':'.join(parts[:3]), 'licenses': licenses,
                                                'scope': 'cached artifact; release inclusion unverified'})
        except ET.ParseError:
            continue
    for binary in sorted(ROOT.glob('android/**/jniLibs/**/*.so')):
        result['native_binaries'].append({'path': str(binary.relative_to(ROOT)), 'sha256': digest(binary)})
    return result

if __name__ == '__main__':
    output = ROOT/'legal/license-inventory.json'
    parser = argparse.ArgumentParser()
    parser.add_argument('--online', action='store_true', help='Read exact-version license metadata from registry.npmjs.org')
    args = parser.parse_args()
    data = inventory()
    if args.online:
        def lookup(row):
            if row.get('license'):
                return row
            # Last node_modules segment identifies nested or scoped package names.
            name = row['name']
            url = 'https://registry.npmjs.org/'+quote(name, safe='')+'/'+quote(row['version'], safe='')
            try:
                with urlopen(Request(url, headers={'User-Agent': 'ArmorChat-license-inventory/1.0'}), timeout=20) as response:
                    meta = json.load(response)
                if meta.get('version') != row['version'] or meta.get('name') != name:
                    raise ValueError('version mismatch')
                row['license'] = meta.get('license') or meta.get('licenses')
                row['evidence'] = 'exact-version npm registry metadata; source license text not verified'
                row['metadata_url'] = url
            except Exception as error:
                row['lookup_error'] = type(error).__name__
            return row
        with ThreadPoolExecutor(max_workers=8) as pool:
            data['npm'] = list(pool.map(lookup, data['npm']))
    output.write_text(json.dumps(data, indent=2)+'\n')
    print(json.dumps({key: len(data[key]) for key in ['rust', 'npm', 'gradle_cached_poms', 'native_binaries']}))
