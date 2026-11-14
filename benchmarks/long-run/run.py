#!/usr/bin/env python3
"""run.py -- the long-run quality benchmark: 16 tasks in two resumed Pironi sessions, each checked.

    python3 benchmarks/long-run/run.py --model qwen38 --label base-38-1 \\
        [--base-url http://127.0.0.1:8000/v1] [--api-key-file FILE] [--jar target/pironi-*.jar]

Session 1 (steps 1-11) is the long test used since 25.10.2026. Session 2 (steps 12-16) targets the
history handling: a resumed session whose third task runs 14+ turns, a task of 22+ turns, and a closing
question whose answer was only ever in an early tool result.

Every run gets a fresh home of its own (-Duser.home): the SOUL, USER and CLAUDE files are the fixed ones
in context/, and no memory, skill or finding from the user's ~/.pironi leaks in or out.

Pironi talks to the server through a small recording proxy, which is how prompt, cached and generated
tokens, the longest prompt, truncations and compressions are counted per step without changing Pironi.
Read (prefill) and write (decode) seconds come from the server's /metrics when it is vLLM.

Output: <out>/<label>.json and a table on stdout. Compare runs with compare.py.
"""
import argparse
import glob
import hashlib
import http.server
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
COMPRESSION_SYSTEM = "Summarize conversation state faithfully and concisely."
LEDGER_MARKERS = ("Established so far (do not re-derive):", "Established here in earlier sessions")

# ---------------------------------------------------------------- recording proxy

class Recorder:
    """What went over the wire, per step."""

    def __init__(self):
        self.step = 0
        self.rows = []
        self.lock = threading.Lock()

    def add(self, row):
        with self.lock:
            row["step"] = self.step
            self.rows.append(row)


def ledger_chars(text):
    at = min((text.find(m) for m in LEDGER_MARKERS if m in text), default=-1)
    return 0 if at < 0 else len(text) - at


def make_proxy(upstream, rec):
    class Proxy(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def _forward(self, body):
            req = urllib.request.Request(upstream + self.path.removeprefix("/v1"), data=body,
                                         method=self.command)
            for k, v in self.headers.items():
                if k.lower() not in ("host", "content-length", "connection", "accept-encoding"):
                    req.add_header(k, v)
            try:
                with urllib.request.urlopen(req, timeout=1800) as r:
                    return r.status, r.headers.get("Content-Type", "application/json"), r.read()
            except urllib.error.HTTPError as e:
                return e.code, e.headers.get("Content-Type", "application/json"), e.read()

        def _reply(self, status, ctype, data):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            self._reply(*self._forward(None))

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            t0 = time.time()
            status, ctype, data = self._forward(body)
            if self.path.endswith("/chat/completions"):
                try:
                    self._record(json.loads(body), json.loads(data) if status == 200 else None,
                                 status, time.time() - t0)
                except Exception as e:  # recording must never break the run
                    rec.add({"record_error": repr(e)})
            self._reply(status, ctype, data)

        def _record(self, req, resp, status, seconds):
            msgs = req.get("messages", [])
            last = msgs[-1] if msgs else {}
            row = {"status": status, "seconds": round(seconds, 2), "messages": len(msgs),
                   "chars": sum(len(m.get("content") or "") for m in msgs),
                   "system_sha": hashlib.sha1((msgs[0].get("content") or "").encode()).hexdigest()[:10] if msgs else "",
                   "compression": bool(msgs) and msgs[0].get("content") == COMPRESSION_SYSTEM,
                   "last_role": last.get("role"), "last_chars": len(last.get("content") or ""),
                   "last_ledger_chars": ledger_chars(last.get("content") or "") if last.get("role") == "user" else 0}
            if resp:
                u = resp.get("usage") or {}
                det = u.get("prompt_tokens_details") or {}
                msg = (resp.get("choices") or [{}])[0].get("message") or {}
                row.update(prompt=u.get("prompt_tokens", 0), cached=det.get("cached_tokens") or 0,
                           completion=u.get("completion_tokens", 0),
                           reasoning_chars=len(msg.get("reasoning_content") or msg.get("reasoning") or ""),
                           content_chars=len(msg.get("content") or ""))
            rec.add(row)

    return Proxy


# ---------------------------------------------------------------- server metrics

def metrics(base_url):
    root = base_url.removesuffix("/v1")
    try:
        txt = urllib.request.urlopen(root + "/metrics", timeout=10).read().decode()
    except Exception:
        return None
    m = {}
    for line in txt.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        name = re.split(r"[{ ]", line, maxsplit=1)[0]
        try:
            m[name] = m.get(name, 0.0) + float(line.rsplit(" ", 1)[1])
        except ValueError:
            pass
    return m


def resets():
    """Engine resets so far, when the kernel log is readable without a password; else None."""
    r = subprocess.run(["sudo", "-n", "dmesg"], capture_output=True, text=True)
    return len(re.findall(r"Engine reset|CAT error", r.stdout)) if r.returncode == 0 else None


# ---------------------------------------------------------------- checks

class Checks:
    """Each check returns (ok, note). A is answers.json, ws the workspace."""

    def __init__(self, ws, answers):
        self.ws, self.A = ws, answers

    def f(self, p):
        return os.path.join(self.ws, p)

    def tests(self, *args):
        r = subprocess.run([sys.executable, *args], cwd=self.ws, capture_output=True, text=True, timeout=120)
        return r.returncode == 0, (r.stdout + r.stderr)[-300:]

    def explain(self, out):
        return len(out) > 200, f"{len(out)} chars"

    def orion(self, out):
        try:
            got = sorted(l.strip().split("/")[-1] for l in open(self.f("orion_files.txt")) if l.strip())
        except OSError:
            return False, "no orion_files.txt"
        return got == self.A["orion"], f"found {got}"

    def move(self, out):
        ls = lambda d: sorted(x for x in os.listdir(self.f(d)) if not x.startswith(".")) if os.path.isdir(self.f(d)) else []
        rep, orion = ls("data/reports"), ls("projects/orion")
        left = [x for x in ls("inbox") if x.endswith(".csv")]
        ok = rep == sorted(self.A["csvs"].values()) and orion == self.A["orion"] and not left \
            and os.path.exists(self.f("inbox/note_19.txt"))
        return ok, f"reports={len(rep)} orion={len(orion)} csv-left={len(left)}"

    def fix(self, out):
        ok, _ = self.tests("-m", "unittest", "discover", "-t", ".", "-s", "app", "-q")
        same = hashlib.sha256(open(self.f("app/test_inventory.py"), "rb").read()).hexdigest() == self.A["test_inv_sha"]
        return ok and same, ("tests pass" if ok else "tests fail") + ("" if same else ", TEST WAS EDITED")

    HIDDEN = '''
import sys; sys.path.insert(0, ".")
from app.inventory import restock_report
items=[{"name":"a","qty":1,"price":1},{"name":"b","qty":9,"price":1},{"name":"c","qty":4,"price":1},{"name":"d","qty":6,"price":1}]
r=restock_report(items,6)
assert r==[{"name":"a","need":5},{"name":"c","need":2}], r
assert restock_report([],3)==[]
assert restock_report(items,1)==[]
'''

    def restock(self, out):
        hidden, _ = self.tests("-c", self.HIDDEN)
        own, _ = self.tests("-m", "unittest", "discover", "-t", ".", "-s", "app", "-q")
        has = os.path.exists(self.f("app/test_restock.py"))
        return hidden and own and has, f"hidden {'ok' if hidden else 'FAIL'}, theirs {'ok' if own else 'FAIL'}, test_restock.py {'ok' if has else 'missing'}"

    def _summary(self):
        return json.load(open(self.f("log_summary.json")))

    def codes(self, out):
        try:
            j = self._summary()
        except Exception as e:
            return False, f"json: {e}"
        got = {k: j.get(k) for k in self.A["codes"]}
        return got == self.A["codes"], str(got)

    def peak(self, out):
        try:
            j = self._summary()
            ok = int(j.get("peak_hour")) == self.A["peak_hour"]
        except Exception:
            ok, j = False, {}
        return ok, f"peak_hour={j.get('peak_hour')} (expected {self.A['peak_hour']})"

    def rename(self, out):
        left = subprocess.run(["grep", "-rn", "calc_total", "--include=*.py", "."], cwd=self.ws,
                              capture_output=True, text=True).stdout
        ok, _ = self.tests("-m", "unittest", "discover", "-t", ".", "-s", "app", "-q")
        has = "compute_total" in open(self.f("app/inventory.py")).read()
        return ok and has and not left.strip(), f"leftovers {len(left.splitlines())}, tests {'ok' if ok else 'FAIL'}"

    def cli(self, out):
        num = str(self.A["amount_total"])
        r = subprocess.run([sys.executable, "app/cli.py"], cwd=self.ws, capture_output=True, text=True, timeout=60) \
            if os.path.exists(self.f("app/cli.py")) else None
        script = r is not None and num in r.stdout.replace(",", "").replace(" ", "")
        said = num in out.replace(",", "").replace(" ", "")
        return script and said, f"script {'ok' if script else 'FAIL'}, answer {'ok' if said else 'FAIL'}"

    def secret(self, out):
        ok = self.A["secret"] in out or "СИНЯ ЧАПЛА 42" in out
        return ok, "remembers" if ok else "forgot"

    def git(self, out):
        r = subprocess.run(["git", "log", "--oneline"], cwd=self.ws, capture_output=True, text=True).stdout
        return "pironi long test" in r, r.strip()[:60] or "no commit"

    # --- session 2
    def stock(self, out):
        rows = str(self.A["stock_rows"]) in out
        top = self.A["stock_top"] in out
        return rows and top, f"rows {'ok' if rows else 'FAIL'}, top {'ok' if top else 'FAIL'}"

    def plan(self, out):
        try:
            lines = [l for l in open(self.f("notes/plan.md")) if re.match(r"\s*([-*]|\d+[.)])\s+\S", l)]
        except OSError:
            return False, "no notes/plan.md"
        return len(lines) >= 3, f"{len(lines)} points"

    def trail_a(self, out):
        try:
            got = [l.strip() for l in open(self.f("trail/a_answer.txt")) if l.strip()]
        except OSError:
            return False, "no trail/a_answer.txt"
        want = ["+".join(reversed(self.A["trail_a"])), str(len(self.A["trail_a"]))]
        return got[:2] == want, f"line1 {'ok' if got[:1] == want[:1] else 'FAIL'}, line2 {got[1] if len(got) > 1 else '-'} (expected {want[1]})"

    def trail_b(self, out):
        try:
            got = open(self.f("trail/b_answer.txt")).read().strip()
        except OSError:
            return False, "no trail/b_answer.txt"
        frags = [x.strip() for x in got.split(",")]
        file_ok = frags == self.A["trail_b"]
        said = self.A["trail_b"][-1] in out
        return file_ok and said, f"file {'ok' if file_ok else 'FAIL'} ({len(frags)}/{len(self.A['trail_b'])}), last {'ok' if said else 'FAIL'}"

    def recall(self, out):
        ok = self.A["warehouse"] in out.upper().replace(" ", "")
        return ok, "remembers" if ok else "forgot"


STEPS = [
    # (session, name, task, check, max_turns, before)  before: a workspace change made by the harness first
    (1, "question", "Обясни накратко на български разликата между процес и нишка в операционната система, с пример кога ползваме едното и кога другото. И запомни кодовата дума СИНЯ-ЧАПЛА-42 — ще те питам за нея по-късно. В тази стъпка не пипай файлове.", "explain", 30, None),
    (1, "search", "Намери всички файлове в inbox/, които съдържат точно текста PROJECT-ORION (главни букви). Запиши имената им (само името на файла, по едно на ред, сортирани) в orion_files.txt в корена на работната папка.", "orion", 30, None),
    (1, "move", "Подреди inbox/: премести всички .csv файлове в data/reports/ и ги преименувай във формат report-YYYY-MM-DD.csv (датата е в името на файла, сега е DD.MM.YYYY). Премести .txt файловете, които съдържат PROJECT-ORION, в projects/orion/ със същите имена. Всичко останало остава в inbox/.", "move", 30, None),
    (1, "bug", "В app/ има малък проект. Тестовете в app/test_inventory.py падат (пускат се от корена с: python3 -m unittest discover -t . -s app). Намери и оправи грешката в app/inventory.py. Не променяй тестовете.", "fix", 30, None),
    (1, "new code", "Добави в app/inventory.py функция restock_report(items, threshold), която връща списък от речници {\"name\": име, \"need\": threshold - qty} само за позициите с qty < threshold, сортиран по need намаляващо. Напиши app/test_restock.py с поне 4 unittest теста за нея и се увери, че всички тестове минават.", "restock", 30, None),
    (1, "log", "Прочети logs/server.log. Преброй колко пъти се среща всеки код на грешка в редовете с ERROR (кодовете са като E1042). Запиши резултата в log_summary.json като JSON обект {\"код\": брой, ...}.", "codes", 30, None),
    (1, "log 2", "В кой час (00–23) от logs/server.log има най-много ERROR редове? Добави го в log_summary.json под ключ \"peak_hour\" като цяло число, без да губиш досегашните ключове. Кажи ми и числото.", "peak", 30, None),
    (1, "refactor", "Преименувай функцията calc_total на compute_total навсякъде в проекта — в кода и в тестовете. След това всички тестове трябва да минават.", "rename", 30, None),
    (1, "script", "Напиши app/cli.py, който чете всички CSV файлове от data/reports/ и отпечатва общата сума на колоната amount (само числото). Пусни го и ми кажи какво число излиза.", "cli", 30, None),
    (1, "memory", "Каква беше кодовата дума, която ти казах в самото начало? Отговори само с нея.", "secret", 30, None),
    (1, "final", "Инициализирай git хранилище в работната папка (ако няма), добави всички файлове и направи commit със съобщение \"pironi long test\". После ми дай кратък отчет (до 10 реда) какво направи през цялата сесия.", "git", 30, None),
    (2, "stock", "Прочети stock/inventory.csv. Колко реда с данни има (без заглавния ред и без коментара) и кой артикул има най-голямо количество?", "stock", 30, None),
    (2, "plan", "Напиши notes/plan.md: план в 3 до 5 точки (списък с тирета) как да се провери инвентаризацията на склад. Само файла, без да пипаш нищо друго.", "plan", 30, "delete stock/inventory.csv"),
    (2, "trail A", "В trail/a/ има верига от файлове. Започни от trail/a/start.txt: всеки файл има ред „фрагмент: …“ и ред „следващ: …“ с името на следващия файл в същата папка (последният казва „следващ: КРАЙ“). Следвай веригата, като четеш файловете един по един. Накрая запиши в trail/a_answer.txt два реда: на първия — всички фрагменти в ОБРАТЕН ред, слепени със знака + (без интервали); на втория — колко файла има във веригата (без start.txt).", "trail_a", 30, None),
    (2, "trail B", "В trail/b/ има още една верига със същите правила: започни от trail/b/start.txt и следвай реда „следващ: …“ до КРАЙ, като четеш по един файл. Запиши всички фрагменти в ПРАВ ред, разделени със запетая, на един ред в trail/b_answer.txt. Накрая ми кажи кой е последният фрагмент.", "trail_b", 40, None),
    (2, "recall", "В началото на тази сесия прочете stock/inventory.csv. Файлът вече го няма. Как се казваше складът в първия му ред? Отговори само с името на склада, по памет — не търси файла.", "recall", 30, None),
]


# ---------------------------------------------------------------- the run

def trace_counts(trace):
    """turns, protocol repairs, model errors, compressions so far, from the run's trace."""
    c = {"turns": 0, "protocol": 0, "model_errors": 0, "compressed": 0}
    try:
        for line in open(trace, encoding="utf-8"):
            j = json.loads(line)
            t, k = j.get("type"), j.get("kind")
            if t == "model_response":
                c["turns"] += 1
            elif t == "model_error":
                c["model_errors"] += 1
            elif t == "harness_note" and k == "protocol_repair":
                c["protocol"] += 1
            elif t == "harness_note" and k == "compressed":
                c["compressed"] += 1
    except OSError:
        pass
    return c


def step_stats(rows):
    """Aggregates the proxy rows of one step."""
    rows = [r for r in rows if "prompt" in r]
    agent = [r for r in rows if not r["compression"]]
    p = sum(r["prompt"] for r in rows)
    cached = sum(r["cached"] for r in rows)
    rc = sum(r["reasoning_chars"] for r in rows)
    cc = sum(r["content_chars"] for r in rows)
    # a request with fewer messages than the one before it, with no compression call in between, followed a truncation
    trunc, prev, compressed = 0, None, False
    for r in rows:
        if r["compression"]:
            compressed = True
            continue
        if prev is not None and r["messages"] < prev and not compressed:
            trunc += 1
        prev, compressed = r["messages"], False
    tool_msgs = [r for r in agent if r["last_role"] == "user"]
    return {"requests": len(rows), "prompt_tokens": p, "cached_tokens": cached,
            "cache_hit": round(cached / p, 3) if p else None,
            "gen_tokens": sum(r["completion"] for r in rows),
            "max_prompt": max((r["prompt"] for r in rows), default=0),
            "max_messages": max((r["messages"] for r in agent), default=0),
            "thinking_share": round(rc / (rc + cc), 3) if rc + cc else None,
            "truncations": trunc, "compression_calls": sum(r["compression"] for r in rows),
            "system_changes": sum(1 for a, b in zip(agent, agent[1:]) if a["system_sha"] != b["system_sha"]),
            "last_msg_chars": sum(r["last_chars"] for r in tool_msgs),
            "ledger_chars": sum(r["last_ledger_chars"] for r in tool_msgs)}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--model", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--base-url", default="http://127.0.0.1:8000/v1")
    ap.add_argument("--api-key-file", default=os.path.expanduser("~/egpu-setup/.vllm-api-key"))
    ap.add_argument("--jar", default=None, help="default: the newest target/pironi-*.jar")
    ap.add_argument("--java", default=os.path.join(os.environ["JAVA_HOME"], "bin", "java")
                    if os.environ.get("JAVA_HOME") else "java")
    ap.add_argument("--context", default="131072")
    ap.add_argument("--out", default=os.path.join(HERE, "results"))
    ap.add_argument("--work", default=os.path.join(HERE, "work"), help="where run homes are made")
    ap.add_argument("--steps", default="", help="e.g. 12-16: run only these (a session still starts at its first step)")
    ap.add_argument("--extra", default="", help="extra Pironi arguments, space separated")
    a = ap.parse_args()

    jar = a.jar or max(glob.glob(os.path.join(REPO, "target", "pironi-*.jar")), key=os.path.getmtime)
    key = open(a.api_key_file).read().strip() if os.path.exists(a.api_key_file) else "local"
    os.makedirs(a.out, exist_ok=True)
    home = os.path.join(a.work, a.label)
    shutil.rmtree(home, ignore_errors=True)
    ws = os.path.join(home, "ws")
    os.makedirs(os.path.join(home, ".pironi"))
    shutil.copy(os.path.join(REPO, "SOUL.example.md"), os.path.join(home, ".pironi", "SOUL.md"))
    shutil.copy(os.path.join(REPO, "USER.example.md"), os.path.join(home, ".pironi", "USER.md"))
    shutil.copy(os.path.join(HERE, "context", "CLAUDE.md"), os.path.join(home, "CLAUDE.md"))
    ans = os.path.join(home, "answers.json")
    subprocess.run([sys.executable, "-I", os.path.join(HERE, "fixture.py"), ws, ans], check=True)
    answers = json.load(open(ans))
    checks = Checks(ws, answers)
    # The trace stays out of the workspace: kept there it once answered the memory question by being
    # read back. The read tools stay in the workspace too. The shell keeps the user scope Pironi is used
    # with; the workspace scope has no run_command at all, and the log, script and git steps need it.
    trace = os.path.join(a.work, f"{a.label}.trace.jsonl")
    if os.path.exists(trace):
        os.remove(trace)

    rec = Recorder()
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), make_proxy(a.base_url.rstrip("/"), rec))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    proxy = f"http://127.0.0.1:{srv.server_address[1]}/v1"

    wanted = set()
    for part in filter(None, a.steps.split(",")):
        lo, _, hi = part.partition("-")
        wanted.update(range(int(lo), int(hi or lo) + 1))

    env = dict(os.environ, PIRONI_BENCH_KEY=key)
    res = {"label": a.label, "model": a.model, "jar": jar, "jar_sha": hashlib.sha256(open(jar, "rb").read()).hexdigest()[:12],
           "started": time.strftime("%Y-%m-%dT%H:%M:%S"), "steps": []}
    r_start = resets()
    t_run = time.time()
    session = None
    print(f"{'#':>2} {'step':9} ok {'s':>6} {'turns':>5} {'req':>3} {'prompt':>7} {'cache':>5} {'gen':>5} "
          f"{'maxP':>6} {'read':>5} {'write':>5} {'think':>5} {'trunc':>5} {'comp':>4} {'ledger':>6}  note", flush=True)
    for i, (sess, name, task, check, max_turns, before) in enumerate(STEPS, 1):
        if wanted and i not in wanted:
            continue
        if before and before.startswith("delete "):
            target = os.path.join(ws, before.split(" ", 1)[1])
            if os.path.exists(target):
                os.remove(target)
        task_file = os.path.join(home, "task.txt")
        open(task_file, "w").write(task)
        cmd = [a.java, f"-Duser.home={home}", "-jar", jar, "--provider", "openai-compatible", "--base-url", proxy,
               "--api-key-env", "PIRONI_BENCH_KEY", "--model", a.model, "--context", a.context,
               "--max-output-tokens", "8192", "--max-turns", str(max_turns), "--activity", "auto",
               "--shell-scope", "user", "--read-scope", "workspace", "--trace", trace,
               "--status", "never", "--no-interactive", "--task-file", task_file, "--workspace", ws] + a.extra.split() + (["--continue"] if session == sess else [])
        session = sess
        rec.step = i
        m0, tc0, t0 = metrics(a.base_url), trace_counts(trace), time.time()
        hang = False
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=1800, env=env)
            out, rc = p.stdout + p.stderr, p.returncode
        except subprocess.TimeoutExpired as e:
            out, rc, hang = (e.stdout or b"").decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or ""), -9, True
        wall = time.time() - t0
        m1, tc1 = metrics(a.base_url), trace_counts(trace)
        open(os.path.join(home, f"step{i:02d}.out"), "w").write(out)
        try:
            ok, note = getattr(checks, check)(out)
        except Exception as e:
            ok, note = False, f"check failed: {e!r}"
        st = {"step": i, "session": sess, "name": name, "ok": ok, "note": note, "wall": round(wall, 1), "rc": rc, "hang": hang,
              "turns": tc1["turns"] - tc0["turns"], "protocol_errors": tc1["protocol"] - tc0["protocol"] + tc1["model_errors"] - tc0["model_errors"],
              "compressions": tc1["compressed"] - tc0["compressed"]}
        st.update(step_stats([r for r in rec.rows if r.get("step") == i]))
        d = (lambda k: (m1.get(k, 0) - m0.get(k, 0))) if m0 and m1 else (lambda k: None)
        st["read_s"] = round(d("vllm:request_prefill_time_seconds_sum"), 1) if m0 and m1 else None
        st["write_s"] = round(d("vllm:request_decode_time_seconds_sum"), 1) if m0 and m1 else None
        res["steps"].append(st)
        print(f"{i:2d} {name:9} {'✓' if ok else '✗'}  {wall:6.1f} {st['turns']:5d} {st['requests']:3d} {st['prompt_tokens']:7d} "
              f"{st['cache_hit'] if st['cache_hit'] is not None else '-':>5} {st['gen_tokens']:5d} {st['max_prompt']:6d} "
              f"{st['read_s'] if st['read_s'] is not None else '-':>5} {st['write_s'] if st['write_s'] is not None else '-':>5} "
              f"{st['thinking_share'] if st['thinking_share'] is not None else '-':>5} {st['truncations']:5d} {st['compressions']:4d} "
              f"{st['ledger_chars']:6d}  {note}", flush=True)
    s = res["steps"]
    tot = lambda k: sum(x[k] or 0 for x in s)
    r_end = resets()
    res["summary"] = {
        "passed": sum(x["ok"] for x in s), "of": len(s), "wall": round(time.time() - t_run, 1),
        "turns": tot("turns"), "requests": tot("requests"), "prompt_tokens": tot("prompt_tokens"),
        "cached_tokens": tot("cached_tokens"), "cache_hit": round(tot("cached_tokens") / tot("prompt_tokens"), 3) if tot("prompt_tokens") else None,
        "gen_tokens": tot("gen_tokens"), "max_prompt": max((x["max_prompt"] for x in s), default=0),
        "read_s": round(tot("read_s"), 1) if s and s[0]["read_s"] is not None else None,
        "write_s": round(tot("write_s"), 1) if s and s[0]["write_s"] is not None else None,
        "thinking_share": round(statistics.fmean([x["thinking_share"] for x in s if x["thinking_share"] is not None]), 3)
        if any(x["thinking_share"] is not None for x in s) else None,
        "truncations": tot("truncations"), "compressions": tot("compressions"), "protocol_errors": tot("protocol_errors"),
        "ledger_share": round(tot("ledger_chars") / tot("last_msg_chars"), 3) if tot("last_msg_chars") else None,
        "hangs": tot("hang"), "resets": (r_end - r_start) if r_start is not None and r_end is not None else None}
    json.dump(res, open(os.path.join(a.out, f"{a.label}.json"), "w"), ensure_ascii=False, indent=1)
    json.dump(rec.rows, open(os.path.join(home, "requests.json"), "w"), indent=0)
    srv.shutdown()
    S = res["summary"]
    print(f"== {a.label}: {S['passed']}/{S['of']} passed, {S['wall']} s, {S['turns']} turns, {S['requests']} requests, "
          f"prompt {S['prompt_tokens']} (cache {S['cache_hit']}), gen {S['gen_tokens']}, max prompt {S['max_prompt']}, "
          f"read {S['read_s']} s, write {S['write_s']} s, trunc {S['truncations']}, comp {S['compressions']}, "
          f"protocol {S['protocol_errors']}, ledger {S['ledger_share']}, resets {S['resets']}", flush=True)


if __name__ == "__main__":
    main()
