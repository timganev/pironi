"""fixture.py <workspace> <answers.json> -- the starting workspace of the long-run benchmark.

Deterministic (fixed seeds), so every run and every build starts from the same files. The expected
answers go to answers.json OUTSIDE the workspace; the model must not see them.

Part 1 (steps 1-11) is byte-for-byte the fixture of the original long test (seed 42), so results stay
comparable with the runs measured before the benchmark moved into the repository. Part 2 (steps 12-16)
adds what part 1 does not reach: a resumed session whose current task runs long, a task of 20+ turns,
and a question answered only by an early tool result.
"""
import hashlib
import json
import os
import random
import sys

root = sys.argv[1]
ans_path = sys.argv[2]
rnd = random.Random(42)
os.makedirs(f"{root}/inbox")
os.makedirs(f"{root}/app")
os.makedirs(f"{root}/logs")

# --- inbox: scattered notes and CSV reports ---
orion = []
topics = ["бюджет", "среща", "доставка", "клиент", "ремонт", "наем", "отпуск", "договор"]
for i in range(1, 19):
    name = f"note_{i:02d}.txt"
    lines = [f"Бележка {i}: {rnd.choice(topics)}"] + [f"ред {j}: " + " ".join(rnd.choice(topics) for _ in range(6)) for j in range(rnd.randint(4, 12))]
    if i in (3, 7, 8, 12, 16):
        lines.insert(rnd.randint(1, len(lines)), "Свързано с PROJECT-ORION, фаза 2.")
        orion.append(name)
    open(f"{root}/inbox/{name}", "w").write("\n".join(lines) + "\n")
# trap: mentions orion in lower case, which does not count
open(f"{root}/inbox/note_19.txt", "w").write("Бележка 19: project orion е друго нещо, не го бъркай.\n")
dates = ["2026-07-14", "2026-08-02", "2026-08-30", "2026-09-11", "2026-10-03"]
total = 0
csvs = {}
for d in dates:
    y, m, dd = d.split("-")
    name = f"Report {dd}.{m}.{y}.csv"
    rows = ["id,item,amount"]
    for k in range(rnd.randint(5, 9)):
        a = rnd.randint(10, 990)
        total += a
        rows.append(f"{k+1},{rnd.choice(['болт','гайка','кабел','лампа','ключ'])},{a}")
    open(f"{root}/inbox/{name}", "w").write("\n".join(rows) + "\n")
    csvs[name] = f"report-{d}.csv"
for n in ("img_001.png", "img_002.png"):
    open(f"{root}/inbox/{n}", "wb").write(bytes(rnd.getrandbits(8) for _ in range(256)))

# --- app: a small project with a bug ---
open(f"{root}/app/__init__.py", "w").write("")
open(f"{root}/app/inventory.py", "w").write('''"""Склад: позиции са речници {"name": str, "qty": int, "price": float}."""


def calc_total(items, discount=0.0):
    """Обща стойност на склада; discount е дял (0.1 = 10% отстъпка)."""
    total = 0
    for it in items:
        total += it["qty"] * it["price"]
    return round(total * discount, 2)


def low_stock(items, limit=5):
    """Имената на позициите с количество под limit."""
    return [it["name"] for it in items if it["qty"] < limit]
''')
test_inv = '''import unittest
from app.inventory import calc_total, low_stock

ITEMS = [{"name": "болт", "qty": 10, "price": 0.5},
         {"name": "кабел", "qty": 2, "price": 12.0},
         {"name": "лампа", "qty": 5, "price": 7.25}]


class TestInventory(unittest.TestCase):
    def test_total_no_discount(self):
        self.assertEqual(calc_total(ITEMS), 65.25)

    def test_total_discount(self):
        self.assertEqual(calc_total(ITEMS, 0.2), 52.2)

    def test_low_stock(self):
        self.assertEqual(low_stock(ITEMS), ["кабел"])


if __name__ == "__main__":
    unittest.main()
'''
open(f"{root}/app/test_inventory.py", "w").write(test_inv)

# --- a large log ---
codes = {"E1042": 0, "E2207": 0, "E3311": 0, "E4090": 0, "E5150": 0}
weights = [9, 5, 3, 2, 1]
per_hour = {}
out = []
for n in range(2600):
    h = rnd.choices(range(24), weights=[1]*14 + [4] + [1]*9)[0]
    mm, ss = rnd.randint(0, 59), rnd.randint(0, 59)
    ts = f"2026-10-24 {h:02d}:{mm:02d}:{ss:02d}"
    if rnd.random() < 0.12:
        c = rnd.choices(list(codes), weights=weights)[0]
        codes[c] += 1
        per_hour[h] = per_hour.get(h, 0) + 1
        out.append((ts, f"{ts} ERROR {c} worker-{rnd.randint(1,8)} request failed: upstream timeout after {rnd.randint(100,9000)}ms"))
    elif rnd.random() < 0.1:
        out.append((ts, f"{ts} WARN slow query {rnd.randint(200,5000)}ms on table t{rnd.randint(1,40)}"))
    else:
        out.append((ts, f"{ts} INFO GET /api/v1/items/{rnd.randint(1,99999)} 200 {rnd.randint(1,300)}ms"))
out.sort()
open(f"{root}/logs/server.log", "w").write("\n".join(l for _, l in out) + "\n")
peak = max(per_hour, key=per_hour.get)

answers = {"orion": sorted(orion), "csvs": csvs, "amount_total": total, "codes": codes,
           "peak_hour": peak, "secret": "СИНЯ-ЧАПЛА-42",
           "test_inv_sha": hashlib.sha256(test_inv.encode()).hexdigest()}

# ===== Part 2: its own seed, so part 1 stays identical =====
r2 = random.Random(1114)
words = ["мост", "река", "фенер", "котва", "кула", "пясък", "вятър", "ключ", "сокол", "дъб", "камък", "огън",
         "лодка", "звезда", "мед", "сол", "игла", "рог", "ябълка", "облак", "лист", "въже", "чаша", "перо"]
filler = ["склад", "доставка", "отчет", "клиент", "договор", "срок", "смяна", "проверка", "партида", "етикет"]

# step 12 reads this once; the runner deletes it before step 13, and step 16 asks about its first line
os.makedirs(f"{root}/stock")
warehouse = "ВЕГА-31"
items = []
for k in range(17):
    items.append((f"артикул-{k+1:02d}", r2.randint(3, 480)))
top = max(items, key=lambda x: x[1])
with open(f"{root}/stock/inventory.csv", "w") as fh:
    fh.write(f"# склад: {warehouse}, инвентаризация 2026-11-02\n")
    fh.write("item,qty\n")
    for name, q in items:
        fh.write(f"{name},{q}\n")


def chain(folder, length, decoys):
    """A trail of files, each naming the next; only reading them in order reveals it.

    Each file is padded to ~1.5 KB, so following the trail grows the history the way real tool output does."""
    os.makedirs(f"{root}/{folder}")
    names = [f"n_{r2.getrandbits(24):06x}.txt" for _ in range(length + decoys)]
    trail, spare = names[:length], names[length:]
    frags = r2.sample(words, length)
    for i, name in enumerate(trail):
        nxt = trail[i + 1] if i + 1 < length else "КРАЙ"
        body = [" ".join(r2.choice(filler) for _ in range(12)) for _ in range(14)]
        body.insert(r2.randint(2, 12), f"фрагмент: {frags[i]}")
        body.insert(r2.randint(2, 14), f"следващ: {nxt}")
        open(f"{root}/{folder}/{name}", "w").write("\n".join(body) + "\n")
    for name in spare:   # decoys: same shape, never reached from start.txt
        body = [" ".join(r2.choice(filler) for _ in range(12)) for _ in range(14)]
        body.insert(5, f"фрагмент: {r2.choice(words)}")
        body.insert(9, f"следващ: {r2.choice(spare)}")
        open(f"{root}/{folder}/{name}", "w").write("\n".join(body) + "\n")
    open(f"{root}/{folder}/start.txt", "w").write(f"Веригата започва тук.\nследващ: {trail[0]}\n")
    return frags


frags_a = chain("trail/a", 14, 6)
frags_b = chain("trail/b", 22, 6)
answers.update({"warehouse": warehouse, "stock_rows": len(items), "stock_top": top[0],
                "trail_a": frags_a, "trail_b": frags_b})
json.dump(answers, open(ans_path, "w"), ensure_ascii=False, indent=1)
