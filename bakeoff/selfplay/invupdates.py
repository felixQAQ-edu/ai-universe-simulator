# ADR-046 D7 副读数:每局 inventory 实际被更新的回合数(机械计数,不判对错)。只读。
# 用法:python3 -I bakeoff/selfplay/invupdates.py <套件目录>
# 口径(事先登记,ADR-046 D7):
#   带键 = 轨迹 parsed.stateUpdate 里有 inventory 这个键(parsed 是服务端改写之前、模型给的那份;降级回合 parsed 为 null,不计);
#   变化 = 带键,且给出的清单取前 8 条(引擎上限 MAX_INVENTORY)后与本回合 pre 里的 world.character.inventory 不同;
#   修复 = 该回合 repairErrors 非空(落账用的是修复尾巴,带键与否也按修复尾巴算)。
# 只输出槽位名与回合号,不输出 saveId。
import json, os, sys

MAX_INVENTORY = 8

B = os.path.expanduser(sys.argv[1])
s = json.load(open(os.path.join(B, 'summary.json'), encoding='utf-8'))
tot = {'turns': 0, 'degraded': 0, 'keyed': 0, 'changed': 0, 'repaired': 0, 'repairedKeyed': 0}
for e in s['slots']:
    a = next((x for x in e['attempts'] if x['attempt'] == e['kept']), None)
    if not a or not a.get('saveId'):
        print(e['slot'], '无保留局')
        continue
    p = os.path.join(B, 'traces', a['saveId'] + '.trace.jsonl')
    if not os.path.exists(p):
        print(e['slot'], '无轨迹')
        continue
    rs = sorted((json.loads(l) for l in open(p, encoding='utf-8')), key=lambda r: r['turnBefore'])
    c = {k: 0 for k in tot}
    keyed, changed = [], []
    for r in rs:
        t = r['turnBefore'] + 1
        c['turns'] += 1
        parsed = r.get('parsed')
        repaired = bool(r.get('repairErrors'))
        c['repaired'] += repaired
        if parsed is None:
            c['degraded'] += 1
            continue
        su = parsed.get('stateUpdate') or {}
        if 'inventory' not in su:
            continue
        c['keyed'] += 1
        c['repairedKeyed'] += repaired
        keyed.append(t)
        before = ((r.get('pre') or {}).get('world') or {}).get('character', {}).get('inventory')
        given = su['inventory'][:MAX_INVENTORY] if isinstance(su['inventory'], list) else su['inventory']
        if given != before:
            c['changed'] += 1
            changed.append(t)
    for k in tot:
        tot[k] += c[k]
    print(f"{e['slot']}\t回合 {c['turns']}\t降级 {c['degraded']}\t修复 {c['repaired']}\t带键 {c['keyed']}"
          f"\t其中清单变化 {c['changed']}\t修复回合里带键 {c['repairedKeyed']}")
    print(f"\t带键回合:{keyed}\n\t变化回合:{changed}")
print(f"ALL\t回合 {tot['turns']}\t降级 {tot['degraded']}\t修复 {tot['repaired']}\t带键 {tot['keyed']}"
      f"\t其中清单变化 {tot['changed']}\t修复回合里带键 {tot['repairedKeyed']}")
