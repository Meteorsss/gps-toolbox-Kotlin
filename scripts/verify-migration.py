"""Fail CI if migration changes bundled UI/privacy resources or leaves app Java sources."""
import pathlib, subprocess, hashlib, json, re
import xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1]
base='defe34f40e192fb8ceb1b2c28cdf16a0cc4f0855'
paths=['app/src/main/res','app/src/main/assets']
subprocess.run(['git','diff','--exit-code',base,'--',*paths],cwd=root,check=True)
java=list((root/'app/src').rglob('*.java'))
assert not java, java
counts={kind:len(list((root/'app/src'/kind).rglob('*.kt'))) for kind in ['main','test','androidTest']}
assert counts['main']>=27, counts
files=subprocess.check_output(['git','ls-tree','-r','--name-only',base,'--',*paths],cwd=root,text=True).splitlines()
for name in files:
    original=subprocess.check_output(['git','show',f'{base}:{name}'],cwd=root)
    current=(root/name).read_bytes().replace(b'\r\n',b'\n')
    assert current==original.replace(b'\r\n',b'\n'),name
print(json.dumps({'baseline':base,'unchanged_resources':len(files),'kotlin_sources':counts,'java_sources':0},indent=2))

# Only the two explicitly requested manifest additions are permitted relative to migration.
manifest='app/src/main/AndroidManifest.xml'
original=subprocess.check_output(['git','show',f'{base}:{manifest}'],cwd=root).decode('utf-8')
current=ET.fromstring((root/manifest).read_text(encoding='utf-8'))
ns='{http://schemas.android.com/apk/res/android}'
permissions=[n for n in current.findall('uses-permission') if n.get(ns+'name')=='android.permission.ACCESS_BACKGROUND_LOCATION']
assert len(permissions)==1
current.remove(permissions[0])
application=current.find('application')
services=[n for n in application.findall('service') if n.get(ns+'name')=='.LocationHoldService']
assert len(services)==1 and services[0].get(ns+'foregroundServiceType')=='location'
assert services[0].get(ns+'exported')=='false' and services[0].get(ns+'stopWithTask')=='false'
application.remove(services[0])
assert ET.canonicalize(ET.tostring(current,encoding='unicode'),strip_text=True,rewrite_prefixes=True)==ET.canonicalize(original,strip_text=True,rewrite_prefixes=True)

# The new permission/consent feature intentionally replaces obsolete privacy wording only.
approved=json.loads((root/'config/approved-ui-wording-changes.json').read_text(encoding='utf-8'))
# All other original UI literals stay unchanged.

literal = re.compile(r'"(?:\\.|[^"\\])*"')
for kt in (root/'app/src/main/kotlin').rglob('*.kt'):
    source=f'app/src/main/java/com/meteorsss/startrace/{kt.stem}.java'
    exists=subprocess.run(['git','cat-file','-e',f'{base}:{source}'],cwd=root,capture_output=True)
    if exists.returncode: continue # new Kotlin feature class, not a migrated Java class
    old=subprocess.check_output(['git','show',f'{base}:{source}'],cwd=root).decode('utf-8')
    old_literals={v for v in literal.findall(old) if re.search(r'[\u4e00-\u9fff]',v)}
    new_literals=set(literal.findall(kt.read_text(encoding='utf-8')))
    removed=old_literals-new_literals
    assert removed==set(approved.get(kt.name,[])), (kt.name, removed)
print('PASS: original UI preserved except explicitly approved permission/privacy wording; manifest changes strictly scoped')
