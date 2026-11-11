"""Static checks for GitHub Actions workflows (S8.3 Phase 6, kept as a test since Phase 7; run by WorkflowHarnessTest).
Reads the files themselves (not diffs). Checks: YAML syntax with duplicate-key detection, `on`, job ids, step ids,
run/uses exclusivity, needs, if-expressions, permissions, environment, expression contexts, steps.* and needs.*
references, job outputs, bash -n on every run block (with expressions replaced by placeholders)."""
import re, sys, subprocess, tempfile, os, json
import yaml

class DupLoader(yaml.SafeLoader):
    pass

def construct_mapping(loader, node, deep=False):
    keys = set()
    for k, _ in node.value:
        key = loader.construct_object(k, deep=deep)
        if key in keys:
            raise yaml.constructor.ConstructorError(None, None, f"duplicate key {key!r}", k.start_mark)
        keys.add(key)
    return yaml.SafeLoader.construct_mapping(loader, node, deep)

DupLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping)

CONTEXTS = {"github", "env", "vars", "secrets", "needs", "steps", "inputs", "job", "runner", "matrix", "strategy", "jobs"}
FUNCTIONS = {"always", "success", "failure", "cancelled", "contains", "startsWith", "endsWith", "format", "join", "toJSON", "fromJSON", "hashFiles"}
PERM_SCOPES = {"actions", "attestations", "checks", "contents", "deployments", "id-token", "issues", "discussions", "packages", "pages", "pull-requests", "repository-projects", "security-events", "statuses", "models"}
EXPR = re.compile(r"\$\{\{(.*?)\}\}", re.S)

def check(path):
    problems, notes = [], []
    text = open(path).read()
    try:
        doc = yaml.load(text, Loader=DupLoader)
    except yaml.YAMLError as e:
        return [f"YAML: {e}"], notes
    # `on` is read as True by YAML 1.1 loaders; GitHub reads the key "on"
    on = doc.get("on", doc.get(True))
    if not on:
        problems.append("no `on`")
    notes.append(f"on: {json.dumps(on)}")
    top_env = set((doc.get("env") or {}).keys())
    jobs = doc.get("jobs") or {}
    if not jobs:
        problems.append("no jobs")
    def perms(p, where):
        if p in ({}, "read-all", "write-all"):
            return
        if not isinstance(p, dict):
            problems.append(f"{where}: permissions {p!r}")
            return
        for k, v in p.items():
            if k not in PERM_SCOPES: problems.append(f"{where}: unknown permission scope {k}")
            if v not in ("read", "write", "none"): problems.append(f"{where}: permission {k}: {v}")
    if "permissions" in doc:
        perms(doc["permissions"], "workflow")
    else:
        problems.append("workflow has no top-level permissions")
    def expressions(value, where, allowed_steps, needs, job_env):
        for m in EXPR.finditer(value if isinstance(value, str) else ""):
            expr = m.group(1).strip()
            if expr.count("(") != expr.count(")"): problems.append(f"{where}: unbalanced parentheses in {expr}")
            for ref in re.findall(r"(?<![\w.'])([A-Za-z_][\w-]*)(?=[.(\[])", re.sub(r"'[^']*'", "''", expr)):
                if ref not in CONTEXTS and ref not in FUNCTIONS:
                    problems.append(f"{where}: unknown context or function {ref} in {expr}")
            for s in re.findall(r"\bsteps\.([\w-]+)", expr):
                if s not in allowed_steps: problems.append(f"{where}: steps.{s} is not an earlier step id")
            for n in re.findall(r"\bneeds\.([\w-]+)", expr):
                if n not in needs: problems.append(f"{where}: needs.{n} is not in needs")
            for e in re.findall(r"\benv\.([\w]+)", expr):
                if e not in top_env and e not in job_env: problems.append(f"{where}: env.{e} is not defined")
            if "secrets." in expr: notes.append(f"{where}: uses {expr}")
    for job_id, job in jobs.items():
        if not re.fullmatch(r"[A-Za-z_][\w-]*", str(job_id)): problems.append(f"invalid job id {job_id}")
        w = f"job {job_id}"
        if "runs-on" not in job: problems.append(f"{w}: no runs-on")
        needs = job.get("needs", [])
        needs = [needs] if isinstance(needs, str) else needs
        for n in needs:
            if n not in jobs: problems.append(f"{w}: needs unknown job {n}")
        if "permissions" in job: perms(job["permissions"], w)
        else: notes.append(f"{w}: no job permissions (inherits the top level {doc.get('permissions')!r})")
        env = job.get("environment")
        if env is not None and not (isinstance(env, str) or (isinstance(env, dict) and "name" in env)):
            problems.append(f"{w}: environment {env!r}")
        if "if" in job: expressions("${{ " + str(job["if"]).replace("${{", "").replace("}}", "") + " }}", f"{w} if", set(), needs, set())
        job_env = set((job.get("env") or {}).keys())
        ids, seen = [], set()
        for i, step in enumerate(job.get("steps") or []):
            sw = f"{w} step {i + 1} ({step.get('name') or step.get('uses')})"
            if ("run" in step) == ("uses" in step): problems.append(f"{sw}: needs exactly one of run/uses")
            sid = step.get("id")
            if sid is not None:
                if sid in seen: problems.append(f"{sw}: duplicate step id {sid}")
                seen.add(sid)
            step_env = set((step.get("env") or {}).keys())
            for key, value in step.items():
                for v in (value.values() if isinstance(value, dict) else [value]):
                    expressions(v, f"{sw} {key}", set(ids), needs, job_env | step_env)
            if "run" in step:
                if "${{" in step["run"]: problems.append(f"{sw}: an expression inside run")
                script = EXPR.sub("PLACEHOLDER", step["run"])
                with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as f:
                    f.write(script)
                r = subprocess.run(["bash", "-n", f.name], capture_output=True, text=True)
                os.unlink(f.name)
                if r.returncode != 0: problems.append(f"{sw}: bash -n: {r.stderr.strip()}")
                # variables used but not defined anywhere visible (env, earlier assignment in the script, or runner variables)
                defined = top_env | job_env | step_env | set(re.findall(r"\b([A-Za-z_]\w*)=", script)) | set(re.findall(r"for (\w+) in", script)) | {"GITHUB_OUTPUT", "GITHUB_SHA", "GITHUB_ENV", "HOME", "PATH"}
                unquoted = re.sub(r"'[^']*'", "''", script)  # jq programs and literals in single quotes are not shell variables
                defined |= {"RUNNER_TEMP", "GITHUB_WORKSPACE", "PWD", "GITHUB_REPOSITORY", "RUNNER_OS", "GITHUB_STEP_SUMMARY"}
                defined |= set(re.findall(r"\bread\s+(?:-\w+\s+(?:''\s+)?)*(\w+)", script))
                for var in set(re.findall(r"\$\{?([A-Za-z_]\w*)", unquoted)):
                    if var not in defined and not var.isdigit(): problems.append(f"{sw}: ${var} is not defined in env or the script")
            if sid: ids.append(sid)
        for k, v in (job.get("outputs") or {}).items():
            expressions(v, f"{w} output {k}", set(ids), needs, job_env)
    return problems, notes

if __name__ == "__main__":
    total = 0
    for p in sys.argv[1:]:
        problems, notes = check(p)
        total += len(problems)
        print(f"== {p}: {len(problems)} problem(s)")
        for x in problems: print("  PROBLEM", x)
        for x in notes: print("  note", x)
    sys.exit(1 if total else 0)
