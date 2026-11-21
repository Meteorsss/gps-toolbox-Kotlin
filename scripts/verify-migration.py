"""Fail CI if migration changes bundled UI/privacy resources or leaves app Java sources."""
import pathlib, subprocess, hashlib, json
root=pathlib.Path(__file__).resolve().parents[1]
base='defe34f40e192fb8ceb1b2c28cdf16a0cc4f0855'
paths=['app/src/main/AndroidManifest.xml','app/src/main/res','app/src/main/assets']
subprocess.run(['git','diff','--exit-code',base,'--',*paths],cwd=root,check=True)
java=list((root/'app/src').rglob('*.java'))
assert not java, java
counts={kind:len(list((root/'app/src'/kind).rglob('*.kt'))) for kind in ['main','test','androidTest']}
assert counts['main']==27, counts
files=subprocess.check_output(['git','ls-tree','-r','--name-only',base,'--',*paths],cwd=root,text=True).splitlines()
for name in files:
    original=subprocess.check_output(['git','show',f'{base}:{name}'],cwd=root)
    current=(root/name).read_bytes().replace(b'\r\n',b'\n')
    assert current==original.replace(b'\r\n',b'\n'),name
print(json.dumps({'baseline':base,'unchanged_resources':len(files),'kotlin_sources':counts,'java_sources':0},indent=2))
