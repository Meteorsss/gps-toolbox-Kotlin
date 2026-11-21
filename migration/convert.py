"""One-shot deterministic JetBrains J2K; no external AI service is used."""
import os, pathlib, shutil, subprocess, json, time, urllib.request, zipfile, html
root=pathlib.Path.cwd()
tmp=pathlib.Path(os.environ['RUNNER_TEMP'])
project=tmp/'conversion-project'
project.mkdir()
logs=root/'conversion-logs'; logs.mkdir()
out=root/'converted'; out.mkdir()
shutil.copytree(root/'app/src',project/'src')
libs=project/'libs'; libs.mkdir()
for p in (tmp/'migration-libs').iterdir():
    if p.suffix=='.jar': shutil.copy(p,libs/p.name)
    elif p.suffix=='.aar':
        with zipfile.ZipFile(p) as z:
            for n in z.namelist():
                if n.endswith('.jar'):
                    (libs/(p.stem+'-'+n.replace('/','-'))).write_bytes(z.read(n))
shutil.copy(pathlib.Path(os.environ['ANDROID_HOME'])/'platforms/android-36/android.jar',libs/'android.jar')
# J2K requires resolvable source modules, but does not need Android Studio or Gradle import.
idea=project/'.idea'; idea.mkdir()
(idea/'modules.xml').write_text('<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/app.iml" filepath="$PROJECT_DIR$/app.iml" /></modules></component></project>')
(idea/'misc.xml').write_text('<project version="4"><component name="ProjectRootManager" version="2" languageLevel="JDK_17" /></project>')
sources=''.join(f'<sourceFolder url="file://$MODULE_DIR$/src/{s}/java" isTestSource="{str(s!="main").lower()}" />' for s in ['main','test','androidTest'])
classes=''.join(f'<root url="jar://$MODULE_DIR$/libs/{html.escape(p.name)}!/" />' for p in libs.glob('*.jar'))
(project/'app.iml').write_text(f'<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager" LANGUAGE_LEVEL="JDK_17"><output url="file://$MODULE_DIR$/out"/><content url="file://$MODULE_DIR$/src">{sources}</content><orderEntry type="sourceFolder" forTests="false"/><orderEntry type="module-library"><library><CLASSES>{classes}</CLASSES><JAVADOC/><SOURCES/></library></orderEntry></component></module>')
vm=tmp/'conversion.vmoptions'
vm.write_text('\n'.join(['-Xmx5g','-Djava.awt.headless=true',f'-Didea.config.path={tmp}/ide-config',f'-Didea.system.path={tmp}/ide-system',f'-Didea.plugins.path={tmp}/ide-plugins',f'-Didea.log.path={logs}/idea','-Didea.initially.ask.config=false','-Dide.show.tips.on.startup.default.value=false']))
env=os.environ.copy();env['IDEA_VM_OPTIONS']=str(vm)
log=(logs/'server.log').open('w')
p=subprocess.Popen([str(tmp/'idea/bin/idea.sh'),'mcpServer',f'--project={project}','--port=64399','--invocation-mode=direct','--allowed-tools=convert_java_to_kotlin,get_project_modules'],stdout=log,stderr=subprocess.STDOUT,env=env)
seq=0;session=None
def rpc(method,params):
    global seq,session
    seq+=1
    headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream'}
    if session:headers['Mcp-Session-Id']=session
    req=urllib.request.Request('http://127.0.0.1:64399/stream',json.dumps({'jsonrpc':'2.0','id':seq,'method':method,'params':params}).encode(),headers)
    with urllib.request.urlopen(req,timeout=1200) as response:
        session=response.headers.get('Mcp-Session-Id') or session
        raw=response.read().decode()
    data=next((s[5:].strip() for s in raw.splitlines() if s.startswith('data:')),raw)
    result=json.loads(data)
    (logs/f'rpc-{seq}.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    return result
try:
    for i in range(600):
        if p.poll() is not None:raise RuntimeError('IDE exited: '+(logs/'server.log').read_text()[-10000:])
        if 'MCP Server started' in (logs/'server.log').read_text():break
        time.sleep(1)
    else:raise RuntimeError('IDE startup timeout')
    print(rpc('initialize',{'protocolVersion':'2024-11-05','capabilities':{},'clientInfo':{'name':'gps-toolbox-migration','version':'1'}}),flush=True)
    tools=rpc('tools/list',{})
    print(json.dumps(tools),flush=True)
    assert any(t['name']=='convert_java_to_kotlin' for t in tools['result']['tools'])
    print(rpc('tools/call',{'name':'get_project_modules','arguments':{'projectPath':str(project)}}),flush=True)
    files=sorted(str(f.relative_to(project)) for f in (project/'src').rglob('*.java'))
    result=rpc('tools/call',{'name':'convert_java_to_kotlin','arguments':{'projectPath':str(project),'file_paths':files}})
    print(json.dumps(result,ensure_ascii=False),flush=True)
    assert not result.get('error') and not result.get('result',{}).get('isError')
finally:
    shutil.copytree(project/'src',out/'src',dirs_exist_ok=True)
    p.terminate()
print('Kotlin files:',len(list(out.rglob('*.kt'))))
assert not list(out.rglob('*.java')),'Not all files converted'
