# Reproducing Figures, Screenshots, HTML & PDFs (`README_BUILD.md`)

All build scripts under `docs/study-guide/tools/` are deterministic, offline, and safe to run from the `ADTNM` repository (using `$env:PYTHONDONTWRITEBYTECODE = '1'` to prevent `__pycache__`).

## Dependencies
- **Java JDK `24`** (`javac`, `java`) + compiled `n:\R&D\cog\the-one\target` classes
- **Python `3.12+`** packages: `markdown`, `pygments`, `matplotlib`, `numpy`, `scipy`, `reportlab`, `PyMuPDF` (`fitz`), `Pillow` (`PIL`)

> Note (`OPEN_QUESTIONS.md`): Because `pandoc`, `wkhtmltopdf`, `weasyprint`, `mmdc`, and headless Edge `--print-to-pdf` are not installed/usable on this Windows host, `build_study_guide.py` builds the searchable offline HTML via pure Python `markdown` + `pygments` and renders bookmarked PDFs natively via `reportlab` + `PIL`, verified page-by-page using `fitz` (`PyMuPDF`).

## Full Build Commands (PowerShell)
```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
python "docs\study-guide\tools\extract_code_inventory.py"
python "docs\study-guide\tools\generate_provenance.py"
python "docs\study-guide\tools\generate_figures.py"
python "docs\study-guide\tools\capture_screenshots.py"
python "docs\study-guide\tools\build_study_guide.py"
python "docs\study-guide\tools\run_quality_gates.py"
```

## Outputs (`docs/study-guide/build/`)
- `ADTNM_Study_Guide.html` — Single-file offline reader with collapsible chapter sidebar, embedded SVG/PNG figures, and instant client-side full-text filtering.
- `ADTNM_Study_Guide.pdf` — Complete multi-chapter ReportLab PDF with cover page, clickable PDF bookmarks/outline, and 300-DPI figures.
- `ADTNM_Executive_Summary.pdf` — Standalone `<= 2`-page brief for supervisors & reviewers.
- `ADTNM_One_Page_Cheatsheet.pdf` — Standalone `1`-page desktop developer reference card.
