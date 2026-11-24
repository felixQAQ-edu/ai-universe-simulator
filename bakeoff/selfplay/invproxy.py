# 勘察用:inventory 条目字面在每回合叙事 / 所选行动文本里出现的回合(机械代理,不判对错)。只读。
# 出处:docs/memory-ledger-survey.md 附录 A.9。入库(ADR-046 D7)只改了套件目录取法:argv[1] = 套件目录。其余逐字不动(物品表照原样四局四件)。
import json,glob,os,sys
B=os.path.expanduser(sys.argv[1])
slots={a['saveId']:s['slot'] for s in json.load(open(B+'/summary.json'))['slots'] for a in s['attempts']}
items={'xiu-1':['聚气丹','半瓶聚气丹'],'xiu-2':['灵石','半块下品灵石'],'rong-1':['定神丹','半瓶定神丹'],'xiu-long':['回气散','半瓶回气散']}
for f in sorted(glob.glob(B+'/traces/*.trace.jsonl')):
    s=slots[os.path.basename(f).split('.')[0]]
    if s not in items: continue
    rs=[json.loads(l) for l in open(f)]
    for it in items[s]:
        nar=[r['turnBefore']+1 for r in rs if it in ((r['parsed'] or {}).get('narrative') or r.get('streamedNarrative') or '')]
        sel=[r['turnBefore']+1 for r in rs if any(a.get('id')==r['actionId'] and it in a.get('text','') for a in r['pre']['currentActions'])]
        print(f"{s}\t「{it}」\t叙事含:{len(nar)} {nar}\n\t\t所选行动文本含:{len(sel)} {sel}")
