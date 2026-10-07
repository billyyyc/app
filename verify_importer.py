# 离线复刻安卓端的「导入映射 + 匹配 + 按期展示」逻辑，验证新格式数据能否正确跑通。
import zipfile, re, xml.etree.ElementTree as ET

SAMPLE = "示例学员数据(新格式).xlsx"

# ---------- 复刻 XlsxReader ----------
def read_xlsx(path):
    z = zipfile.ZipFile(path)
    NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
    ss = ET.fromstring(z.read('xl/sharedStrings.xml'))
    shared = [''.join(t.text or '' for t in si.iter(NS+'t')) for si in ss.iter(NS+'si')]
    rels = ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))
    rid2target = {r.get('Id'): r.get('Target') for r in rels}
    wb = ET.fromstring(z.read('xl/workbook.xml'))
    sheets = []
    for s in wb.iter(NS+'sheet'):
        rid = s.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')
        target = rid2target.get(rid)
        path = target if target.startswith('/') else 'xl/'+target
        sh = ET.fromstring(z.read(path))
        rows = []
        maxc = -1
        for row in sh.iter(NS+'row'):
            cells = {}
            for c in row.iter(NS+'c'):
                ref = c.get('r'); t = c.get('t'); v = c.find(NS+'v')
                col = col_index(ref)
                if col > maxc: maxc = col
                if v is not None:
                    val = shared[int(v.text)] if t=='s' else (v.text or '')
                    if val != '': cells[col] = val
            lst = [cells.get(i) for i in range(maxc+1)]
            rows.append(lst)
        sheets.append((s.get('name'), rows))
    return sheets

def col_index(ref):
    m = re.match(r'([A-Z]+)\d+', ref)
    if not m: return 0
    col = 0
    for ch in m.group(1): col = col*26 + (ord(ch)-64)
    return col-1

# ---------- 复刻 DataImporter 别名映射 ----------
ALIASES = {
    "name": ["姓名","学生姓名","名字"],
    "grade": ["年级"],
    "school": ["学校"],
    "phone": ["手机","手机号码","电话"],
    "birth": ["出生年月","出生日期"],
    "classSession": ["班次","班级"],
    "teacher": ["老师","教师"],
}
def clean_term(t):
    t = t.replace("考勤信息","").strip()
    y = re.search(r'\d{4}', t)
    year = y.group(0) if y else ""
    for s in ["寒假","春季","暑期","秋季"]:
        if s in t: return f"{year}{s}" if year else t
    return t
def map_headers(header):
    m = {}
    for i,v in enumerate(header):
        if v: m[re.sub(r'\s+','',v)] = i
    return m
def resolve(hmap, field):
    for a in ALIASES[field]:
        if a in hmap: return hmap[a]
    return None

def import_students(path):
    sheets = read_xlsx(path)
    students = []
    for name, rows in sheets:
        header = rows[0]
        hmap = map_headers(header)
        name_i = resolve(hmap, "name")
        if name_i is None:
            print(f"  [跳过] {name}: 缺姓名列"); continue
        g=resolve(hmap,"grade"); sc=resolve(hmap,"school"); ph=resolve(hmap,"phone")
        b=resolve(hmap,"birth"); cl=resolve(hmap,"classSession"); te=resolve(hmap,"teacher")
        for r in rows[1:]:
            nm = r[name_i] if name_i < len(r) else None
            if not nm or not nm.strip(): continue
            def get(i): return r[i].strip() if i is not None and i < len(r) and r[i] else None
            students.append(dict(term=name, termClean=clean_term(name), name=nm.strip(),
                grade=get(g), school=get(sc), phone=get(ph), birth=get(b),
                classSession=get(cl), teacher=get(te)))
    return students

# ---------- 复刻 Matcher ----------
SUFFIXES = ["妈妈","爸爸","爸","姐姐","姐","哥哥","哥","叔叔","阿姨","爷爷","奶奶","姥爷","姥姥","先生","女士","老师","舅舅","姑姑","婶婶","伯伯"]
def norm(s): return re.sub(r'\s+','', s).strip()
def strip_suffix(s):
    for x in SUFFIXES:
        if s.endswith(x): return s[:-len(x)]
    return s
def lev(a,b):
    a=a.lower(); b=b.lower()
    dp=[[0]*(len(b)+1) for _ in range(len(a)+1)]
    for i in range(len(a)+1): dp[i][0]=i
    for j in range(len(b)+1): dp[0][j]=j
    for i in range(1,len(a)+1):
        for j in range(1,len(b)+1):
            dp[i][j]=min(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+(0 if a[i-1]==b[j-1] else 1))
    return dp[len(a)][len(b)]
def match(query, allstu):
    q=norm(query)
    if not q: return None
    exact=[s for s in allstu if norm(s["name"])==q]
    if exact: return (exact, False)
    qs=strip_suffix(q)
    if qs!=q:
        s=[x for x in allstu if norm(x["name"])==qs or strip_suffix(norm(x["name"]))==qs]
        if s: return (s, False)
    if len(q)>=1:
        c=[x for x in allstu if q in norm(x["name"]) or norm(x["name"]) in q]
        if c: return (c, True)
    th = 1 if len(q)<=2 else 2
    l=[x for x in allstu if lev(norm(x["name"]),q)<=th]
    if l: return (l, True)
    return None

# ---------- 复刻 TermUtils + 展示 ----------
SEASON={"寒假":1,"春季":2,"暑期":3,"秋季":4}
def sort_key(t):
    y=re.search(r'\d{4}',t); year=y.group(0) if y else "0"
    order=9
    for s,i in SEASON.items():
        if s in t: order=i; break
    return f"{year}-{order}"
def age(birth):
    if not birth: return None
    p = birth.replace("\n","").split(".") if "." in birth else birth.replace("\n","").split("-")
    try:
        y=int(p[0]); m=int(p[1]) if len(p)>1 else 1; d=int(p[2]) if len(p)>2 else 1
    except: return None
    now=2026  # 近似（演示用）
    age=now-y
    return f"{age}岁"

def show(query, allstu):
    res=match(query, allstu)
    if not res:
        print(f"  ❌ 「{query}」未找到")
        return
    rows, approx = res
    byname={}
    for r in rows: byname.setdefault(r["name"],[]).append(r)
    print(f"  查询「{query}」 approximate={approx} 命中姓名数={len(byname)}")
    for name, recs in byname.items():
        recs.sort(key=lambda r: sort_key(r["term"]))
        a=next((age(r["birth"]) for r in recs if r["birth"]), None)
        print(f"  👨‍🎓 {name}  年龄={a}  就读={len(recs)}期")
        print(f"     {'期数':<6}{'学期':<10}{'年级':<6}{'学校':<12}{'手机':<14}{'班次':<10}{'老师'}")
        for i,r in enumerate(recs):
            print(f"     第{i+1}期  {r['termClean']:<8} {str(r['grade']):<6} {str(r['school']):<10} {str(r['phone']):<12} {str(r['classSession']):<8} {str(r['teacher'])}")

# ---------- 跑一遍 ----------
stu = import_students(SAMPLE)
print(f"导入记录数: {len(stu)}")
terms = sorted({s['term'] for s in stu}, key=sort_key)
print(f"学期({len(terms)}): {[clean_term(t) for t in terms]}")
print("\n[测试用例]")
for q in ["曾子淳", "陈思漫", "王小宝", "陈思漫妈妈", "王", "林", "小小宝", "张三"]:
    show(q, stu)
