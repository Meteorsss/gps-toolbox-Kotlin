"""Verify Actions-built APKs; only an independently pinned release certificate may publish."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

root=Path(__file__).resolve().parents[1]
os.chdir(root)
dist=Path('dist');dist.mkdir(exist_ok=True)
release=bool(os.environ.get('SIGNING_STORE_FILE'))
source=Path('app/build/outputs/apk/release/app-release.apk' if release else 'app/build/outputs/apk/debug/app-debug.apk')
apk=dist/('StarTrace.apk' if release else 'StarTrace-Kotlin-debug.apk')
shutil.copyfile(source,apk)
tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'

def command(name,*args):
    return subprocess.check_output([str(tools/name),*map(str,args)],text=True,stderr=subprocess.STDOUT)

permissions=command('aapt','dump','permissions',apk)
badging=command('aapt','dump','badging',apk)
(dist/'permissions.txt').write_text(permissions)
(dist/'apk-info.txt').write_text(badging)
assert 'android.permission.ACCESS_BACKGROUND_LOCATION' in permissions
assert 'android.permission.INTERNET' in permissions
match=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
assert match, 'Package metadata missing'
package,code,version=match.groups()
assert package=='com.meteorsss.startrace'
assert "sdkVersion:'33'" in badging and "targetSdkVersion:'36'" in badging
report={'release':release,'package':package,'version_code':int(code),'version_name':version,'source_commit':os.environ['GITHUB_SHA'],'run_id':int(os.environ['GITHUB_RUN_ID'])}
if release:
    expected=(root/'config/release-certificate-sha256.txt').read_text().strip()
    assert re.fullmatch(r'[0-9a-f]{64}',expected)
    signature=command('apksigner','verify','--verbose','--print-certs',apk)
    (dist/'signature.txt').write_text(signature)
    fingerprints=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)',signature)
    assert fingerprints==[expected], f'Unexpected certificate: {fingerprints}'
    assert 'application-debuggable' not in badging, 'Release must not be debuggable'
    alignment=command('zipalign','-c','-P','16','-v','4',apk)
    (dist/'alignment.txt').write_text(alignment)
    with zipfile.ZipFile(apk) as z:
        libs=[n for n in z.namelist() if n.startswith('lib/') and not n.endswith('/')]
        abis=sorted({n.split('/')[1] for n in libs})
        assert abis==['arm64-v8a'] and libs, abis
        for name in libs:
            binary=z.read(name)
            assert binary[:4]==b'\x7fELF' and binary[4:6]==b'\x02\x01',name
            assert struct.unpack_from('<H',binary,18)[0]==183,name
        (dist/'abi-report.txt').write_text('\n'.join(f'{name}: ELF64 AArch64' for name in libs)+'\n')
    assert "native-code: 'arm64-v8a'" in badging
    report.update({'certificate_sha256':expected,'debuggable':False,'abis':abis,'native_libraries':libs,'zip_alignment_16k':True})
sha=hashlib.sha256(apk.read_bytes()).hexdigest()
report.update({'apk_sha256':sha,'apk_bytes':apk.stat().st_size})
(dist/'SHA256SUMS.txt').write_text(f'{sha}  {apk.name}\n')
(dist/'release-verification.json').write_text(json.dumps(report,indent=2)+'\n')
for name in ['README.md','THIRD_PARTY_NOTICES.md']:shutil.copyfile(name,dist/name)
if release:
    publish=Path('release-files');publish.mkdir(exist_ok=True)
    shutil.copyfile(apk,publish/'StarTrace.apk')
    shutil.copyfile('README.md',publish/'README.md')
    assert {p.name for p in publish.iterdir()}=={'StarTrace.apk','README.md'}
print(json.dumps(report,indent=2))
