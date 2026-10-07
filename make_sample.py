# 生成一份「新格式」示例考勤数据（与真实考勤记录.xlsx 同结构），用于安卓 App 测试导入。
import zipfile, os, datetime

OUT = os.path.join(os.path.dirname(__file__), "示例学员数据(新格式).xlsx")

# 两个学期，列：序号/姓名/年级/学校/手机/出生年月/班次/老师
header = ["序号", "姓名", "年级", "学校", "手机", "出生\n年月", "班次", "老师"]

sheets = {
    "2026暑期考勤信息": [
        (1, "曾子淳", "9", "龙湖实验中学", "13322779966", "2012.5.9", "一三五晚A", "徐臻"),
        (2, "陈思漫", "9", "澄海中学", "13715972355", "2012.1.26", "一三五晚A", "徐臻"),
        (3, "陈思语", "高三", "金山中学", "15913924065", "2008.1", "一三五晚A", "徐臻"),
        (4, "王小宝", "5", "实验三小", "13531202246", "2016.8.1", "一三五晚A", "徐臻"),
    ],
    "2025秋季考勤信息": [
        (1, "曾子淳", "8", "龙湖实验中学", "13322779966", "2012.5.9", "二四六早B", "李娜"),
        (2, "陈思漫", "8", "澄海中学", "13715972355", "2012.1.26", "二四六早B", "李娜"),
        (3, "林晓彤", "高二", "金山中学", "13800001111", "2009.3", "二四六早B", "李娜"),
    ],
}

def col_letter(i):
    s = ""
    i += 1
    while i:
        i, r = divmod(i - 1, 26)
        s = chr(65 + r) + s
    return s

# 构建共享字符串与 sheet xml
shared = []
shared_idx = {}
def sidx(text):
    if text not in shared_idx:
        shared_idx[text] = len(shared)
        shared.append(text)
    return shared_idx[text]

def sheet_xml(rows):
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>']
    out.append('<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">')
    out.append('<sheetData>')
    for r, row in enumerate(rows, start=1):
        out.append(f'<row r="{r}">')
        for c, val in enumerate(row):
            ref = f"{col_letter(c)}{r}"
            if isinstance(val, str) and val in shared_idx:
                out.append(f'<c r="{ref}" t="s"><v>{sidx(val)}</v></c>')
            else:
                out.append(f'<c r="{ref}"><v>{val}</v></c>')
        out.append('</row>')
    out.append('</sheetData></worksheet>')
    return "".join(out)

# 第一行表头也作为共享字符串
rows_all = {}
for name, data in sheets.items():
    rows_all[name] = [header] + [list(map(str, d)) for d in data]

sheet_xmls = {n: sheet_xml(rows) for n, rows in rows_all.items()}

# 共享字符串表（按插入顺序）
ss_xml = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="%d" uniqueCount="%d">' % (len(shared), len(shared))]
for t in shared:
    ss_xml.append(f'<si><t xml:space="preserve">{t}</t></si>')
ss_xml.append('</sst>')
ss_xml = "".join(ss_xml)

# workbook + rels
wb = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>']
for i, name in enumerate(sheets.keys(), start=1):
    wb.append(f'<sheet name="{name}" sheetId="{i}" r:id="rId{i}"/>')
wb.append('</sheets></workbook>')
wb = "".join(wb)

rels = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">']
for i in range(1, len(sheets) + 1):
    rels.append(f'<Relationship Id="rId{i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{i}.xml"/>')
rid_ss = len(sheets) + 1
rels.append(f'<Relationship Id="rId{rid_ss}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>')
rels.append('</Relationships>')
rels = "".join(rels)

ct = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      '<Default Extension="xml" ContentType="application/xml"/>'
      '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      f'<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
      + "".join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for i in range(1, len(sheets)+1))
      + '</Types>')

root_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
             '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
             '</Relationships>')

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", ct)
    z.writestr("_rels/.rels", root_rels)
    z.writestr("xl/workbook.xml", wb)
    z.writestr("xl/_rels/workbook.xml.rels", rels)
    z.writestr("xl/sharedStrings.xml", ss_xml)
    for i in range(1, len(sheets) + 1):
        z.writestr(f"xl/worksheets/sheet{i}.xml", sheet_xmls[list(sheets.keys())[i-1]])

print("written:", OUT)
