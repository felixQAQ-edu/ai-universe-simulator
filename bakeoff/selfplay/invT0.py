# 出处:docs/memory-ledger-survey.md 附录 A.7。入库(ADR-046 D7)只改了套件目录取法:argv[1] = 套件目录;
# argv[2](可选)= T0 所在的原套件目录 —— 续玩套件(--resume-t0)的 summary 里 t0 是原套件下的相对路径。其余逐字不动。只读。
import json, os, sys
B = os.path.expanduser(sys.argv[1])
T0B = os.path.expanduser(sys.argv[2]) if len(sys.argv) > 2 else B
s = json.load(open(os.path.join(B, 'summary.json'), encoding='utf-8'))
for e in s['slots']:
    a = next((x for x in e['attempts'] if x['attempt'] == e['kept']), None)
    if not a or not a.get('t0'):
        continue
    t0 = json.load(open(os.path.join(T0B, a['t0']), encoding='utf-8'))
    fin = json.load(open(os.path.join(B, 'store', a['saveId'] + '.json'), encoding='utf-8'))
    i0 = t0['world']['character'].get('inventory')
    i1 = fin['world']['character'].get('inventory')
    print(e['slot'], 'T0 inventory =', json.dumps(i0, ensure_ascii=False), '| 终局存档 turn', fin['state']['turn'], '相同' if i0 == i1 else '不同', '| T0 顶层键', sorted(t0.keys()), '| T0 state 键', sorted(t0['state'].keys()))
