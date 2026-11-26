"""Reject signing unless the owner explicitly selects the current, same-repository open PR SHA."""
import json, os, subprocess
repo = os.environ['GH_REPO']
branch = 'feature/osm-standard-phone-heading'
assert repo == 'Meteorsss/gps-toolbox-Kotlin'
assert os.environ['GITHUB_EVENT_NAME'] == 'workflow_dispatch'
assert os.environ['GITHUB_ACTOR'] == 'Meteorsss'
assert os.environ['GITHUB_REF'] == 'refs/heads/' + branch
assert os.environ['RELEASE_SHA'] == os.environ['GITHUB_SHA']
prs = json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/pulls?state=open&base=main&head=Meteorsss:{branch}']))
assert len(prs) == 1
pr = prs[0]
assert pr['head']['sha'] == os.environ['GITHUB_SHA']
assert pr['head']['repo']['full_name'] == repo and pr['base']['repo']['full_name'] == repo
assert pr['user']['login'] == 'Meteorsss' and not pr['draft']
print(f"Approved open PR #{pr['number']} at exact SHA {pr['head']['sha']}; main is not modified.")
