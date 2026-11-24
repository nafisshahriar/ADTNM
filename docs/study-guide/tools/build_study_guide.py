#!/usr/bin/env python3
"""Build offline searchable HTML and 3 verified PDF deliverables for the ADTNM Study Guide.

Outputs under docs/study-guide/build/:
  - ADTNM_Study_Guide.html          (self-contained offline HTML with sidebar & instant search)
  - ADTNM_Study_Guide.pdf           (ReportLab bookmarked multi-chapter PDF with 300-DPI figures)
  - ADTNM_Executive_Summary.pdf     (standalone <= 2 pages, verified via PyMuPDF fitz)
  - ADTNM_One_Page_Cheatsheet.pdf   (standalone == 1 page, verified via PyMuPDF fitz)
"""

from __future__ import annotations

import base64
import html
import re
from pathlib import Path
from typing import List, Tuple

import fitz  # PyMuPDF
import markdown
from PIL import Image as PILImage
from pygments.formatters import HtmlFormatter
from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import (
    Image as RLImage,
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)
from xml.sax.saxutils import escape as xml_escape

SCRIPT_DIR = Path(__file__).resolve().parent
GUIDE_DIR = SCRIPT_DIR.parent
BUILD_DIR = GUIDE_DIR / "build"

CHAPTER_ORDER: List[Tuple[str, str]] = [
    ("Overview & Navigation", "index.md"),
    ("Ch 01: Executive Summary", "chapters/01_executive_summary.md"),
    ("Ch 02: Primer for Newcomers", "chapters/02_primer_for_newcomers.md"),
    ("Ch 03: Architecture Overview", "chapters/03_architecture_overview.md"),
    ("Ch 04a: Mechanisms 01-06 (Telemetry & Profiles)", "chapters/04a_mechanisms_telemetry_and_profiles.md"),
    ("Ch 04b: Mechanisms 07-11 (SRL, Shield & Replay)", "chapters/04b_mechanisms_srl_and_shield.md"),
    ("Ch 04c: Mechanisms 12-17 (Adapters & Remote)", "chapters/04c_mechanisms_adapters_and_remote.md"),
    ("Ch 04d: Mechanisms 18-23 (RL, Controls & Reporting)", "chapters/04d_mechanisms_rl_and_reporting.md"),
    ("Ch 05: Codebase Tour & Developer Guide", "chapters/05_codebase_tour.md"),
    ("Ch 06: Empirical Evaluation & Results", "chapters/06_evaluation_and_results.md"),
    ("Ch 07: Hands-On Tutorials", "chapters/07_howto_and_tutorials.md"),
    ("Ch 08: History, Post-Mortems & ADRs", "chapters/08_history_and_decisions.md"),
    ("Worked Examples (1-6)", "worked_examples/worked_examples.md"),
    ("Appendix A: RFC 9675 & 7575 Alignment", "appendices/appendix_a_rfc_conformance.md"),
    ("Appendix B: Glossary & Acronyms", "appendices/appendix_b_glossary.md"),
    ("Appendix C: Reference Tables", "appendices/appendix_c_reference_tables.md"),
    ("Appendix D: Provenance Ledger", "appendices/appendix_d_provenance.md"),
    ("Discrepancies Log (DISC-01..10)", "appendix_discrepancies.md"),
    ("Extra: Week 1 Onboarding Plan", "extras/onboarding_week1_plan.md"),
    ("Extra: One-Page Cheat Sheet", "extras/one_page_cheatsheet.md"),
    ("Extra: Supervisor Checklist", "extras/supervisor_review_checklist.md"),
]


def resolve_asset_path(md_file: Path, rel_src: str) -> Path | None:
    p = (md_file.parent / rel_src).resolve()
    if p.exists():
        return p
    return None


def inline_html_images(html_body: str, md_file: Path) -> str:
    def _repl(m):
        alt = m.group(1)
        src = m.group(2)
        if src.startswith(("http://", "https://", "data:")):
            return m.group(0)
        p = resolve_asset_path(md_file, src)
        if p is None:
            return m.group(0)
        ext = p.suffix.lower()
        mime = "image/svg+xml" if ext == ".svg" else "image/png"
        b64 = base64.b64encode(p.read_bytes()).decode("ascii")
        return f'<img src="data:{mime};base64,{b64}" alt="{html.escape(alt)}" class="doc-img" />'

    return re.sub(r'<img[^>]*alt="([^"]*)"[^>]*src="([^"]*)"[^>]*/?>', _repl, html_body)


def style_github_callouts_html(raw_html: str) -> str:
    def _cb(m):
        kind = m.group(1).upper()
        cls = {"TIP": "callout-tip", "IMPORTANT": "callout-imp", "WARNING": "callout-warn", "NOTE": "callout-note"}.get(kind, "callout-note")
        return f'<blockquote><p class="callout-badge {cls}">{kind}</p><p>'

    out = re.sub(r"<blockquote>\s*<p>\[!(TIP|IMPORTANT|WARNING|NOTE)\]\s*", _cb, raw_html, flags=re.I)
    return out


def build_offline_html() -> Path:
    pyg_css = HtmlFormatter(style="friendly").get_style_defs(".codehilite")
    md_conv = markdown.Markdown(extensions=["tables", "fenced_code", "codehilite", "toc", "sane_lists"])

    nav_items = []
    sections = []
    for idx, (label, rel_path) in enumerate(CHAPTER_ORDER):
        md_file = GUIDE_DIR / rel_path
        sec_id = f"sec-{idx:02d}"
        text = md_file.read_text(encoding="utf-8")
        md_conv.reset()
        body = md_conv.convert(text)
        body = inline_html_images(body, md_file)
        body = style_github_callouts_html(body)
        nav_items.append(f'<a href="#{sec_id}" class="nav-link" data-target="{sec_id}">{html.escape(label)}</a>')
        sections.append(f'<section id="{sec_id}" class="chapter-section" data-title="{html.escape(label)}">{body}</section>')

    page_html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>ADTNM Comprehensive Study Guide (d4a159f)</title>
<style>
  :root {{ --bg:#ffffff; --fg:#0f172a; --muted:#475569; --side:#f8fafc; --border:#cbd5e1; --accent:#0072b2; }}
  * {{ box-sizing: border-box; }}
  body {{ margin:0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; color:var(--fg); background:var(--bg); line-height:1.58; display:flex; }}
  aside {{ width:290px; position:fixed; top:0; bottom:0; left:0; background:var(--side); border-right:1px solid var(--border); overflow-y:auto; padding:16px 14px; }}
  main {{ margin-left:290px; max-width:980px; padding:28px 40px 60px; width:calc(100% - 290px); }}
  .brand {{ font-size:1.05rem; font-weight:700; color:var(--accent); margin-bottom:4px; }}
  .meta {{ font-size:0.75rem; color:var(--muted); margin-bottom:12px; }}
  #searchBox {{ width:100%; padding:8px 10px; border:1px solid var(--border); border-radius:6px; font-size:0.86rem; margin-bottom:10px; }}
  #searchMeta {{ font-size:0.74rem; color:var(--muted); margin-bottom:8px; min-height:16px; }}
  .nav-link {{ display:block; padding:5px 8px; border-radius:5px; text-decoration:none; color:#1e293b; font-size:0.82rem; margin-bottom:2px; }}
  .nav-link:hover, .nav-link.active {{ background:#e0f2fe; color:#0369a1; font-weight:600; }}
  .chapter-section {{ padding-bottom:34px; margin-bottom:34px; border-bottom:2px solid #e2e8f0; }}
  h1 {{ color:#0f172a; border-bottom:2px solid var(--accent); padding-bottom:6px; font-size:1.65rem; }}
  h2 {{ color:#0369a1; margin-top:1.45em; font-size:1.25rem; }}
  h3 {{ color:#1e293b; margin-top:1.1em; font-size:1.04rem; }}
  table {{ border-collapse:collapse; width:100%; margin:12px 0; font-size:0.86rem; }}
  th, td {{ border:1px solid var(--border); padding:6px 8px; text-align:left; vertical-align:top; }}
  th {{ background:#f1f5f9; font-weight:600; }}
  tr:nth-child(even) td {{ background:#fafafa; }}
  code {{ background:#f1f5f9; padding:1px 4px; border-radius:3px; font-size:0.86em; font-family:Consolas, monospace; }}
  pre {{ background:#f8fafc; border:1px solid var(--border); border-radius:6px; padding:10px 12px; overflow-x:auto; font-size:0.82rem; }}
  blockquote {{ border-left:4px solid var(--accent); margin:12px 0; padding:8px 14px; background:#f8fafc; border-radius:0 6px 6px 0; }}
  .callout-badge {{ font-weight:700; font-size:0.75rem; letter-spacing:0.05em; margin:0 0 4px; }}
  .callout-tip {{ color:#009e73; }} .callout-imp {{ color:#0072b2; }} .callout-warn {{ color:#d55e00; }} .callout-note {{ color:#6d28d9; }}
  .doc-img {{ max-width:100%; height:auto; display:block; margin:14px auto; border:1px solid #e2e8f0; border-radius:6px; padding:4px; background:white; }}
  .search-hit {{ background:#fef08a; padding:0 2px; }}
  {pyg_css}
</style>
</head>
<body>
<aside>
  <div class="brand">ADTNM Study Guide</div>
  <div class="meta">Commit d4a159f | Tag v1.0-tnsm-freeze</div>
  <input id="searchBox" type="search" placeholder="Filter chapters (e.g. Shield, H2, M3)..." />
  <div id="searchMeta">21 chapters &amp; appendices ready</div>
  <nav id="navList">
    {''.join(nav_items)}
  </nav>
</aside>
<main id="contentRoot">
  {''.join(sections)}
</main>
<script>
const box = document.getElementById('searchBox');
const meta = document.getElementById('searchMeta');
const secs = Array.from(document.querySelectorAll('.chapter-section'));
const links = Array.from(document.querySelectorAll('.nav-link'));
box.addEventListener('input', () => {{
  const q = box.value.trim().toLowerCase();
  let shown = 0;
  secs.forEach((s, i) => {{
    const match = !q || s.textContent.toLowerCase().includes(q);
    s.style.display = match ? '' : 'none';
    links[i].style.display = match ? 'block' : 'none';
    if (match) shown++;
  }});
  meta.textContent = q ? `Showing ${{shown}} / ${{secs.length}} sections` : `${{secs.length}} sections ready`;
}});
</script>
</body>
</html>
"""
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    out = BUILD_DIR / "ADTNM_Study_Guide.html"
    out.write_text(page_html, encoding="utf-8")
    return out


# ---------------------------------------------------------------------------
# ReportLab PDF Helpers
# ---------------------------------------------------------------------------
def clean_inline_md(text: str) -> str:
    """Convert inline markdown bold/italic/code/links cleanly into ReportLab XML."""
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = text.replace("$$", "").replace("$", "")
    text = text.replace(r"\le", "<=").replace(r"\ge", ">=").replace(r"\equiv", "==")
    text = text.replace(r"\implies", "->").replace(r"\to", "->").replace(r"\land", "AND").replace(r"\cdot", "*")

    # Extract code spans first so *, **, <, > inside backticks never collide with XML/italic parsing
    code_spans: List[str] = []

    def _hold_code(m):
        idx = len(code_spans)
        code_spans.append(xml_escape(m.group(1)))
        return f"@@CODE{idx}@@"

    text = re.sub(r"`([^`]+)`", _hold_code, text)
    text = xml_escape(text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"(?<!\*)\*([^*]+?)\*(?!\*)", r"<i>\1</i>", text)
    for idx, cval in enumerate(code_spans):
        text = text.replace(f"@@CODE{idx}@@", f'<font face="Courier" size="8.0">{cval}</font>')
    return text


def md_to_flowables(
    md_text: str,
    md_file: Path,
    styles: dict,
    max_img_w: float,
    max_img_h: float,
    chapter_bookmark: Tuple[str, str] | None = None,
    outline_entries: List[Tuple[str, str, int]] | None = None,
) -> List[Any]:
    flows: List[Any] = []
    lines = md_text.splitlines()
    i = 0
    bm_added = False

    while i < len(lines):
        ln = lines[i]
        stripped = ln.strip()
        if not stripped or stripped == "---":
            i += 1
            continue

        # Code block
        if stripped.startswith("```"):
            code_lines = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            i += 1
            flows.append(Preformatted("\n".join(code_lines[:45]), styles["CodeBlock"]))
            flows.append(Spacer(1, 3))
            continue

        # Image embed ![alt](rel)
        m_img = re.match(r"^!\[([^\]]*)\]\(([^)]+)\)$", stripped)
        if m_img:
            alt, rel = m_img.group(1), m_img.group(2)
            # Prefer 300-DPI PNG twin for SVGs cleanly inside ReportLab
            rel_png = rel.replace("/svg/", "/png/").replace(".svg", ".png")
            img_path = resolve_asset_path(md_file, rel_png) or resolve_asset_path(md_file, rel)
            if img_path and img_path.exists() and img_path.suffix.lower() in (".png", ".jpg"):
                with PILImage.open(img_path) as pim:
                    iw, ih = pim.size
                scale = min(max_img_w / iw, max_img_h / ih)
                rl_img = RLImage(str(img_path), width=iw * scale, height=ih * scale)
                flows.append(Spacer(1, 3))
                flows.append(rl_img)
                if alt:
                    flows.append(Paragraph(clean_inline_md(alt), styles["Caption"]))
                flows.append(Spacer(1, 4))
            i += 1
            continue

        # Table block
        if stripped.startswith("|") and i + 1 < len(lines) and lines[i + 1].strip().startswith("|") and "---" in lines[i + 1]:
            tbl_lines = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                tbl_lines.append(lines[i].strip())
                i += 1
            header = [c.strip() for c in tbl_lines[0].strip("|").split("|")]
            body_rows = [
                [c.strip() for c in r.strip("|").split("|")]
                for r in tbl_lines[2:]
            ]
            ncols = len(header)
            col_w = max_img_w / max(1, ncols)
            table_data = [[Paragraph(f"<b>{clean_inline_md(c)}</b>", styles["TblHead"]) for c in header]]
            for r in body_rows:
                padded = (r + [""] * ncols)[:ncols]
                table_data.append([Paragraph(clean_inline_md(c), styles["TblCell"]) for c in padded])
            t = Table(table_data, colWidths=[col_w] * ncols, repeatRows=1)
            t.setStyle(TableStyle([
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#EFF6FF")),
                ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#CBD5E1")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("TOPPADDING", (0, 0), (-1, -1), styles["tbl_pad"]),
                ("BOTTOMPADDING", (0, 0), (-1, -1), styles["tbl_pad"]),
                ("LEFTPADDING", (0, 0), (-1, -1), 4),
                ("RIGHTPADDING", (0, 0), (-1, -1), 4),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F8FAFC")]),
            ]))
            flows.append(Spacer(1, 3))
            flows.append(t)
            flows.append(Spacer(1, 4))
            continue

        # Headings
        m_h = re.match(r"^(#{1,3})\s+(.*)$", stripped)
        if m_h:
            lvl = len(m_h.group(1))
            htxt = m_h.group(2).strip()
            pstyle = styles[f"H{lvl}"]
            if lvl == 1 and chapter_bookmark and not bm_added and outline_entries is not None:
                key, title = chapter_bookmark
                p = Paragraph(f'<a name="{xml_escape(key)}"/>' + clean_inline_md(htxt), pstyle)
                p._bookmark_key = key
                p._bookmark_title = title
                bm_added = True
                flows.append(p)
            else:
                flows.append(Paragraph(clean_inline_md(htxt), pstyle))
            i += 1
            continue

        # Blockquote / Callout
        if stripped.startswith(">"):
            q_lines = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                q_lines.append(re.sub(r"^>\s?", "", lines[i].strip()))
                i += 1
            joined = " ".join(q_lines)
            joined = re.sub(r"^\[!(TIP|IMPORTANT|WARNING|NOTE)\]\s*", r"<b>[\1]</b> ", joined, flags=re.I)
            flows.append(Paragraph(clean_inline_md(joined), styles["Callout"]))
            continue

        # List item
        if re.match(r"^[-*]\s+", stripped) or re.match(r"^\d+\.\s+", stripped):
            bullet_txt = re.sub(r"^([-*]|\d+\.)\s+", "- ", stripped)
            flows.append(Paragraph(clean_inline_md(bullet_txt), styles["BulletLine"]))
            i += 1
            continue

        # Standard paragraph
        para_lines = [stripped]
        i += 1
        while i < len(lines):
            nxt = lines[i].strip()
            if not nxt or nxt.startswith(("#", ">", "|", "```", "- ", "* ", "---")) or re.match(r"^\d+\.\s+", nxt) or nxt.startswith("!["):
                break
            para_lines.append(nxt)
            i += 1
        flows.append(Paragraph(clean_inline_md(" ".join(para_lines)), styles["Body"]))
    return flows


def make_rl_styles(compact: str = "normal") -> dict:
    base = getSampleStyleSheet()
    if compact == "cheatsheet":
        fs_body, ld_body, fs_h1, fs_h2, fs_tbl, pad = 7.4, 9.0, 10.5, 8.2, 6.8, 1.5
    elif compact == "exec":
        fs_body, ld_body, fs_h1, fs_h2, fs_tbl, pad = 8.1, 9.8, 12.2, 9.4, 7.1, 2.0
    else:
        fs_body, ld_body, fs_h1, fs_h2, fs_tbl, pad = 9.3, 12.2, 15.0, 11.5, 7.6, 3.0

    return {
        "tbl_pad": pad,
        "H1": ParagraphStyle("SG_H1", parent=base["Heading1"], fontSize=fs_h1, leading=fs_h1 + 3, textColor=colors.HexColor("#0072B2"), spaceBefore=4 if compact != "normal" else 8, spaceAfter=3),
        "H2": ParagraphStyle("SG_H2", parent=base["Heading2"], fontSize=fs_h2, leading=fs_h2 + 2.5, textColor=colors.HexColor("#0369A1"), spaceBefore=4 if compact != "normal" else 7, spaceAfter=2),
        "H3": ParagraphStyle("SG_H3", parent=base["Heading3"], fontSize=fs_body + 0.8, leading=fs_body + 3, textColor=colors.HexColor("#1E293B"), spaceBefore=3, spaceAfter=1),
        "Body": ParagraphStyle("SG_Body", parent=base["Normal"], fontSize=fs_body, leading=ld_body, textColor=colors.HexColor("#0F172A"), spaceAfter=2 if compact != "normal" else 4),
        "BulletLine": ParagraphStyle("SG_Bul", parent=base["Normal"], fontSize=fs_body, leading=ld_body, leftIndent=10, textColor=colors.HexColor("#0F172A"), spaceAfter=1.5 if compact != "normal" else 2.5),
        "Callout": ParagraphStyle("SG_Call", parent=base["Normal"], fontSize=fs_body - 0.3, leading=ld_body - 0.5, leftIndent=8, rightIndent=8, backColor=colors.HexColor("#F8FAFC"), borderColor=colors.HexColor("#0072B2"), borderWidth=0.6, borderPadding=4, spaceBefore=2, spaceAfter=3),
        "CodeBlock": ParagraphStyle("SG_Code", fontName="Courier", fontSize=max(6.4, fs_body - 1.5), leading=max(7.6, fs_body), backColor=colors.HexColor("#F8FAFC"), leftIndent=6, rightIndent=6),
        "TblHead": ParagraphStyle("SG_TH", parent=base["Normal"], fontSize=fs_tbl, leading=fs_tbl + 1.8, textColor=colors.HexColor("#0F172A")),
        "TblCell": ParagraphStyle("SG_TD", parent=base["Normal"], fontSize=fs_tbl, leading=fs_tbl + 1.8, textColor=colors.HexColor("#1E293B")),
        "Caption": ParagraphStyle("SG_Cap", parent=base["Normal"], fontSize=7.4, leading=9.0, alignment=1, textColor=colors.HexColor("#475569")),
    }


class BookmarkedDocTemplate(SimpleDocTemplate):
    def afterFlowable(self, flowable):
        if hasattr(flowable, "_bookmark_key"):
            key = flowable._bookmark_key
            title = flowable._bookmark_title
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(title, key, level=0, closed=False)


def build_pdfs() -> None:
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    # 1. Full Study Guide PDF with Outlines/Bookmarks
    full_pdf = BUILD_DIR / "ADTNM_Study_Guide.pdf"
    margin = 0.55 * inch
    doc = BookmarkedDocTemplate(str(full_pdf), pagesize=letter, leftMargin=margin, rightMargin=margin, topMargin=margin, bottomMargin=margin, title="ADTNM Comprehensive Study Guide", author="ADTNM Architecture Team")
    st_norm = make_rl_styles("normal")
    max_w = letter[0] - 2 * margin
    story: List[Any] = [
        Spacer(1, 1.3 * inch),
        Paragraph("ADTNM Comprehensive Study Guide", ParagraphStyle("CoverT", fontSize=24, leading=28, textColor=colors.HexColor("#0072B2"), fontName="Helvetica-Bold")),
        Spacer(1, 10),
        Paragraph("Architectural Management Framework for Autonomic Delay/Disruption-Tolerant Networks (RFC 9675 &amp; RFC 7575-Inspired)", ParagraphStyle("CoverS", fontSize=12, leading=16, textColor=colors.HexColor("#334155"))),
        Spacer(1, 18),
        Paragraph("<b>Documented Commit:</b> d4a159f &nbsp;|&nbsp; <b>Frozen Confirmatory Tag:</b> v1.0-tnsm-freeze (ca5d9ac) &nbsp;|&nbsp; <b>Exploratory:</b> v1.0-exp-controls", st_norm["Body"]),
        PageBreak(),
    ]
    outlines: List[Tuple[str, str, int]] = []
    for idx, (label, rel_path) in enumerate(CHAPTER_ORDER):
        md_file = GUIDE_DIR / rel_path
        bm_key = f"chap_{idx:02d}"
        story.extend(
            md_to_flowables(
                md_file.read_text(encoding="utf-8"),
                md_file,
                st_norm,
                max_img_w=max_w,
                max_img_h=3.6 * inch,
                chapter_bookmark=(bm_key, label),
                outline_entries=outlines,
            )
        )
        story.append(PageBreak())
    doc.build(story)

    # 2. Standalone Executive Summary PDF (strictly <= 2 pages)
    exec_pdf = BUILD_DIR / "ADTNM_Executive_Summary.pdf"
    exec_margin = 0.42 * inch
    exec_doc = SimpleDocTemplate(str(exec_pdf), pagesize=letter, leftMargin=exec_margin, rightMargin=exec_margin, topMargin=0.38 * inch, bottomMargin=0.38 * inch, title="ADTNM Executive Summary")
    st_exec = make_rl_styles("exec")
    exec_md = GUIDE_DIR / "chapters/01_executive_summary.md"
    exec_story = md_to_flowables(exec_md.read_text(encoding="utf-8"), exec_md, st_exec, max_img_w=letter[0] - 2 * exec_margin, max_img_h=1.75 * inch)
    exec_doc.build(exec_story)

    # 3. Standalone One-Page Cheatsheet PDF (strictly == 1 page)
    cs_pdf = BUILD_DIR / "ADTNM_One_Page_Cheatsheet.pdf"
    cs_margin = 0.38 * inch
    cs_doc = SimpleDocTemplate(str(cs_pdf), pagesize=letter, leftMargin=cs_margin, rightMargin=cs_margin, topMargin=0.35 * inch, bottomMargin=0.35 * inch, title="ADTNM One-Page Cheat Sheet")
    st_cs = make_rl_styles("cheatsheet")
    cs_md = GUIDE_DIR / "extras/one_page_cheatsheet.md"
    cs_story = md_to_flowables(cs_md.read_text(encoding="utf-8"), cs_md, st_cs, max_img_w=letter[0] - 2 * cs_margin, max_img_h=1.5 * inch)
    cs_doc.build(cs_story)

    # Verify via PyMuPDF (fitz)
    for p, cond_desc, pred in [
        (full_pdf, "Study Guide PDF > 20 pages & outlines", lambda d: d.page_count >= 20 and len(d.get_toc()) >= 15),
        (exec_pdf, "Executive Summary <= 2 pages", lambda d: 1 <= d.page_count <= 2),
        (cs_pdf, "One-Page Cheatsheet == 1 page", lambda d: d.page_count == 1),
    ]:
        with fitz.open(p) as pdf_doc:
            print(f"{p.name}: pages={pdf_doc.page_count}, toc_entries={len(pdf_doc.get_toc())}")
            assert pred(pdf_doc), f"PDF verification failed for {p.name} ({cond_desc}), pages={pdf_doc.page_count}"


def main() -> None:
    html_path = build_offline_html()
    print(f"Built HTML: {html_path} ({html_path.stat().st_size // 1024} KB)")
    build_pdfs()
    print("All HTML & PDF deliverables verified via fitz cleanly!")


if __name__ == "__main__":
    main()
