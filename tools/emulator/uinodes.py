import re,sys
s=open(sys.argv[1],encoding='utf8').read()
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?checkable="(\w+)"[^>]*?bounds="([^"]*)"',s):
    t,c,b=m.groups()
    if (t and (len(sys.argv)<3 or sys.argv[2] in t)) or c=='true': print(repr(t[:30]),c,b)
