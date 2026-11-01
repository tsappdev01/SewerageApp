"""Builds the CR #47155 technical document and the one-page user guide as Word files.

    python3 docs/cr-47155/src/build_docs.py

Sources: technical.md (## / ### headings, - bullets, 1. steps, | tables |, **bold**, `code`, !DIAGRAM) and
the user guide below. Screens in img/ are from the "Android screenshots" workflow (demo data).
"""
import os
import re

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.dirname(HERE)
LOGO = os.path.join(HERE, "logo.png")
NAVY = RGBColor(0x1F, 0x3A, 0x5F)
DATE = "1 November 2026"
F = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def diagram(path):
    W, H = 1540, 600
    im = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(im)
    fb, fr = ImageFont.truetype(FB, 26), ImageFont.truetype(F, 20)
    navy, grey, tint = (31, 58, 95), (110, 110, 110), (232, 238, 246)
    bw, bh, y = 340, 160, 50
    xs = [20, 405, 790, 1175]
    boxes = [("Phone app", ["Android and iOS", "DIP Field Service", "offline queue"]),
             ("zApps gateway", ["Azure App Service", "fixed route list", "rate limits, audit"]),
             ("mApps API", ["IIS on UATWEB01", "rules, amounts, VAT", "invoices, report"]),
             ("PropertyManagement-", ["System, schema mr", "readings, billing rows", "settings, logs"])]
    for i, (x, (t, ls)) in enumerate(zip(xs, boxes)):
        d.rounded_rectangle([x, y, x + bw, y + bh], 14, fill=tint if i == 3 else "white", outline=navy, width=3)
        d.text((x + 18, y + 18), t, font=fb, fill=navy)
        for j, l in enumerate(ls):
            d.text((x + 18, y + 62 + j * 30), l, font=fr, fill=(40, 40, 40))

    def arrow(x1, y1, x2, y2):
        d.line([x1, y1, x2, y2], fill=grey, width=3)
        if y1 == y2:
            d.polygon([(x2, y2), (x2 - 14, y2 - 8), (x2 - 14, y2 + 8)], fill=grey)
        else:
            d.polygon([(x2, y2), (x2 - 8, y2 - 14), (x2 + 8, y2 - 14)], fill=grey)

    cy = y + bh // 2
    for i in range(3):
        arrow(xs[i] + bw, cy, xs[i + 1], cy)
    d.text((xs[0] + bw + 6, cy - 34), "HTTPS", font=fr, fill=grey)
    d.text((xs[1] + bw + 6, cy - 34), "HTTPS", font=fr, fill=grey)
    d.text((xs[2] + bw + 14, cy - 34), "SQL", font=fr, fill=grey)
    # PMS dbo below the database box
    px, py = 790, 330
    arrow(xs[3] + bw // 2, y + bh, xs[3] + bw // 2, py)
    d.rounded_rectangle([px, py, 1515, py + 200], 14, fill=(245, 245, 245), outline=grey, width=3)
    d.text((px + 18, py + 18), "PMS (schema dbo), same database", font=fb, fill=navy)
    for j, l in enumerate(["read through mr.vw_MR_* views: readers, zones, properties,",
                           "tenants, periods, meters, statuses, inspection plan",
                           "written: dbo.MaintainFinalizedInvoices (invoice header),",
                           "dbo.MaintainMeterReading (optional copy)"]):
        d.text((px + 18, py + 62 + j * 30), l, font=fr, fill=(40, 40, 40))
    # Database Mail
    mx, my = 20, 330
    d.rounded_rectangle([mx, my, 725, my + 200], 14, fill="white", outline=grey, width=3)
    d.text((mx + 18, my + 18), "SQL Server Database Mail", font=fb, fill=navy)
    for j, l in enumerate(["Invoice Generation Report (HTML email)",
                           "profile noreply, msdb.dbo.sp_send_dbmail",
                           "logged in mr.InvoiceReportLog"]):
        d.text((mx + 18, my + 62 + j * 30), l, font=fr, fill=(40, 40, 40))
    d.line([px, py + 100, 725, my + 100], fill=grey, width=3)
    d.polygon([(725, my + 100), (739, my + 92), (739, my + 108)], fill=grey)
    im.save(path)


def shade(cell, hexcolor):
    tcPr = cell._tc.get_or_add_tcPr()
    sh = OxmlElement("w:shd")
    sh.set(qn("w:val"), "clear"); sh.set(qn("w:color"), "auto"); sh.set(qn("w:fill"), hexcolor)
    tcPr.append(sh)


def runs(par, text, bold=False, size=None, color=None):
    for part in re.split(r"(\*\*[^*]+\*\*|`[^`]+`)", text):
        if not part:
            continue
        if part.startswith("**"):
            r = par.add_run(part[2:-2]); r.bold = True
        elif part.startswith("`"):
            r = par.add_run(part[1:-1]); r.font.name = "Consolas"
        else:
            r = par.add_run(part)
        if bold:
            r.bold = True
        if size:
            r.font.size = Pt(size)
        if color:
            r.font.color.rgb = color


def table(doc, rows, size=9.5):
    hdr, body = rows[0], rows[2:]
    t = doc.add_table(rows=1 + len(body), cols=len(hdr))
    t.style = "Table Grid"; t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for r, row in enumerate([hdr] + body):
        for c, val in enumerate(row):
            cell = t.cell(r, c); cell.text = ""
            runs(cell.paragraphs[0], val, bold=(r == 0), size=size, color=RGBColor(255, 255, 255) if r == 0 else None)
            if r == 0:
                shade(cell, "1F3A5F")
    doc.add_paragraph()


def base(doctitle, margins=2.0, normal=10.5):
    doc = Document()
    sec = doc.sections[0]
    sec.left_margin = sec.right_margin = Cm(margins); sec.top_margin = Cm(1.4); sec.bottom_margin = Cm(1.2)
    st = doc.styles["Normal"]; st.font.name = "Calibri"; st.font.size = Pt(normal)
    for name in ("Heading 1", "Heading 2", "Heading 3", "Title"):
        doc.styles[name].font.color.rgb = NAVY; doc.styles[name].font.name = "Calibri"
    hp = sec.header.paragraphs[0]
    hp.add_run().add_picture(LOGO, width=Cm(4))
    hp.add_run("\t\tCR #47155 · Change ID 1004").font.size = Pt(8)
    fp = sec.footer.paragraphs[0]; fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
    fp.add_run(f"{doctitle} · Sewerage & Irrigation Meter Reading System · Dubai Investments Park").font.size = Pt(8)
    return doc


def signoff(doc):
    t = doc.add_table(rows=3, cols=3); t.style = "Table Grid"; t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for c, (k, v) in enumerate([("Prepared by", "Nayyar Jawaid"), ("Reviewed by", "Imtiyaz Hussain"), ("Approved by", "Nidhi Singhvi")]):
        h = t.cell(0, c); h.text = ""; runs(h.paragraphs[0], k, bold=True, size=10, color=RGBColor(255, 255, 255)); shade(h, "1F3A5F")
        t.cell(1, c).text = v
        t.cell(2, c).text = "Signature / date:"
    doc.add_paragraph()


def technical(diag):
    title = "Technical Document"
    doc = base(title)
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.add_run().add_picture(LOGO, width=Cm(9))
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    runs(p, title, bold=True, size=22, color=NAVY)
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    runs(p, "Sewerage & Irrigation Meter Reading System (DIP Field Service)", size=12)
    t = doc.add_table(rows=4, cols=2); t.style = "Table Grid"; t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, (k, v) in enumerate([("Request", "Service desk #47155, Change ID 1004"),
                                ("Document", "Technical description of the system as built"),
                                ("Version", "1.0"), ("Date", DATE)]):
        t.cell(i, 0).text = ""; runs(t.cell(i, 0).paragraphs[0], k, bold=True); shade(t.cell(i, 0), "E8EEF6"); t.cell(i, 1).text = v
    doc.add_paragraph()
    signoff(doc)
    lines = open(os.path.join(HERE, "technical.md"), encoding="utf-8").read().split("\n")
    i = 0
    while i < len(lines):
        ln = lines[i].rstrip()
        if not ln:
            i += 1; continue
        if ln.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")]); i += 1
            table(doc, rows); continue
        if ln == "!DIAGRAM":
            doc.add_picture(diag, width=Cm(17)); doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
            c = doc.add_paragraph(); c.alignment = WD_ALIGN_PARAGRAPH.CENTER
            r = c.add_run("Figure 1 – System architecture"); r.italic = True; r.font.size = Pt(9)
        elif ln.startswith("### "):
            doc.add_heading(ln[4:], 2)
        elif ln.startswith("## "):
            doc.add_heading(ln[3:], 1)
        elif ln.startswith("- "):
            runs(doc.add_paragraph(style="List Bullet"), ln[2:])
        elif re.match(r"\d+\. ", ln):
            runs(doc.add_paragraph(style="List Number"), re.sub(r"^\d+\. ", "", ln))
        else:
            runs(doc.add_paragraph(), ln)
        i += 1
    doc.save(os.path.join(OUT, "CR47155_08_Technical_Document.docx"))


GUIDE_STEPS = [
    ("Open the app", "Tap DIP Field Service. A new phone shows its ID and a code: give both to IT. After IT approves it, tap Check again. Then tap Download on Home for today's list."),
    ("Go to the meter", "Home shows the next meter in your route. Or use Zones, or Find (property code or tenant name)."),
    ("Check which meter", "The banner says SEWERAGE METER or IRRIGATION METER. With two meters, tap the tab you are reading."),
    ("How is the meter?", "Tap OK if it works, or the picture that matches (Faulty, Damaged, Covered with water, Meter changed). Not ready to read this plot? Tap Skip property, go to the next."),
    ("Photo and number", "Take a clear photo of the dial. Type the reading as a whole number, no commas. If a warning shows, check the number."),
    ("Check and send", "Check the tenant and tap Send. With no signal the reading is kept on the phone and sent later. The app moves to the next meter."),
    ("Finalize the property", "When every meter of the property is read, open the property and tap Finalize property, then Finalize. Wait for the message."),
    ("End of month (supervisor)", "My summary → Finalize Meter Reading for the Month → supervisor PIN → Finalize and generate invoices. Wait: this can take a few minutes. The month closes for everyone."),
]
GUIDE_TIPS = [
    "Read a meter again: open it and tap Read again; the new reading replaces the old one.",
    "Purple means readings are still on the phone: tap Upload now on My summary when you have signal.",
    "Messages at the bottom close with ✕ or a tap. The speaker button reads each screen aloud.",
    "Problems: \"This phone has been blocked\" or \"waiting for IT\" → call IT. \"Meters left to read\" → read or mark \"can't reach\" first.",
]
GUIDE_SHOTS = ["01_home", "05_capture_condition", "07_capture_number", "10_capture_sent", "16_finalize_ready", "13_summary"]
GUIDE_CAPTIONS = ["Home", "How is the meter?", "Type the number", "Sent", "Finalize property", "Finalize the month"]


def guide():
    title = "User Guide – Meter Reader"
    doc = base(title, margins=1.5, normal=9.5)
    sec = doc.sections[0]; sec.top_margin = Cm(1.2); sec.bottom_margin = Cm(1.0)
    p = doc.add_paragraph(); p.paragraph_format.space_after = Pt(2)
    runs(p, "DIP Field Service – reading Sewerage & Irrigation meters", bold=True, size=15, color=NAVY)
    p = doc.add_paragraph(); p.paragraph_format.space_after = Pt(4)
    runs(p, f"One page for meter readers and supervisors · App 1.0.0 · {DATE}", size=8.5)
    # Screens in one row
    t = doc.add_table(rows=2, cols=len(GUIDE_SHOTS)); t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for c, (shot, cap) in enumerate(zip(GUIDE_SHOTS, GUIDE_CAPTIONS)):
        cp = t.cell(0, c).paragraphs[0]; cp.alignment = WD_ALIGN_PARAGRAPH.CENTER
        cp.add_run().add_picture(os.path.join(HERE, "img", f"{shot}.png"), width=Cm(2.75))
        q = t.cell(1, c).paragraphs[0]; q.alignment = WD_ALIGN_PARAGRAPH.CENTER
        runs(q, cap, bold=True, size=7.5, color=NAVY)
    # Steps
    h = doc.add_paragraph(); h.paragraph_format.space_before = Pt(4); h.paragraph_format.space_after = Pt(2)
    runs(h, "Step by step", bold=True, size=11, color=NAVY)
    st = doc.add_table(rows=len(GUIDE_STEPS), cols=2); st.style = "Table Grid"; st.alignment = WD_TABLE_ALIGNMENT.CENTER
    for r, (k, v) in enumerate(GUIDE_STEPS):
        a = st.cell(r, 0); a.text = ""; runs(a.paragraphs[0], f"{r + 1}. {k}", bold=True, size=9, color=RGBColor(255, 255, 255)); shade(a, "1F3A5F")
        b = st.cell(r, 1); b.text = ""; runs(b.paragraphs[0], v, size=9)
        a.width = Cm(4.2); b.width = Cm(13.8)
    st.autofit = False
    st.columns[0].width = Cm(4.2); st.columns[1].width = Cm(13.8)
    h = doc.add_paragraph(); h.paragraph_format.space_before = Pt(4); h.paragraph_format.space_after = Pt(2)
    runs(h, "Good to know", bold=True, size=11, color=NAVY)
    for tip in GUIDE_TIPS:
        q = doc.add_paragraph(style="List Bullet"); q.paragraph_format.space_after = Pt(0)
        runs(q, tip, size=9)
    doc.save(os.path.join(OUT, "CR47155_09_User_Guide.docx"))


if __name__ == "__main__":
    d = os.path.join(HERE, "architecture.png")
    diagram(d)
    technical(d)
    guide()
    print("ok")
