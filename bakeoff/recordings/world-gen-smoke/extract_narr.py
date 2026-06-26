import json,sys,re
f=sys.argv[1]; want=sys.argv[2] if len(sys.argv)>2 else None
cur=None; ev=None; buf=[]; out={}
for line in open(f,encoding="utf-8"):
    line=line.rstrip("\n")
    m=re.match(r"===== REQUEST turn=(\d+)",line)
    if m:
        if cur is not None: out[cur]="".join(buf)
        cur=m.group(1); buf=[]; continue
    if line.startswith("event:"): ev=line[6:].strip()
    elif line.startswith("data:") and ev=="narrative":
        try: buf.append(json.loads(line[5:].strip()).get("text",""))
        except: pass
if cur is not None: out[cur]="".join(buf)
for t,n in out.items():
    if want and t!=want: continue
    print(f"\n----- turn {t} narrative ({len(n)} chars) -----\n{n}")
