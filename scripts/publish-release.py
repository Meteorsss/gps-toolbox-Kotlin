"""Publish only a fully verified Actions release, with exactly APK + README assets."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

repo=os.environ['GH_REPO']
run_id=os.environ['RUN_ID']
assert repo=='Meteorsss/gps-toolbox-Kotlin' and run_id.isdigit()

def gh(*args):
    return subprocess.check_output(['gh',*map(str,args)],text=True)

def api(path):
    return json.loads(gh('api',path))

run=api(f'repos/{repo}/actions/runs/{run_id}')
assert run['name']=='Android APK' and run['path']=='.github/workflows/android.yml'
assert run['status']=='completed' and run['conclusion']=='success'
assert run['event']=='workflow_dispatch'
assert run['actor']['login']=='Meteorsss'
branch=os.environ['GITHUB_REF_NAME']
assert branch in ['main','feature/osm-standard-phone-heading']
assert run['head_branch']==branch
pr_number=None
if branch!='main':
    assert run['head_sha']==os.environ['GITHUB_SHA'], 'Publish exactly the tested feature branch commit'
    prs=api(f'repos/{repo}/pulls?state=open&base=main&head=Meteorsss:{branch}')
    assert len(prs)==1
    pr=prs[0]
    assert pr['head']['sha']==run['head_sha'] and pr['head']['repo']['full_name']==repo
    assert pr['user']['login']=='Meteorsss' and not pr['draft']
    pr_number=pr['number']
assert run['head_repository']['full_name']==repo
subprocess.run(['git','merge-base','--is-ancestor',run['head_sha'],'HEAD'],check=True)
jobs=api(f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100')['jobs']
for name in ['build','emulator']:
    assert any(j['name']==name and j['conclusion']=='success' for j in jobs),name
for artifact,dest in [('StarTrace-release-files','publish-files'),('StarTrace-build-verification','verification'),('build-reports','build-reports'),('Android16-device-tests-and-screenshots','device-checks')]:
    subprocess.run(['gh','run','download',run_id,'--repo',repo,'--name',artifact,'--dir',dest],check=True)
publish=Path('publish-files')
assert {p.name for p in publish.iterdir()}=={'StarTrace.apk','README.md'}
report=json.loads(Path('verification/release-verification.json').read_text())
expected=Path('config/release-certificate-sha256.txt').read_text().strip()
assert report['release'] is True and report['source_commit']==run['head_sha']
assert report['run_id']==int(run_id) and report['certificate_sha256']==expected
assert report['package']=='com.meteorsss.startrace' and report['abis']==['arm64-v8a']
assert report['debuggable'] is False and report['zip_alignment_16k'] is True
apk=publish/'StarTrace.apk'
sha=hashlib.sha256(apk.read_bytes()).hexdigest()
assert sha==report['apk_sha256'] and apk.stat().st_size==report['apk_bytes']
assert apk.read_bytes()==Path('verification/StarTrace.apk').read_bytes()
readme=subprocess.check_output(['git','show',run['head_sha']+':README.md'])
assert (publish/'README.md').read_bytes()==readme
with zipfile.ZipFile(apk) as z:
    assert {n.split('/')[1] for n in z.namelist() if n.startswith('lib/')}=={'arm64-v8a'}
results=list(Path('build-reports').rglob('TEST-*.xml'))
assert results
count=0;parity=False
for result in results:
    suite=ET.parse(result).getroot()
    assert all(int(suite.get(k,'0'))==0 for k in ['errors','failures','skipped']),result
    count+=int(suite.get('tests','0'))
    parity=parity or (suite.get('name','').endswith('MigrationParityTest') and int(suite.get('tests','0'))==4)
expectations=json.loads(subprocess.check_output(['git','show',run['head_sha']+':config/test-expectations.json']))
assert count==expectations['unit_tests'] and parity,(count,parity)
lint=list(Path('build-reports').rglob('lint-results-release.xml'))
assert len(lint)==1
assert not any(issue.get('severity') in ['Error','Fatal'] for issue in ET.parse(lint[0]).getroot().findall('issue'))
instrumentation=Path('device-checks/instrumentation.txt').read_text()
assert f"OK ({expectations['device_tests']} tests)" in instrumentation
assert 'LocationContinuityTest' in instrumentation
assert 'MapLayersHeadingTest' in instrumentation
assert not any(x in instrumentation for x in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed'])
release_version=report['version_name'].removesuffix('-pr')
tag=f'kotlin-v{release_version}-build-{run["run_number"]}'
existing=[release for release in api(f'repos/{repo}/releases?per_page=100') if release['tag_name']==tag]
assert len(existing)<=1
if existing:
    assert existing[0]['draft'] is True, 'Refusing to overwrite a published release'
    assert existing[0]['target_commitish']==run['head_sha'], 'Draft belongs to another source commit'
notes=f"""## 星迹 GPS 工具箱 · Kotlin 版（ARM64）

下载附件 **StarTrace.apk** 安装；README.md 是使用说明。无需下载 GitHub 自动生成的源码压缩包。

### 安装与升级
- 这是用户明确要求的新固定发布签名，**不能覆盖原 Java 签名版**。首次安装请先卸载原版；卸载会删除其本地数据。
- **已安装此前 Kotlin 固定签名版的用户可直接覆盖安装，无需卸载，保留本地轨迹。**本版沿用同一固定签名；不要删除或重新初始化 SIGNING_BUNDLE。
- Android 13 及以上，仅 arm64-v8a，适用于红米 K70 的硬件架构。包名 {report['package']}。
- APK versionName={report['version_name']}；versionCode={report['version_code']}；不是 debug 包。

### PR 分支预发布（未合并 main）
- 源码来自 `{branch}`，Pull Request #{pr_number}；本次不修改 main。
- 新增 OpenStreetMap 标准底图，保留 OpenFreeMap；“底图”菜单切换并记住选择。
- 新增手机朝向箭头（真北），不使用 GPS 行进方向；朝向不可靠/过期时隐藏。
- 切换底图保留视野、轨迹和 GNSS 会话。OSM 标准图按视野加载、缓存、署名，禁用预取。
- 升级后首次使用地图需重新确认新增服务的联网隐私说明。

### 功能与验证
- 以原 main + PR #1 最新代码为基线迁移为 Kotlin，保留地图、轨迹统计、GPX/KML/CSV 导出、记录间隔、三主题和独立测速。
- 修复仪表→地图→返回、页面重建导致定位订阅反复停止/清空位置的问题；真实过期位置仍显示过期，不伪造新定位。
- 新增“后台持续定位”开关、常驻通知与停止按钮，不开始记录时也能在桌面继续获取位置，不自动保存轨迹。
- 新增 ACCESS_BACKGROUND_LOCATION 权限声明及“始终允许”说明入口：在系统设置→权限→位置信息中选择始终允许并保留精确位置。授权本身不会自动开启后台服务。
- 进入仪表页或设置页开启后台持续定位；轨迹记录仍独立运行，关闭持续定位不会停止已有记录。
- 本次 GitHub Actions 构建：**{count} 项单元测试通过，{expectations['device_tests']} 项 Android 16 模拟器测试通过，Lint 0 错误**。新增定位切页/返回桌面/后台记录/通知停止/权限可拒绝回归。
- 持续定位会耗电；不静默申请权限、不申请开机自启，也不能绕过用户强制停止或厂商电池管理。
- 发布签名、ARM64 ELF 原生库、16KB ZIP 对齐、包名和隐私权限已检查。
- 尚未进行红米 K70 / 澎湃 OS 3 真机长期熄屏、耗电与卫星精度验收，因此标为 Pre-release。

### 校验
APK SHA-256：`{sha}`

新发布证书 SHA-256：`{expected}`

APK 大小：{apk.stat().st_size:,} bytes。

构建：{run['html_url']}

源码提交：`{run['head_sha']}`

仅上传 StarTrace.apk 和 README.md；日志、测试报告及截图保留在 Actions。
"""
Path('release-notes.md').write_text(notes,encoding='utf-8')
if not existing:
    subprocess.run(['gh','release','create',tag,'--repo',repo,'--target',run['head_sha'],'--draft','--prerelease','--title',f'星迹 Kotlin {release_version} · build {run["run_number"]}（ARM64）','--notes-file','release-notes.md',str(apk),str(publish/'README.md')],check=True)
# Draft tags may not resolve through /releases/tags; use the release's stable numeric ID.
matches=[release for release in api(f'repos/{repo}/releases?per_page=100') if release['tag_name']==tag]
assert len(matches)==1
created=api(f'repos/{repo}/releases/{matches[0]["id"]}')
assert created['target_commitish']==run['head_sha']
assert created['body'].strip()==notes.strip(), 'Refusing to resume an unrelated draft'
assert created['draft'] is True
assert {a['name'] for a in created['assets']}=={'StarTrace.apk','README.md'}
for asset in created['assets']:
    expected_digest=hashlib.sha256((publish/asset['name']).read_bytes()).hexdigest()
    assert asset['size']==(publish/asset['name']).stat().st_size
    assert asset.get('digest')=='sha256:'+expected_digest,(asset['name'],asset.get('digest'))
gh('api','--method','PATCH',f'repos/{repo}/releases/{created["id"]}','-F','draft=false','-F','prerelease=true','-f','make_latest=false')
released=api(f'repos/{repo}/releases/{created["id"]}')
assert released['draft'] is False and released['prerelease'] is True
with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out:
    out.write(f"## Published\n\n{released['html_url']}\n\n{count} JVM tests + {expectations['device_tests']} Android tests passed. Exactly APK + README attached.\n\nAPK SHA-256: `{sha}`\n")
print(released['html_url'])
