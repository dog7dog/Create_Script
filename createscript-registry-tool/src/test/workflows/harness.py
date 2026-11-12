"""Runs the real `run` scripts of the registry workflows (registry-template/.github/workflows) against fake `gh` and
`curl` commands and local git repositories. No network, no GitHub. S8.3 Phase 6, kept as a test since Phase 7
(run by WorkflowHarnessTest).

Usage: python3 -I harness.py --repo <CreateScript root> --tool <createscript-registry-tool launcher> --work <empty dir>

The RSA key it makes is a throwaway stand-in for the GitHub App key, created in --work and used only here."""
import argparse, base64, json, os, pathlib, shutil, subprocess, sys, tempfile
import yaml

parser = argparse.ArgumentParser()
parser.add_argument("--repo", required=True)
parser.add_argument("--tool", required=True)
parser.add_argument("--work", required=True)
cli = parser.parse_args()
REPO = pathlib.Path(cli.repo).resolve()
TOOL = pathlib.Path(cli.tool).resolve()
WORK = pathlib.Path(cli.work).resolve()
RUNS = WORK / "runs"
RUNS.mkdir(parents=True, exist_ok=True)
results = []

def wf(name):
    return yaml.safe_load((REPO / "registry-template/.github/workflows" / name).read_text())

def step(doc, job, name):
    return next(s for s in doc["jobs"][job]["steps"] if s.get("name") == name or s.get("id") == name)["run"]

# ---- fakes ----

FAKE_GH = r'''#!/usr/bin/env python3
import hashlib, json, os, re, sys
fake = os.environ["FAKE"]
args = sys.argv[1:]
with open(os.path.join(fake, "log"), "a") as f: f.write(json.dumps(args) + "\n")
# which token each call used (never the token itself): "app" when it is the token the test expects
with open(os.path.join(fake, "gh-tokens.log"), "a") as f:
    f.write(("app" if os.environ.get("GH_TOKEN") and os.environ.get("GH_TOKEN") == os.environ.get("FAKE_EXPECT_TOKEN") else "other") + "\n")
state_path = os.path.join(fake, "releases.json")
releases = json.load(open(state_path)) if os.path.exists(state_path) else []
def save(): json.dump(releases, open(state_path, "w"))
if args[0] == "release" and args[1] == "create":
    if os.environ.get("FAKE_RELEASE_FAIL"): sys.stderr.write("HTTP 422: tag already exists\n"); sys.exit(1)
    tag = args[2]
    files = [a for a in args[3:] if not a.startswith("-") and os.path.isfile(a)]
    assets = []
    for f in files:
        digest = "sha256:" + hashlib.sha256(open(f, "rb").read()).hexdigest()
        if os.environ.get("FAKE_DIGEST_OVERRIDE") and os.path.basename(f) == "index.json": digest = "sha256:" + "0" * 64
        assets.append({"name": os.path.basename(f), "state": "uploaded", "digest": digest})
    if os.environ.get("FAKE_EXTRA_ASSET"): assets.append({"name": "extra.txt", "state": "uploaded", "digest": "sha256:" + "1" * 64})
    if os.environ.get("FAKE_DROP_ASSET"): assets = assets[:1]
    releases.append({"id": 100 + len(releases), "tag_name": tag, "draft": "--draft" in args, "immutable": False, "assets": assets})
    save(); sys.exit(0)
assert args[0] == "api", args
method = "GET"; path = None; jq = None; fields = {}
i = 1
while i < len(args):
    a = args[i]
    if a == "-X": method = args[i + 1]; i += 2; continue
    if a == "--jq": jq = args[i + 1]; i += 2; continue
    if a in ("-F", "-f"): k, v = args[i + 1].split("=", 1); fields[k] = v; i += 2; continue
    if a.startswith("-"): i += 1; continue
    if path is None: path = a
    i += 1
def out(obj):
    text = json.dumps(obj)
    if jq:
        import subprocess
        text = subprocess.run(["jq", "-r", jq], input=text, capture_output=True, text=True, check=True).stdout
    sys.stdout.write(text)
m = re.fullmatch(r"repos/[^/]+/[^/]+/releases\?per_page=100", path)
if m: out([releases]); sys.exit(0)
m = re.fullmatch(r"repos/[^/]+/[^/]+/releases/(\d+)", path)
if m and method == "PATCH":
    r = next(r for r in releases if r["id"] == int(m.group(1)))
    if fields.get("draft") == "false": r["draft"] = False; r["immutable"] = os.environ.get("FAKE_NOT_IMMUTABLE") is None
    r["make_latest"] = fields.get("make_latest"); save(); out(r); sys.exit(0)
key = re.sub(r"[^A-Za-z0-9]", "_", path)
f = os.path.join(fake, "api", key)
if not os.path.exists(f): sys.stderr.write("gh: HTTP 404 " + path + "\n"); sys.exit(1)
out(json.load(open(f)))
'''

FAKE_CURL = r'''#!/usr/bin/env python3
import json, os, re, sys
args = sys.argv[1:]; method = "GET"; headers = []; body = None; url = None; i = 0
while i < len(args):
    a = args[i]
    if a == "-X": method = args[i + 1]; i += 2; continue
    if a == "-H": headers.append(args[i + 1]); i += 2; continue
    if a == "-d": body = sys.stdin.read() if args[i + 1] == "@-" else args[i + 1]; i += 2; continue
    if a in ("--max-time", "-o"): i += 2; continue
    if a.startswith("-"): i += 1; continue
    url = a; i += 1
fake = os.environ["FAKE"]
with open(os.path.join(fake, "curl.log"), "a") as f: f.write(json.dumps({"method": method, "url": url, "headers": headers, "body": body}) + "\n")
key = method + "_" + re.sub(r"[^A-Za-z0-9]", "_", url.split("://", 1)[1])
path = os.path.join(fake, "curl", key)
if not os.path.exists(path):
    sys.stderr.write("curl: (22) The requested URL returned error: 404\n"); sys.exit(22)
sys.stdout.write(open(path).read())
'''

def new_fake():
    fake = pathlib.Path(tempfile.mkdtemp(dir=RUNS))
    for d in ("bin", "api", "curl"): (fake / d).mkdir()
    (fake / "bin/gh").write_text(FAKE_GH); (fake / "bin/curl").write_text(FAKE_CURL)
    os.chmod(fake / "bin/gh", 0o755); os.chmod(fake / "bin/curl", 0o755)
    return fake

def env_for(fake, extra):
    env = {"PATH": f"{fake}/bin:" + os.environ.get("PATH", "/usr/bin:/bin"), "FAKE": str(fake), "HOME": str(fake), "TOOL": str(TOOL),
           "TMPDIR": str(fake), "GITHUB_STEP_SUMMARY": str(fake / "summary.md")}
    if "JAVA_HOME" in os.environ: env["JAVA_HOME"] = os.environ["JAVA_HOME"]
    env.update(extra)
    return env

def api(fake, path, obj):
    (fake / "api" / "".join(c if c.isalnum() else "_" for c in path)).write_text(json.dumps(obj))

def curl_response(fake, method, url, obj):
    (fake / "curl" / (method + "_" + "".join(c if c.isalnum() else "_" for c in url.split("://", 1)[1]))).write_text(json.dumps(obj))

def bash(script, env, cwd):
    f = tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False, dir=RUNS); f.write(script); f.close()
    r = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", f.name], env=env, cwd=cwd, capture_output=True, text=True)
    os.unlink(f.name)
    return r.returncode, r.stdout, r.stderr

def expect(name, condition, detail=""):
    results.append((name, bool(condition)))
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else f"  [{str(detail).strip()[:600]}]"))

def curl_log(fake):
    p = fake / "curl.log"
    return [json.loads(l) for l in p.read_text().splitlines()] if p.exists() else []

def gh_log(fake):
    p = fake / "log"
    return [json.loads(l) for l in p.read_text().splitlines()] if p.exists() else []

HEAD = "c" * 40
REPOSITORY = "example/CreateScript-Registry"

# ---------- validate.yml: the outdated-run guard ----------
validate = wf("validate.yml")
for job in ("validate", "approvals"):
    guard = step(validate, job, "Refuse an outdated run (the pull request has a newer commit)")
    for current, ok in ((HEAD, True), ("d" * 40, False)):
        fake = new_fake(); api(fake, f"repos/{REPOSITORY}/pulls/7", {"head": {"sha": current}})
        code, out, err = bash(guard, env_for(fake, {"GH_TOKEN": "t", "REPOSITORY": REPOSITORY, "PR_NUMBER": "7", "HEAD_SHA": HEAD}), fake)
        expect(f"validate/{job}: run for {'current' if ok else 'outdated'} head -> {'continues' if ok else 'stops'}", (code == 0) == ok, err)
    fake = new_fake()
    code, out, err = bash(guard, env_for(fake, {"GH_TOKEN": "t", "REPOSITORY": REPOSITORY, "PR_NUMBER": "7", "HEAD_SHA": HEAD}), fake)
    expect(f"validate/{job}: GitHub API error -> stops", code != 0, err)

# ---------- validate.yml: the report job (GitHub App) ----------
report = step(validate, "report", "Report the results as the registry's GitHub App")
key = WORK / "throwaway-mock-app-key.pem"
subprocess.run(["openssl", "genrsa", "-out", str(key), "2048"], check=True, capture_output=True)
subprocess.run(["openssl", "rsa", "-in", str(key), "-pubout", "-out", str(key) + ".pub"], check=True, capture_output=True)
KEY = key.read_text()
TOKEN = "ghs_mocktoken1234567890"

def report_env(fake, **over):
    e = {"GH_TOKEN": "t", "APP_ID": "123456", "APP_KEY": KEY, "REPOSITORY": REPOSITORY, "PR_NUMBER": "7", "HEAD_SHA": HEAD,
         "VALIDATE_RESULT": "success", "APPROVALS_RESULT": "failure"}
    e.update(over)
    return env_for(fake, e)

def report_fake(installed=True, checks_ok=True, revoke_ok=True, current=HEAD):
    fake = new_fake()
    api(fake, f"repos/{REPOSITORY}/pulls/7", {"head": {"sha": current}})
    if installed: curl_response(fake, "GET", f"https://api.github.com/repos/{REPOSITORY}/installation", {"id": 42})
    curl_response(fake, "POST", "https://api.github.com/app/installations/42/access_tokens", {"token": TOKEN, "expires_at": "x"})
    if checks_ok: curl_response(fake, "POST", f"https://api.github.com/repos/{REPOSITORY}/check-runs", {"id": 1})
    if revoke_ok: curl_response(fake, "DELETE", "https://api.github.com/installation/token", {})
    return fake

def token_leaked(fake, out, err, token=None):
    TOKEN = token or globals()["TOKEN"]
    # the token may appear only in the mask command; never in other output or in a file
    printed = (out.replace(f"::add-mask::{TOKEN}", "") + err)
    # files the script could have written (the fake's own canned responses and logs are not)
    fakes = {fake / "curl.log", fake / "log"}
    files = [p for p in fake.rglob("*") if p.is_file() and p not in fakes and (fake / "curl") not in p.parents and TOKEN in p.read_text(errors="ignore")]
    return TOKEN in printed or bool(files)

def revocations(fake):
    return [e for e in curl_log(fake) if e["method"] == "DELETE" and e["url"] == "https://api.github.com/installation/token"]

fake = report_fake()
code, out, err = bash(report, report_env(fake), fake)
log = curl_log(fake)
posts = [json.loads(e["body"]) for e in log if e["url"].endswith("/check-runs")]
expect("report: normal run exits 0", code == 0, err)
expect("report: posts exactly registry/validate and registry/approvals", [p["name"] for p in posts] == ["registry/validate", "registry/approvals"], posts)
expect("report: both on the event's head SHA", all(p["head_sha"] == HEAD for p in posts))
expect("report: success -> success, failure -> failure", [p["conclusion"] for p in posts] == ["success", "failure"], posts)
expect("report: check runs use the App token, not GITHUB_TOKEN", all(f"Authorization: Bearer {TOKEN}" in e["headers"] for e in log if e["url"].endswith("/check-runs")))
token_req = next(e for e in log if e["url"].endswith("/access_tokens"))
expect("report: App token limited to checks: write on this repository", json.loads(token_req["body"]) == {"repositories": ["CreateScript-Registry"], "permissions": {"checks": "write"}}, token_req["body"])
expect("report: App token is masked", f"::add-mask::{TOKEN}" in out)
jwt = next(h for h in token_req["headers"] if h.startswith("Authorization: Bearer ")).split(" ", 2)[2]
h, p, s = jwt.split(".")
pad = lambda x: x + "=" * (-len(x) % 4)
payload = json.loads(base64.urlsafe_b64decode(pad(p)))
expect("report: JWT header RS256", json.loads(base64.urlsafe_b64decode(pad(h))) == {"alg": "RS256", "typ": "JWT"})
expect("report: JWT iss = App ID, lifetime 10 minutes", payload["iss"] == "123456" and payload["exp"] - payload["iat"] == 600, payload)
sig = RUNS / "sig.bin"; sig.write_bytes(base64.urlsafe_b64decode(pad(s)))
signed = RUNS / "signed.txt"; signed.write_text(f"{h}.{p}")
v = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(key) + ".pub", "-signature", str(sig), str(signed)], capture_output=True, text=True)
expect("report: JWT signature verifies with the App's public key", v.returncode == 0, v.stdout + v.stderr)
expect("report: the private key file is removed", not [x for x in fake.rglob("tmp.*") if x.is_file()], list(fake.rglob("tmp.*")))
# P6-L1: revocation
rv = revocations(fake)
expect("report: the App token is revoked at the end (DELETE /installation/token with the App token)", len(rv) == 1 and f"Authorization: Bearer {TOKEN}" in rv[0]["headers"], rv)
expect("report: revocation comes after the check runs", [e["url"].rsplit("/", 1)[-1] for e in log][-1] == "token", [e["url"] for e in log])
expect("report: the token is never printed or written to a file", not token_leaked(fake, out, err))
for result in ("skipped", "cancelled", ""):
    fake = report_fake(); bash(report, report_env(fake, VALIDATE_RESULT=result, APPROVALS_RESULT="success"), fake)
    c = [json.loads(e["body"])["conclusion"] for e in curl_log(fake) if e["url"].endswith("/check-runs")]
    expect(f"report: job result '{result}' -> failure", c[:1] == ["failure"], c)
fake = report_fake(current="d" * 40); code, out, err = bash(report, report_env(fake), fake)
expect("report: outdated run reports nothing (no App call, nothing to revoke)", code == 0 and curl_log(fake) == [], curl_log(fake))
fake = report_fake(installed=False); code, out, err = bash(report, report_env(fake), fake)
expect("report: App not installed -> fails, no checks, no token to revoke", code != 0 and not any(e["url"].endswith("/check-runs") for e in curl_log(fake)) and not revocations(fake), err)
fake = report_fake(checks_ok=False); code, out, err = bash(report, report_env(fake), fake)
expect("report: check-run creation fails -> job fails", code != 0, err)
expect("report: ... and the token is still revoked (abnormal end)", len(revocations(fake)) == 1, curl_log(fake))
expect("report: ... and the token is not printed", not token_leaked(fake, out, err))
fake = report_fake(revoke_ok=False); code, out, err = bash(report, report_env(fake), fake)
expect("report: revocation fails -> the result stays success, a warning without secrets", code == 0 and "::warning::the App installation token could not be revoked" in out, out + err)
expect("report: ... and the token is not printed", not token_leaked(fake, out, err))
for name, over in (("APP_ID unset", {"APP_ID": ""}), ("APP_ID not a number", {"APP_ID": "1;rm"}), ("head SHA malformed", {"HEAD_SHA": "zz"}), ("head SHA short", {"HEAD_SHA": "c" * 39})):
    fake = report_fake(); code, out, err = bash(report, report_env(fake, **over), fake)
    expect(f"report: {name} -> fails before any App call", code != 0 and curl_log(fake) == [], err)
fake = report_fake(); (fake / "api").joinpath("".join(c if c.isalnum() else "_" for c in f"repos/{REPOSITORY}/pulls/7")).unlink()
code, out, err = bash(report, report_env(fake), fake)
expect("report: GitHub API error reading the head -> fails, no App call", code != 0 and curl_log(fake) == [], err)

# ---------- publish.yml ----------
publish = wf("publish.yml")
find = step(publish, "check", "pr")
compare = step(publish, "check", "Check that the merged tree is the approved tree, and the approvals")
record = step(publish, "check", "files")
release = step(publish, "release", "Publish the release as the registry's publishing GitHub App")

def fixture(target, extra=None):
    """An open registry (three maintainers) made from the template."""
    shutil.copytree(REPO / "registry-template", target, ignore=shutil.ignore_patterns("workflows", "github-settings"))
    (target / "maintainers.json").write_text(
        '{\n  "registryVersion": 1,\n  "lead": "dog7dog",\n  "minimumMaintainers": 3,\n  "requiredApprovals": 2,\n  "maintainers": [\n'
        '    {\n      "login": "dog7dog",\n      "id": 1\n    },\n    {\n      "login": "bea",\n      "id": 2\n    },\n    {\n      "login": "chen",\n      "id": 3\n    }\n  ]\n}\n')
    (target / ".github/CODEOWNERS").write_text("* @dog7dog @bea @chen\n")
    for name, content in (extra or {}).items():
        (target / name).write_text(content)

def git(cwd, *a):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false", *a], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()

def publication_repos(tamper=False, base_extra=None, base_symlink=False, unrelated_before=False):
    work = pathlib.Path(tempfile.mkdtemp(dir=RUNS))
    src = work / "src"; fixture(src, base_extra)
    if base_symlink: os.symlink("README.md", src / "link.md")
    git(src, "init", "-q", "-b", "main"); git(src, "add", "-A"); git(src, "commit", "-q", "-m", "base")
    base = git(src, "rev-parse", "HEAD")
    git(src, "checkout", "-q", "-b", "pr")
    (src / "signed/index.json").write_text('{"serial":2}'); (src / "signed/index.json.sig").write_text("sig")
    git(src, "add", "-A"); git(src, "commit", "-q", "-m", "publish 2")
    head = git(src, "rev-parse", "HEAD"); tree = git(src, "rev-parse", "HEAD^{tree}")
    if tamper:
        (src / "signed/index.json").write_text('{"serial":3}'); git(src, "add", "-A"); git(src, "commit", "-q", "-m", "x")
        tree = git(src, "rev-parse", "HEAD^{tree}")
    merged = git(src, "commit-tree", tree, "-p", base, "-m", "Squash publish 2")
    if unrelated_before:  # a commit that is not an ancestor of the published commit
        base = git(src, "commit-tree", git(src, "rev-parse", f"{base}^{{tree}}"), "-m", "unrelated")
    origin = work / "origin.git"
    git(work, "init", "-q", "--bare", str(origin))
    git(src, "push", "-q", str(origin), f"{merged}:refs/heads/main", f"{head}:refs/pull/7/head", f"{base}:refs/heads/unrelated")
    git(work, "clone", "-q", str(origin), "registry"); git(work / "registry", "checkout", "-q", merged)
    return work, base, head, merged

def publish_env(fake, work, extra):
    out = work / "github_output"; out.touch()
    return env_for(fake, {"GH_TOKEN": "t", "REPOSITORY": REPOSITORY, "GITHUB_OUTPUT": str(out), **extra}), out

def run_check(tamper=False, pulls=None, reviews=None, author=("alice", 1001), **repo):
    work, base, head, merged = publication_repos(tamper, **repo)
    fake = new_fake()
    api(fake, f"repos/{REPOSITORY}/commits/{merged}/pulls", pulls if pulls is not None else [{"number": 7, "merged_at": "2026-11-20T10:00:00Z", "base": {"ref": "main"}}])
    api(fake, f"repos/{REPOSITORY}/pulls/7", {"head": {"sha": head}, "user": {"login": author[0], "id": author[1]}})
    rv = reviews if reviews is not None else [[{"user": {"login": "bea", "id": 2}, "state": "APPROVED", "commit_id": head}, {"user": {"login": "chen", "id": 3}, "state": "APPROVED", "commit_id": head}]]
    api(fake, f"repos/{REPOSITORY}/pulls/7/reviews?per_page=100", rv)
    env, out = publish_env(fake, work, {"COMMIT": merged})
    code, o, e = bash(find, env, work)
    if code != 0: return code, o + e, work, base, head, merged
    outputs = dict(l.split("=", 1) for l in out.read_text().splitlines())
    env2, _ = publish_env(fake, work, {"BEFORE": base, "PR_NUMBER": outputs["number"], "PR_HEAD": outputs["head"], "PR_AUTHOR": outputs["author"], "PR_AUTHOR_ID": outputs["author_id"]})
    code, o2, e2 = bash(compare, env2, work)
    return code, o + e + o2 + e2, work, base, head, merged

code, log, *_ = run_check()
expect("publish/check: approved, same tree -> passes", code == 0 and "approved by bea, chen" in log, log)
code, log, *_ = run_check(tamper=True)
expect("publish/check: merged tree differs from the approved head -> fails", code != 0 and "is not the tree of the approved head commit" in log, log)
code, log, *_ = run_check(pulls=[])
expect("publish/check: no merged pull request -> fails", code != 0 and "(0 found)" in log, log)
code, log, *_ = run_check(pulls=[{"number": 7, "merged_at": "x", "base": {"ref": "main"}}, {"number": 8, "merged_at": "y", "base": {"ref": "main"}}])
expect("publish/check: two merged pull requests -> fails", code != 0 and "(2 found)" in log, log)
code, log, *_ = run_check(pulls=[{"number": 7, "merged_at": None, "base": {"ref": "main"}}])
expect("publish/check: pull request not merged -> fails", code != 0, log)
code, log, *_ = run_check(reviews=[[{"user": {"login": "bea", "id": 2}, "state": "APPROVED", "commit_id": "a" * 40}]])
expect("publish/check: approvals missing or on an older commit -> fails", code != 0 and "approvals needed" in log, log)
code, log, *_ = run_check(author=("bea", 2))
expect("publish/check: the author's own approval does not count -> fails", code != 0 and "bea is the author and cannot approve" in log, log)
marker = WORK / "pwned"
code, log, *_ = run_check(author=(f"x;touch {marker}", 9))
expect("publish/check: unexpected author login is refused", code != 0 and "unexpected author" in log and not marker.exists(), log)
code, log, *_ = run_check(pulls=[{"number": "7;id", "merged_at": "x", "base": {"ref": "main"}}])
expect("publish/check: unexpected pull request number is refused", code != 0, log)

# P6-L5: export-ignore in the base commit
attributes = {".gitattributes": "maintainers.json export-ignore\n.github/CODEOWNERS export-ignore\n"}
work, base, head, merged = publication_repos(base_extra=attributes)
archived = work / "archived"; archived.mkdir()
subprocess.run(f"git -C registry archive {base} | tar -x -C archived", shell=True, cwd=work, check=True)
expect("P6-L5 reproduced: git archive drops export-ignore files from the base", not (archived / "maintainers.json").exists() and not (archived / ".github/CODEOWNERS").exists())
code, log, work, base, head, merged = run_check(base_extra=attributes)
expect("P6-L5 fixed: the base is complete despite export-ignore, and the approvals pass", code == 0 and "approved by bea, chen" in log, log)
blob = git(work / "registry", "cat-file", "blob", f"{base}:maintainers.json")
expect("P6-L5 fixed: base files are the raw blobs", (work / "before/maintainers.json").read_text() == blob + "\n")
expect("P6-L5 fixed: no .git in the base", not (work / "before/.git").exists())
tracked = sorted(git(work / "registry", "ls-tree", "-r", "--name-only", "--full-tree", base).splitlines())
extracted = sorted(str(p.relative_to(work / "before")) for p in (work / "before").rglob("*") if p.is_file())
expect("P6-L5 fixed: the base has exactly the tracked files of the commit", tracked == extracted, (tracked, extracted))
# eol conversion changes the checkout of the published commit (not the raw base): it can only make the check fail
code, log, work2, *_ = run_check(base_extra={".gitattributes": "* text eol=crlf\n"})
expect("P6-L5: eol attributes never let the approvals pass more easily (the base stays raw; the result fails closed)", code != 0 and b"\r" not in (work2 / "before/maintainers.json").read_bytes(), log)
code, log, *_ = run_check(base_symlink=True)
expect("P6-L5: a symbolic link in the base commit is refused", code != 0 and "not a regular file (mode 120000)" in log, log)
code, log, *_ = run_check(unrelated_before=True)
expect("P6-L5: a base that is not an ancestor of the published commit is refused", code != 0 and "not an ancestor" in log, log)

# record the files, then the release job (P6-L3)
code, log, work, base, head, merged = run_check()
fake = new_fake(); env, out = publish_env(fake, work, {})
code, o, e = bash(record, env, work)
outputs = dict(l.split("=", 1) for l in out.read_text().splitlines())
expect("publish/check: records serial and SHA-256 of both files", code == 0 and outputs.get("serial") == "2" and len(outputs.get("index_sha256", "")) == 64, o + e)

PUB_TOKEN = "ghs_mockpublisher0987654321"
GRANTED = {"token": PUB_TOKEN, "expires_at": "x", "permissions": {"contents": "write", "metadata": "read"},
           "repository_selection": "selected", "repositories": [{"name": "CreateScript-Registry"}]}

def release_run(prepare=None, installed=True, granted=GRANTED, revoke_ok=True, extra_env=None, **flags):
    """The release job's step with the publishing App (W1). The step's env has no GH_TOKEN; extra_env can add one
    (to show that there is no fallback to it)."""
    fake = new_fake()
    if prepare: prepare(fake)
    if installed: curl_response(fake, "GET", f"https://api.github.com/repos/{REPOSITORY}/installation", {"id": 77})
    if granted is not None: curl_response(fake, "POST", "https://api.github.com/app/installations/77/access_tokens", granted)
    if revoke_ok: curl_response(fake, "DELETE", "https://api.github.com/installation/token", {})
    env = env_for(fake, {"APP_ID": "222", "APP_KEY": KEY, "FAKE_EXPECT_TOKEN": PUB_TOKEN, "REPOSITORY": REPOSITORY,
                         "SERIAL": outputs["serial"], "INDEX_SHA256": outputs["index_sha256"], "SIG_SHA256": outputs["sig_sha256"],
                         "GITHUB_SHA": merged, **(extra_env or {}), **flags})
    code, o, e = bash(release, env, work)
    state = json.loads((fake / "releases.json").read_text()) if (fake / "releases.json").exists() else []
    summary = (fake / "summary.md").read_text() if (fake / "summary.md").exists() else ""
    release_run.last = (fake, o, e)
    return code, o + e, state, gh_log(fake), summary

def gh_tokens(fake):
    p = fake / "gh-tokens.log"
    return p.read_text().split() if p.exists() else []

code, log, state, calls, summary = release_run()
creates = [c for c in calls if c[:2] == ["release", "create"]]
patches = [c for c in calls if "-X" in c and "PATCH" in c]
expect("publish/release: normal -> exit 0", code == 0, log)
expect("publish/release: created as a draft, not as latest", len(creates) == 1 and "--draft" in creates[0] and "--latest" not in creates[0] and creates[0][2] == "index-2", creates)
expect("publish/release: created with exactly the two files at the commit", creates and creates[0][3:5] == ["registry/signed/index.json", "registry/signed/index.json.sig"] and merged in creates[0], creates)
expect("publish/release: published (draft=false) and made latest only after the draft check", len(patches) == 1 and "draft=false" in patches[0] and "make_latest=true" in patches[0] and calls.index(patches[0]) > calls.index(creates[0]), calls)
expect("publish/release: the release ends published and immutable", len(state) == 1 and state[0]["draft"] is False and state[0]["immutable"] is True, state)
fake, o, e = release_run.last
# W1: the publishing App
expect("W1: every gh call uses the Publisher App token (never GITHUB_TOKEN)", gh_tokens(fake) and set(gh_tokens(fake)) == {"app"}, gh_tokens(fake))
token_req = next((x for x in curl_log(fake) if x["url"].endswith("/access_tokens")), None)
expect("W1: the token is requested for this repository and contents: write only", token_req and json.loads(token_req["body"]) == {"repositories": ["CreateScript-Registry"], "permissions": {"contents": "write"}}, token_req)
expect("W1: the Publisher App token is masked", f"::add-mask::{PUB_TOKEN}" in o)
expect("W1: the Publisher App token is never printed or written to a file", not token_leaked(fake, o, e, PUB_TOKEN))
pub_revocations = [x for x in curl_log(fake) if x["method"] == "DELETE" and x["url"] == "https://api.github.com/installation/token"]
expect("W1: the token is revoked at the end with the token itself", len(pub_revocations) == 1 and f"Authorization: Bearer {PUB_TOKEN}" in pub_revocations[0]["headers"] and curl_log(fake)[-1]["method"] == "DELETE", curl_log(fake))
expect("W1: the private key file is removed", not [x for x in fake.rglob("tmp.*") if x.is_file()])
# W1: fail-closed, no fallback
for name, kwargs in (("the App is not installed (authentication or lookup fails)", {"installed": False}),
                     ("no token is issued (missing permission or 401/422)", {"granted": None})):
    code, log, state, calls, summary = release_run(extra_env={"GH_TOKEN": "a-github-token"}, **kwargs)
    fake, o, e = release_run.last
    expect(f"W1: {name} -> stops before any gh call, no fallback to GITHUB_TOKEN", code != 0 and calls == [] and state == [] and "nothing was published" in log, log)
for name, granted in (("contents: read only", {**GRANTED, "permissions": {"contents": "read", "metadata": "read"}}),
                      ("an extra permission", {**GRANTED, "permissions": {"contents": "write", "metadata": "read", "issues": "write"}}),
                      ("no permissions in the response", {k: v for k, v in GRANTED.items() if k != "permissions"}),
                      ("all repositories", {**GRANTED, "repository_selection": "all"}),
                      ("another repository", {**GRANTED, "repositories": [{"name": "other"}]})):
    code, log, state, calls, summary = release_run(granted=granted)
    fake, o, e = release_run.last
    expect(f"W1: token with {name} -> stops before any gh call", code != 0 and calls == [] and "does not have exactly contents: write" in log, log)
    expect(f"W1: ... and that token is still revoked ({name})", len([x for x in curl_log(fake) if x["method"] == "DELETE"]) == 1)
code, log, state, calls, summary = release_run(APP_ID="")
fake, o, e = release_run.last
expect("W1: REGISTRY_PUBLISHER_APP_ID not set -> stops before any App call", code != 0 and curl_log(fake) == [] and calls == [], log)
code, log, state, calls, summary = release_run(revoke_ok=False)
expect("W1: revocation fails -> published anyway, warning without secrets", code == 0 and "::warning::the Publisher App installation token could not be revoked" in log and not token_leaked(release_run.last[0], release_run.last[1], release_run.last[2], PUB_TOKEN), log)
code, log, state, calls, summary = release_run(FAKE_DIGEST_OVERRIDE="1")
expect("publish/release: digest mismatch on the draft -> stops, stays a draft, never latest", code != 0 and state and state[0]["draft"] is True and not [c for c in calls if "PATCH" in c] and "left as a draft" in log, log)
expect("publish/release: ... and the failure is recorded in the job summary", "Publication of index-2 stopped" in summary, summary)
code, log, state, calls, summary = release_run(FAKE_EXTRA_ASSET="1")
expect("publish/release: an extra asset -> stops as a draft", code != 0 and state[0]["draft"] is True, log)
code, log, state, calls, summary = release_run(FAKE_DROP_ASSET="1")
expect("publish/release: a missing asset -> stops as a draft", code != 0 and state[0]["draft"] is True, log)
code, log, state, calls, summary = release_run(FAKE_NOT_IMMUTABLE="1")
expect("publish/release: published but not immutable -> the job fails and records it (no automatic deletion)", code != 0 and len(state) == 1 and "not report it as immutable" in log and "stopped" in summary, log)
def existing(draft):
    return lambda fake: (fake / "releases.json").write_text(json.dumps([{"id": 7, "tag_name": "index-2", "draft": draft, "immutable": not draft, "assets": []}]))
for draft in (False, True):
    code, log, state, calls, summary = release_run(prepare=existing(draft))
    expect(f"publish/release: re-run with an existing {'draft' if draft else 'published'} index-2 -> stops before creating anything", code != 0 and not [c for c in calls if c[:2] == ["release", "create"]] and "already exists" in log, log)
code, log, state, calls, summary = release_run(FAKE_RELEASE_FAIL="1")
expect("publish/release: gh release create fails -> job fails, nothing published", code != 0 and not [c for c in calls if "PATCH" in c], log)
(work / "registry/signed/index.json").write_text('{"serial":2,"x":1}')
code, log, state, calls, summary = release_run()
expect("publish/release: a file that changed after the check -> fails before any release call", code != 0 and not [c for c in calls if c[0] == "release"], log)
git(work / "registry", "checkout", "-q", "--", "signed/index.json")
for name, over in (("serial empty", {"SERIAL": ""}), ("serial with ;", {"SERIAL": "2;id"}), ("serial negative", {"SERIAL": "-1"}), ("SHA-256 malformed", {"INDEX_SHA256": "zz"})):
    code, log, state, calls, summary = release_run(**over)
    expect(f"publish/release: {name} refused before any release call", code != 0 and not [c for c in calls if c[0] == "release"], log)
# W3: the tag rulesets of the repository, checked before publishing
gate = step(publish, "check", "Check the repository's tag rulesets (fails when they cannot be verified)")
CHECKS_ID, PUBLISHER_ID = 111, 222
A = {"name": "index tags: creation", "target": "tag", "enforcement": "active",
     "bypass_actors": [{"actor_id": PUBLISHER_ID, "actor_type": "Integration", "bypass_mode": "always"}],
     "conditions": {"ref_name": {"include": ["refs/tags/index-*"], "exclude": []}}, "rules": [{"type": "creation"}]}
B = {"name": "index tags: update and deletion", "target": "tag", "enforcement": "active", "bypass_actors": [],
     "conditions": {"ref_name": {"include": ["refs/tags/index-*"], "exclude": []}}, "rules": [{"type": "deletion"}, {"type": "update"}, {"type": "non_fast_forward"}]}

def gate_run(live_a=A, live_b=B, list_ok=True, checks_id=str(CHECKS_ID), publisher_id=str(PUBLISHER_ID)):
    work = pathlib.Path(tempfile.mkdtemp(dir=RUNS))
    fixture(work / "registry")
    (work / "registry/github-settings").mkdir()
    (work / "registry/github-settings/index-tags-creation-ruleset.json").write_text(json.dumps(A, indent=2))
    (work / "registry/github-settings/index-tags-protection-ruleset.json").write_text(json.dumps(B, indent=2))
    fake = new_fake()
    live = [x for x in ((1, live_a), (2, live_b)) if x[1] is not None]
    if list_ok:
        api(fake, f"repos/{REPOSITORY}/rulesets?per_page=100", [[{"id": i, "name": r["name"], "target": "tag"} for i, r in live] + [{"id": 3, "name": "main", "target": "branch"}]])
    for i, r in live:
        api(fake, f"repos/{REPOSITORY}/rulesets/{i}", {"id": i, "source_type": "Repository", **r})
    code, o, e = bash(gate, env_for(fake, {"GH_TOKEN": "t", "REPOSITORY": REPOSITORY, "CHECKS_APP_ID": checks_id, "PUBLISHER_APP_ID": publisher_id}), work)
    return code, o + e

code, log = gate_run()
expect("W3: live tag rulesets equal to github-settings with visible bypass actors -> passes", code == 0 and "the tag rulesets match" in log, log)
code, log = gate_run(live_a={k: v for k, v in A.items() if k != "bypass_actors"})
expect("W3: bypass_actors not returned (token cannot read them) -> fails closed", code != 0 and "bypass_actors is not visible" in log, log)
code, log = gate_run(live_b={k: v for k, v in B.items() if k != "bypass_actors"})
expect("W3: B's bypass_actors not returned -> fails closed", code != 0 and "cannot be verified that nobody bypasses it" in log, log)
code, log = gate_run(live_b={**B, "bypass_actors": [{"actor_id": PUBLISHER_ID, "actor_type": "Integration", "bypass_mode": "always"}]})
expect("W3: B with a bypass actor -> fails", code != 0 and "bypass_actors must be empty" in log, log)
code, log = gate_run(live_a={**A, "bypass_actors": [{"actor_id": CHECKS_ID, "actor_type": "Integration", "bypass_mode": "always"}]})
expect("W3: A bypassed by the Checks App -> fails", code != 0 and "not the Registry Publisher App" in log, log)
code, log = gate_run(live_a={**A, "bypass_actors": A["bypass_actors"] + [{"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}]})
expect("W3: A with an extra bypass actor (e.g. a repository role) -> fails", code != 0 and "exactly one actor" in log, log)
code, log = gate_run(live_a={**A, "enforcement": "disabled"})
expect("W3: A not active -> fails", code != 0 and "enforcement must be active" in log, log)
code, log = gate_run(live_b={**B, "rules": [{"type": "deletion"}]})
expect("W3: B without update -> fails", code != 0 and "are missing" in log, log)
code, log = gate_run(live_b=None)
expect("W3: B missing on the repository -> fails", code != 0 and "exactly one ruleset named" in log, log)
code, log = gate_run(publisher_id="333")
expect("W3: the App ID variable differs from github-settings -> fails", code != 0 and "not the Registry Publisher App (333)" in log, log)
code, log = gate_run(publisher_id="")
expect("W3: the App ID variable is not set -> fails", code != 0, log)
code, log = gate_run(list_ok=False)
expect("W3: the rulesets cannot be read (API error) -> fails", code != 0, log)

verify_failed = step(publish, "verify", "Record a failed verification")
fake = new_fake(); code, o, e = bash(verify_failed, env_for(fake, {"SERIAL": "2"}), fake)
expect("publish/verify: a failed verification is recorded in the summary and as an error", code == 0 and "published, but the verification failed" in (fake / "summary.md").read_text() and "::error::index-2" in o, o + e)
expect("publish/verify: the failure record runs only on failure", wf("publish.yml")["jobs"]["verify"]["steps"][-1].get("if") == "${{ failure() }}")

passed = sum(1 for _, ok in results if ok)
print(f"\nHARNESS: {len(results)} checks, {passed} passed, {len(results) - passed} failed")
key.unlink(); pathlib.Path(str(key) + ".pub").unlink()
sys.exit(0 if passed == len(results) else 1)
