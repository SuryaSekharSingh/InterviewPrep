from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import simpleSplit
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, Flowable, KeepTogether, HRFlowable,
)
from reportlab.graphics.shapes import Drawing, Rect, String, Line, Circle, Wedge
from reportlab.lib.colors import HexColor
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output" / "pdf" / "InterviewEdge_Project_Report_2026-10-07.pdf"
OUT.parent.mkdir(parents=True, exist_ok=True)

pdfmetrics.registerFont(TTFont("Segoe", r"C:\Windows\Fonts\segoeui.ttf"))
pdfmetrics.registerFont(TTFont("SegoeBold", r"C:\Windows\Fonts\segoeuib.ttf"))
pdfmetrics.registerFont(TTFont("Consolas", r"C:\Windows\Fonts\consola.ttf"))
pdfmetrics.registerFontFamily("Segoe", normal="Segoe", bold="SegoeBold")

NAVY = HexColor("#17253F")
BLUE = HexColor("#325DEB")
TEAL = HexColor("#108C83")
INK = HexColor("#26344B")
MUTED = HexColor("#5A697E")
PALE = HexColor("#F3F6FB")
LINE = HexColor("#DCE4EF")
AMBER = HexColor("#99671B")
PALE_AMBER = HexColor("#FFF7E8")
WHITE = colors.white

PAGE_W, PAGE_H = A4
MARGIN = 48
CONTENT = PAGE_W - MARGIN * 2

styles = {
    "eyebrow": ParagraphStyle("eyebrow", fontName="SegoeBold", fontSize=8, leading=11, textColor=BLUE, spaceAfter=9),
    "h1": ParagraphStyle("h1", fontName="SegoeBold", fontSize=20, leading=24, textColor=NAVY, spaceAfter=12),
    "h2": ParagraphStyle("h2", fontName="SegoeBold", fontSize=11.5, leading=15, textColor=NAVY, spaceBefore=12, spaceAfter=5),
    "h3": ParagraphStyle("h3", fontName="SegoeBold", fontSize=9.5, leading=13, textColor=BLUE, spaceBefore=8, spaceAfter=3),
    "body": ParagraphStyle("body", fontName="Segoe", fontSize=8.8, leading=13.4, textColor=INK, spaceAfter=7),
    "small": ParagraphStyle("small", fontName="Segoe", fontSize=7.6, leading=10.5, textColor=MUTED, spaceAfter=5),
    "table": ParagraphStyle("table", fontName="Segoe", fontSize=7.7, leading=10.5, textColor=INK),
    "tablehead": ParagraphStyle("tablehead", fontName="SegoeBold", fontSize=7.7, leading=10, textColor=WHITE),
    "callout": ParagraphStyle("callout", fontName="Segoe", fontSize=8.5, leading=12.6, textColor=NAVY),
    "code": ParagraphStyle("code", fontName="Consolas", fontSize=7.6, leading=11.5, textColor=NAVY),
    "cover_title": ParagraphStyle("cover_title", fontName="SegoeBold", fontSize=36, leading=40, textColor=WHITE),
    "cover_sub": ParagraphStyle("cover_sub", fontName="Segoe", fontSize=14, leading=20, textColor=WHITE),
}


def P(text, style="body"):
    return Paragraph(text, styles[style])


def b(text):
    return "<b>" + escape(text) + "</b>"


def bullet(text):
    return P("<font color='#325DEB'><b>-</b></font>  " + text)


def title(kicker, heading, deck=None):
    parts = [P(kicker.upper(), "eyebrow"), P(heading, "h1")]
    if deck:
        parts.append(P(deck))
    parts.append(HRFlowable(width=CONTENT, thickness=1, color=LINE, spaceBefore=3, spaceAfter=13))
    return parts


def callout(label, text, fill=PALE):
    content = P("<b>" + escape(label.upper()) + "</b><br/>" + text, "callout")
    table = Table([[content]], colWidths=[CONTENT], hAlign="LEFT")
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), fill),
        ("BOX", (0, 0), (-1, -1), .7, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 12),
        ("RIGHTPADDING", (0, 0), (-1, -1), 12),
        ("TOPPADDING", (0, 0), (-1, -1), 10),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 10),
    ]))
    return table


def grid(headers, rows, widths=None):
    widths = widths or [CONTENT / len(headers)] * len(headers)
    data = [[P(escape(h), "tablehead") for h in headers]]
    for row in rows:
        data.append([P(cell, "table") for cell in row])
    t = Table(data, colWidths=widths, hAlign="LEFT", repeatRows=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), NAVY),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, PALE]),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("GRID", (0, 0), (-1, -1), .4, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 9),
        ("RIGHTPADDING", (0, 0), (-1, -1), 9),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return t


def code(lines):
    inner = P("<br/>".join(escape(x).replace(" ", "&nbsp;") for x in lines), "code")
    t = Table([[inner]], colWidths=[CONTENT])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), PALE),
        ("LINEBELOW", (0, 0), (-1, -1), .6, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 13),
        ("RIGHTPADDING", (0, 0), (-1, -1), 13),
        ("TOPPADDING", (0, 0), (-1, -1), 10),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 10),
    ]))
    return t


class Architecture(Flowable):
    def __init__(self):
        super().__init__()
        self.width = CONTENT
        self.height = 305

    def draw(self):
        c = self.canv
        c.setFont("SegoeBold", 9)
        c.setStrokeColor(LINE)
        c.setLineWidth(1)

        def box(x, y, w, h, heading, details, color):
            c.setFillColor(color)
            c.roundRect(x, y, w, h, 8, stroke=0, fill=1)
            c.setFillColor(WHITE)
            c.setFont("SegoeBold", 10)
            c.drawString(x + 11, y + h - 22, heading)
            c.setFont("Segoe", 7.5)
            for i, line in enumerate(details):
                c.drawString(x + 11, y + h - 37 - i * 11, line)

        box(0, 179, 149, 84, "Android emulator", ["Java / XML screens", "Room cache + drafts", "Recording + Retrofit"], BLUE)
        box(206, 179, 248, 84, "Python FastAPI", ["Authenticated /api/v1 resources", "Domain rules + reports + admin", "PostgreSQL-backed job worker"], NAVY)
        box(210, 38, 116, 82, "PostgreSQL", ["Accounts", "Content / results", "Jobs / evidence"], TEAL)
        box(340, 38, 114, 82, "Local providers", ["Ollama Qwen3", "whisper.cpp", "Private WAV files"], HexColor("#7258AC"))

        c.setStrokeColor(BLUE)
        c.setLineWidth(2)
        c.line(149, 221, 202, 221)
        c.line(196, 224, 202, 221)
        c.line(196, 218, 202, 221)
        c.setFillColor(MUTED)
        c.setFont("Segoe", 7)
        c.drawCentredString(176, 236, "ADB reverse / HTTP")
        c.setStrokeColor(TEAL)
        c.line(273, 177, 273, 123)
        c.line(270, 129, 273, 123)
        c.line(276, 129, 273, 123)
        c.setStrokeColor(HexColor("#7258AC"))
        c.line(398, 177, 398, 123)
        c.line(395, 129, 398, 123)
        c.line(401, 129, 398, 123)
        c.setFillColor(MUTED)
        c.setFont("Segoe", 7.5)
        c.drawString(0, 282, "Student device")
        c.drawString(206, 282, "Laptop services - loopback by default")
        c.drawString(0, 9, "Only the backend talks to the database, AI runtime and recording store.")


class ScoreVisual(Flowable):
    def __init__(self):
        super().__init__()
        self.width = CONTENT
        self.height = 122

    def draw(self):
        c = self.canv
        c.setFillColor(PALE)
        c.roundRect(0, 0, CONTENT, 121, 9, fill=1, stroke=0)
        c.setStrokeColor(LINE)
        c.setLineWidth(11)
        c.circle(68, 60, 39, fill=0, stroke=1)
        c.setStrokeColor(BLUE)
        c.setLineWidth(11)
        c.arc(29, 21, 107, 99, startAng=90, extent=-72 * 3.6)
        c.setFillColor(NAVY)
        c.setFont("SegoeBold", 15)
        c.drawCentredString(68, 57, "72")
        c.setFont("Segoe", 7.5)
        c.drawCentredString(68, 43, "of 100")
        c.setFont("SegoeBold", 10)
        c.drawString(137, 85, "Practice score example")
        c.setFont("Segoe", 9)
        c.drawString(137, 64, "Interview 75 x 40% = 30.0")
        c.drawString(137, 48, "Tests 68 x 40% = 27.2")
        c.drawString(137, 32, "English 74 x 20% = 14.8")
        c.setFont("SegoeBold", 9)
        c.drawString(344, 48, "Total: 72.0")


def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(LINE)
    canvas.line(MARGIN, 36, PAGE_W - MARGIN, 36)
    canvas.setFont("Segoe", 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawString(MARGIN, 24, "InterviewEdge  |  Project report  |  6 Oct 2026")
    canvas.drawRightString(PAGE_W - MARGIN, 24, f"{doc.page}")
    if doc.page > 1:
        canvas.setFont("SegoeBold", 8)
        canvas.setFillColor(BLUE)
        canvas.drawString(MARGIN, PAGE_H - 35, "INTERVIEWEDGE")
    canvas.restoreState()


doc = BaseDocTemplate(str(OUT), pagesize=A4, leftMargin=MARGIN, rightMargin=MARGIN,
                      topMargin=56, bottomMargin=50, title="InterviewEdge - Project Report",
                      author="InterviewEdge project team", subject="Architecture, functionality, testing and presentation briefing")
frame = Frame(MARGIN, 50, CONTENT, PAGE_H - 106, leftPadding=0, rightPadding=0,
              topPadding=0, bottomPadding=0)
doc.addPageTemplates(PageTemplate(id="main", frames=[frame], onPage=footer))
story = []

def add(*items):
    story.extend(items)


def newpage():
    story.append(PageBreak())


# 1 - Cover
add(Spacer(1, 104))
cover = Table([[P("InterviewEdge", "cover_title")],
               [P("AI-powered placement preparation for students", "cover_sub")]],
              colWidths=[CONTENT])
cover.setStyle(TableStyle([
    ("BACKGROUND", (0, 0), (-1, -1), NAVY),
    ("LEFTPADDING", (0, 0), (-1, -1), 23),
    ("RIGHTPADDING", (0, 0), (-1, -1), 23),
    ("TOPPADDING", (0, 0), (0, 0), 28),
    ("BOTTOMPADDING", (0, 0), (0, 0), 11),
    ("TOPPADDING", (0, 1), (0, 1), 0),
    ("BOTTOMPADDING", (0, 1), (0, 1), 29),
]))
add(cover, Spacer(1, 27))
add(P("PROJECT REPORT + PRESENTATION BRIEF", "eyebrow"))
add(P("Prepared for the 7 October 2026 presentation. Based on repository documentation and current source code checked on 6 October 2026."))
add(Spacer(1, 10))
add(grid(["PRODUCT", "CURRENT BUILD", "PRESENTATION FOCUS"], [[
    "Students practise interviews, computer-science tests and spoken English in one app.",
    "Java/XML Android client; local Python/FastAPI, PostgreSQL, Ollama and whisper.cpp services.",
    "Demonstrate a five-question test; explain AI scoring, progress, safeguards and remaining validation."
]], [CONTENT*.30, CONTENT*.34, CONTENT*.36]))
add(Spacer(1, 25), callout("Key message", "One cycle connects practice, evidence-based feedback, a shared competency profile and the next recommended activity."))
newpage()

# 2 - At a glance
add(*title("01 / Executive overview", "What InterviewEdge does",
           "A student-facing placement practice application with one shared record of strengths and gaps."))
add(P("InterviewEdge serves students preparing for internships and entry-level software roles. The application brings together technical/HR interview practice, DSA/DBMS/OS tests and English self-introduction practice. Each activity produces saved feedback and, when eligible, updates progress and recommendations. [S1-S3]"))
add(P("The product loop", "h2"))
add(grid(["1. Choose", "2. Practise", "3. Review", "4. Improve"], [[
    "Select a role, topic or prompt.", "Answer a question, take a test or record speech.",
    "See a score, evidence and specific feedback.", "Inspect progress and take the suggested next step."
]], [CONTENT*.24, CONTENT*.25, CONTENT*.26, CONTENT*.25]))
add(P("What is built today", "h2"))
for text in [
    "Local username/password accounts and recovery codes; profile, goals, consent, export and deletion.",
    "Android screens for Home, Practice, Progress and Profile, plus interview, test, recording and report flows.",
    "FastAPI endpoints, PostgreSQL migrations and jobs, starter content, local model scoring and transcription adapters.",
    "Scoring API with authenticated request/status endpoints, server-owned rubrics and retries.",
]: add(bullet(text))
add(Spacer(1, 7), callout("How to frame the result", "The system is a working local prototype with tested backend journeys. It is not yet the final faculty-reviewed release; content review, wider AI calibration and full emulator accessibility testing remain." , PALE_AMBER))
add(P("One-minute explanation", "h2"))
add(P("'InterviewEdge lets a student practise three placement skills in one place. The Android app talks to a Python service on the laptop. That service saves attempts in PostgreSQL, checks objective answers against reviewed keys, and uses a local AI model for open-ended feedback. Eligible results update a common progress score and trigger focused recommendations.'"))
newpage()

# 3 - goals / scope
add(*title("02 / Problem and boundaries", "Why the project exists",
           "Students often practise technical knowledge, interviewing and speaking in separate tools, making progress hard to connect."))
add(P("The proposed benefit is a common competency profile. For example, a weak SQL explanation in an interview and a missed DBMS question can both contribute to the student's SQL practice needs. The present recommendation engine is rule-based; the product does not claim to predict placement outcomes. [S1-S3]"))
add(P("Audience and role presets", "h2"))
add(grid(["Audience / role", "Initial focus"], [
    ("Student user", "Practice, review feedback, track goals and choose the next activity."),
    ("Java Developer", "Java language, object-oriented programming, collections, exceptions and SQL."),
    ("Backend Developer", "APIs, HTTP, SQL, transactions and backend design."),
    ("Software Engineer", "DSA, complexity, object-oriented programming, DBMS and OS."),
    ("Content administrator", "Review, publish, retire and correct the question bank; review provisional answers."),
], [CONTENT*.31, CONTENT*.69]))
add(P("Release boundary", "h2"))
add(P("The current scope uses recorded voice one answer at a time. Resume-based interviews, uninterrupted voice chat, adaptive tests, student code execution, recruiter dashboards and institution analytics are future work. The architecture keeps role, content, media and AI providers replaceable. [S1-S3]"))
add(callout("Decision change during development", "The original design mentioned Firebase and a Java/Spring backend. The implemented project uses local PostgreSQL accounts with recovery codes and a Python/FastAPI backend. The former Java backend remains archived for reference; it is not the runtime service."))
newpage()

# 4 - architecture
add(*title("03 / System design", "How the pieces connect",
           "A native app sends authenticated requests to one local backend, which owns business rules, persistence and AI access."))
add(Architecture())
add(P("Responsibilities", "h2"))
add(grid(["Layer", "Responsibility"], [
    ("Android / Java XML", "Screens and navigation; Retrofit/OkHttp requests; Room cached reports and drafts; WorkManager retries; on-device PCM recording."),
    ("FastAPI / Python", "Authentication, ownership checks, activity rules, verified test scoring, AI response validation, progress calculations and admin APIs."),
    ("PostgreSQL", "Accounts, content versions, attempt/answer state, durable jobs, competency evidence, snapshots and audit records."),
    ("Ollama + whisper.cpp", "Local Qwen3:4b text evaluation/follow-ups and local English speech transcription; media files stay in private laptop storage."),
], [CONTENT*.28, CONTENT*.72]))
add(P("The emulator's localhost is not the laptop's localhost. <b>ADB reverse</b> connects its port 8080 to FastAPI on the laptop. Authentication and objective tests can work without AI, but open-ended evaluations require the local model; voice also requires transcription. [S2, S3]"))
newpage()

# 5 - experience
add(*title("04 / Student experience", "What a student sees",
           "The interface favors one task at a time on a small phone display."))
add(grid(["Area", "Purpose and visible flow"], [
    ("Login / profile", "Register or sign in; save eight recovery codes; set target role, skills and weekly goal; accept practice consent."),
    ("Home", "Greeting, circular Practice score (or 'Start your baseline'), weekly goal and one recommended activity."),
    ("Practice", "Three large paths: mock interview, subject test and self-introduction."),
    ("Progress", "Module scores, competency scores and freshness, snapshots, history and up to three reasoned recommendations."),
    ("Report / privacy", "Answer-level feedback, retry or review; export account data, change password, set retention or delete account."),
], [CONTENT*.23, CONTENT*.77]))
add(P("Three primary journeys", "h2"))
add(bullet("Interview: choose role, skills, type, difficulty, answer mode and duration -> answer turns -> AI feedback/follow-ups -> finish -> report."))
add(bullet("Test: choose DSA/DBMS/OS, difficulty and length -> save answers under a server deadline -> submit -> see verified solutions and scores."))
add(bullet("English: read the self-introduction prompt -> record -> review the transcript -> submit -> see grammar/clarity/relevance feedback -> retry."))
add(Spacer(1, 7), callout("Dashboard score", "The ring represents performance out of 100. Weekly completion is displayed separately. A new account has no overall number until all three modules have eligible evidence. It is an educational practice indicator, not a hiring prediction."))
newpage()

# 6 - interview
add(*title("05 / Module 1", "AI mock interview",
           "A role-specific session with technical, HR or mixed questions and answer-aware follow-ups."))
add(P("The student chooses Java Developer, Backend Developer or Software Engineer; topic skills; easy, medium or hard; text or recorded voice; and a 10-, 20- or 30-minute answer budget. Session state and answer timing are stored on the server. AI processing time does not consume the answer budget; a wall-clock cap limits abandoned sessions. [S3, S5]"))
add(P("What happens after a text or transcribed answer", "h2"))
for x in [
    "The answer is accepted once with a submission key and stored against the current turn.",
    "A PostgreSQL job asks local Ollama to evaluate the answer against a server-held reference and rubric.",
    "For a scorable answer, the model may ask a distinct follow-up about the student's claim, with at most two follow-ups per topic.",
    "If follow-up generation fails, the saved evaluation is kept and the session can continue with reviewed seed content.",
    "The report contains question-by-question feedback, evidence excerpts, strengths, improvements and a practice score when scorable answers exist.",
]: add(bullet(x))
add(P("Interview rubric", "h2"))
add(grid(["Technical", "HR"], [[
    "Correctness 40%; reasoning 30%; clarity 20%; relevance 10%.",
    "Relevance 30%; structure 25%; clarity 30%; reflection 15%."
]], [CONTENT*.5, CONTENT*.5]))
add(Spacer(1, 10), callout("Limitation to state", "The role presets and three difficulty choices exist, but difficulty-specific interview behavior needs further validation. The included 12 interview prompts are starter content, not the final faculty-reviewed prompt bank.", PALE_AMBER))
newpage()

# 7 - tests
add(*title("06 / Module 2", "Subject and DSA tests",
           "Verified content determines objective marks; AI supports open-ended explanations and short-answer feedback."))
add(P("The content catalogue covers DSA, DBMS and Operating Systems. The engine supports MCQ, code-output and short-answer items, topic selection, easy/medium/hard levels, fixed test lengths of 5/10/15, answer saving, review markers and a server-owned deadline. Active test responses omit the answer key. [S3, S5]"))
add(P("Scoring and review", "h2"))
add(bullet("MCQ and code-output responses are compared with the published answer key. No AI call is required for these marks."))
add(bullet("A short answer is evaluated against a server-held reference and review criteria using correctness 75% and clarity 25%."))
add(bullet("A successful AI short-answer mark is provisional and does not update progress until an administrator reviews it."))
add(bullet("If AI is unavailable or the answer cannot be scored, the item remains pending with null points; objective subtotals remain visible."))
add(P("Content and current demo reality", "h2"))
add(grid(["Implemented", "Current limitation"], [[
    "45 published starter objective questions and 12 starter interview prompts are present. The database supports immutable question versions and reviewed publication.",
    "The starter bank has five selectable questions per subject and difficulty. A 10- or 15-question test at one difficulty will report insufficient content until the bank is expanded. Use a five-question test in the demo."
]], [CONTENT*.5, CONTENT*.5]))
add(Spacer(1, 10), callout("What to demonstrate", "Start a five-question DSA test, answer the questions, submit, open the reviewed explanation and show the updated Test module score and recommendation."))
newpage()

# 8 - english
add(*title("07 / Module 3", "English speaking practice",
           "The first activity is a recorded self-introduction with transcript-based feedback and retry comparison."))
add(P("The prompt asks for an introduction, one project, the student's contribution and a career goal. The student records up to two minutes of audio, plays it back, reviews the transcript generated by whisper.cpp and submits the confirmed text. Individual files use mono 16 kHz 16-bit WAV; private media storage and user ownership checks protect playback. [S2, S3, S5]"))
add(P("Feedback available today", "h2"))
add(grid(["AI-scored text dimensions", "Recorded-text metrics"], [[
    "Grammar 30%; clarity 35%; relevance 35%. Feedback includes verbatim evidence, strengths, improvements and suggested wording.",
    "Word count, approximate words per minute and filler count come from the raw transcript; the report flags any edits made to the confirmed transcript."
]], [CONTENT*.54, CONTENT*.46]))
add(P("The user can try the same prompt again and compare a later score with the earlier attempt. Retry grouping prevents immediate repeats from inflating the overall practice index. The scorer does not claim to measure pronunciation, accent, detailed pauses or voice quality from text. [S2, S3]"))
add(P("Voice pipeline", "h2"))
add(code([
    "Record WAV -> authenticated upload -> durable transcription job",
    "-> raw transcript -> student confirmation -> AI evaluation job",
    "-> report + comparison -> eligible competency evidence",
]))
add(Spacer(1, 10), callout("Limitations to state", "The first English activity is self-introduction. Broader accent/noise validation and full emulator microphone interruption testing are still release work. Speech quality is not inferred from transcript text.", PALE_AMBER))
newpage()

# 9 - AI scoring
add(*title("08 / AI scoring design", "How a score is produced",
           "The model supplies bounded rubric dimensions and feedback; the backend validates them and calculates the number."))
add(P("Ollama runs the local <b>qwen3:4b</b> model with structured JSON output. A dimension is rated 0 (incorrect/absent), 1 (major gaps), 2 (partly correct), 3 (mostly correct) or 4 (complete). The backend applies fixed, versioned weights and calculates <b>sum(dimension rating x weight) x 25</b>. [S2, S3]"))
add(grid(["Assessment", "Dimensions / weights"], [
    ("Technical", "Correctness .40; reasoning .30; clarity .20; relevance .10"),
    ("HR", "Relevance .30; structure .25; clarity .30; reflection .15"),
    ("English", "Grammar .30; clarity .35; relevance .35"),
    ("Short answer", "Correctness .75; clarity .25"),
], [CONTENT*.31, CONTENT*.69]))
add(P("Example: technical ratings 4, 2, 3, 4 produce (4x.40 + 2x.30 + 3x.20 + 4x.10)x25 = <b>80/100</b>. The numbers shown to students are practice ratings, not validated placement probabilities."))
add(P("Validation and failures", "h2"))
for x in [
    "Evidence must be copied from exact spans of the submitted answer. Extra fields, invalid types, non-finite values and ratings outside 0-4 are rejected.",
    "The model is instructed to grade understandable but wrong answers as low-scoring, not exclude them as unscorable.",
    "Unintelligible or unrelated answers can be unscorable with a null score. Missing AI is a failed/pending job, not an invented zero.",
    "Rubric and scoring versions, model identifier and evaluation time are stored with feedback; repeat requests reuse the accepted job/report.",
]: add(bullet(x))
newpage()

# 10 - progress
add(*title("09 / Shared progress", "From three modules to one profile",
           "Only eligible completed work contributes to the practice summary."))
add(ScoreVisual())
add(Spacer(1, 9), P("The current formula is <b>40% Interview + 40% Tests + 20% English</b>. The example above gives 72.0/100. The application shows no overall score until each module has eligible evidence, and initially labels it an 'Early estimate.' [S1-S3]"))
add(P("Competency evidence", "h2"))
add(P("Each eligible activity can update one or more topic-level competencies. The backend uses the latest five eligible activity contributions per competency; immediate retries with the same group contribute once. It stores historical snapshots, last assessment dates and a flag for evidence older than 30 days. Regrading replaces previous evidence rather than adding a duplicate. [S3]"))
add(P("Personalized recommendations", "h2"))
add(P("Rules first ask the student to build any missing module baseline. They then sort weaker competencies into up to three practice suggestions, each with a reason and a destination module. A suggestion can be dismissed for three days. The Home screen surfaces one next action. These are explainable rules, not a learned recommendation model. [S3, S5]"))
add(callout("Interpretation", "The practice score combines recent assessed performance. The weekly goal measures completed sessions. Evidence coverage measures how much practice supports the number. Keep these three ideas separate when explaining the dashboard."))
newpage()

# 11 - data
add(*title("10 / Data and persistence", "How records relate",
           "The schema uses activity as the shared anchor for tests, interviews and English attempts."))
add(grid(["Entity group", "Key role"], [
    ("edge_user, local_account, login_session, recovery_code, profile", "Local identity, password/session hashes, recovery and student preferences."),
    ("role_catalog, topic, question, interview_seed", "Role presets, subject/topic taxonomy, versioned questions and interview seed prompts."),
    ("activity, test_attempt, test_item", "Common activity record and saved test deadline, response, score and explanation."),
    ("interview_session, interview_turn", "Answer-time budget, server session state, prompts, saved answers and evaluations."),
    ("media, english_attempt", "Private recording metadata, raw/confirmed transcripts and retry links."),
    ("job, progress_activity, competency_evidence, progress_snapshot", "Durable processing, eligible module scores, competency contribution and trend history."),
], [CONTENT*.43, CONTENT*.57]))
add(P("Six SQL migrations build the current schema and starter catalogue. Primary/foreign keys tie module records to an owning user and activity; unique keys help prevent duplicate submissions and progress entries. Question correction creates a new version. The Python migration runner recognizes earlier Flyway history, so it can use existing data from the former Java backend. [S3, S4]"))
add(P("Recordings remain on the laptop filesystem behind authenticated media endpoints; the phone keeps a local Room cache for reports and drafts. Database backups and media backups must be kept together. [S3]"))
newpage()

# 12 - API/security
add(*title("11 / API and security", "The backend controls every score",
           "The phone cannot submit a score, answer key, reference answer or rubric weight."))
add(grid(["Endpoint family", "Purpose"], [
    ("/api/v1/auth and /api/v1/me", "Register/login/recover, profile, export and delete."),
    ("/api/v1/catalog", "Roles, skills, subjects and activity options."),
    ("/api/v1/tests/attempts", "Create, save, submit and review subject tests."),
    ("/api/v1/interviews", "Create, answer, finish and review interview reports."),
    ("/api/v1/media and /api/v1/english/attempts", "Upload/own recordings, transcribe, submit and retrieve speaking feedback."),
    ("/api/v1/activities/{id}/scoring", "POST {} to request or retry scoring; GET to poll state, score and report."),
    ("/api/v1/jobs, /dashboard, /progress, /admin", "Job retry, student summaries and restricted content review."),
], [CONTENT*.40, CONTENT*.60]))
add(P("The scoring endpoint returns HTTP 202 for queued work and HTTP 200 for a reusable completed result. Its states include NOT_REQUESTED, QUEUED, RUNNING, FAILED, COMPLETED and NEEDS_REVIEW. A POST reuses the same job; the client polls GET. At most five processing attempts are allowed. [S2, S3]"))
add(P("Security essentials", "h2"))
add(P("Passwords use bcrypt; session and recovery tokens are stored as hashes. Sessions expire after seven days; recovery rotates codes and revokes old sessions. Authenticated ownership checks guard activity, reports and media IDs. Administrator access is configured server-side. The local development services bind to loopback, and Android uses a development port forward. HTTPS and deployment-specific settings are needed for ordinary network use. [S3]"))
newpage()

# 13 - testing
add(*title("12 / Verification and measurements", "What has been tested",
           "Tests prove defined software behavior; broader educational and device validation remains open."))
add(grid(["Evidence", "Result and scope"], [
    ("Automated Python suite", "52 passed, 7 live checks skipped in the standard run. Includes account recovery, ownership, duplicate jobs, retry limits, AI schema checks, review races, score replacement and all role/subject combinations."),
    ("Opt-in live Ollama checks", "7 passed with synthetic inputs: technical, HR, English, short answer, prompt-injection rejection, correct/partial/wrong ordering, and an end-to-end scoring endpoint path."),
    ("Android build", "assembleDebug and lintDebug passed before the latest scoring-only backend changes; full emulator journeys still need a final run."),
    ("Historical speech feasibility", "whisper.cpp base.en transcribed an ~11-second reference clip; measured 10.23 s process wall time and ~301.6 MiB sampled peak working set."),
], [CONTENT*.34, CONTENT*.66]))
add(P("One live scoring comparison returned <b>85</b> for a correct technical answer, <b>67.5</b> for a partial answer and <b>0</b> for an incorrect answer. A five-item endpoint check combined four correct objective answers and one 75-point AI short answer for <b>95</b>; this result remained provisional until review. Individual synthetic outcomes are not a claim of measurement validity. [S6]"))
add(Spacer(1, 7), callout("Release-quality work still required", "Calibrate at least 60 reviewer-scored examples; test several speakers, accents and noise conditions; complete small-screen/accessibility and interruption checks; verify backup/restore and a full faculty walkthrough.", PALE_AMBER))
newpage()

# 14 - demo
add(*title("13 / Tomorrow's walkthrough", "A practical demo plan",
           "Use verified paths and have a recovery line ready if local services are slow."))
add(grid(["Time", "Show / say"], [
    ("0:00-1:00", "Problem, student audience and three-module practice loop."),
    ("1:00-2:00", "App navigation and the empty/early dashboard score; explain that 40/40/20 is a practice index."),
    ("2:00-4:00", "Profile and a five-question DSA test. Submit and show explanations and progress update."),
    ("4:00-6:00", "Interview setup and one text answer. Show queued evaluation, follow-up or saved report."),
    ("6:00-7:30", "English self-introduction recording/transcript/report, if microphone and transcription have been prechecked."),
    ("7:30-9:00", "Architecture, data ownership, scoring safeguards and measured tests."),
    ("9:00-10:00", "Known gaps, faculty review plan and questions."),
], [CONTENT*.22, CONTENT*.78]))
add(P("Start local services before opening the emulator", "h2"))
add(code([
    "# PowerShell, from the project root",
    ".\\scripts\\start-local-ai.ps1",
    ".\\scripts\\start-local.ps1 -UseWorkspaceDatabase",
    "# In a second terminal after the emulator boots",
    ".\\scripts\\connect-emulator.ps1",
]))
add(P("Then choose the <b>android</b> project in Android Studio, select the <b>app</b> run configuration and the running emulator, and press Run. Confirm http://127.0.0.1:8080/health on the laptop first. The backend terminal must remain open. [S3]"))
add(callout("Demo safeguards", "Use a five-question test at one difficulty. Keep a completed report ready in case model loading is slow. Do not present an illustrative report as a live result. Check recording permission and local AI/transcription before the audience arrives.", PALE_AMBER))
newpage()

# 15 - risks, questions
add(*title("14 / Honest answers", "Questions the panel may ask",
           "Short responses that make the project clear without overstating the current build."))
add(P("Why Python rather than Spring Boot?", "h3"))
add(P("The team chose FastAPI for a smaller backend codebase and faster iteration. The Android app remains native Java; PostgreSQL preserves the data. The old Java server is archived, and Python now owns its migrations and admin assets."))
add(P("Why PostgreSQL if Android already has SQLite?", "h3"))
add(P("SQLite/Room is useful for one device's drafts and cached reports. PostgreSQL holds accounts, shared content, jobs and authoritative scores on the laptop, so a device change does not redefine the user's records."))
add(P("Can students manipulate AI scores?", "h3"))
add(P("Requests use stored answers and server-held references. The model returns rubric dimensions, then the backend validates type, bounds and exact evidence before computing the score. Provisional short-answer marks wait for review."))
add(P("Is it fully offline or free?", "h3"))
add(P("The local setup uses no paid services. The emulator still needs a connection to the laptop service. Once installed, local account operations do not need Firebase or public internet."))
add(P("Is the score a placement probability?", "h3"))
add(P("No. It is a weighted practice summary. Coverage, recency and review status matter, and broader scoring calibration has not yet been completed."))
add(P("What remains before faculty review release?", "h3"))
add(P("Expand and independently review the content bank; run reviewer-scored AI evaluation; test voice and UI on the emulator; validate backups, retention, score-version comparisons and complete account controls."))
newpage()

# 16 - sources / provenance
add(*title("15 / Source notes", "Where these facts come from",
           "Repository files reviewed on 6 October 2026. The product blueprint records the original intent; current implementation and status files take precedence."))
sources = [
    ("S1 - Product intent", "design/InterviewEdge-Project-Blueprint.md; earlier core application plan in the project conversation. Original Firebase/Spring suggestions are historical."),
    ("S2 - Scoring contract", "docs/SCORING-API.md; backend-python/app/ai.py; backend-python/app/scoring.py; backend-python/tests/test_ai.py; backend-python/tests/test_live_scoring.py."),
    ("S3 - Current implementation", "README.md; docs/SETUP.md; docs/IMPLEMENTATION-STATUS.md; backend-python/app/main.py; backend-python/app/security.py; android/app/src/main/java/com/interviewedge/."),
    ("S4 - Database and content", "backend-python/migrations/V1__core.sql through V6__starter_content.sql; backend-python/app/db.py."),
    ("S5 - Mobile UI and journeys", "android/app/src/main/java/com/interviewedge/ui/screens/; android/app/src/main/java/com/interviewedge/ui/ScoreRing.java; android/app/build.gradle."),
    ("S6 - Verification", "docs/BENCHMARKS.md; backend-python/tests/test_scoring_api.py; backend-python/tests/test_core.py; Android build/lint status in docs/IMPLEMENTATION-STATUS.md."),
]
add(grid(["Reference", "Repository path / interpretation"], sources, [CONTENT*.30, CONTENT*.70]))
add(P("Source hygiene", "h2"))
add(P("The report distinguishes current code, verified tests, historical feasibility readings and proposed release goals. Example scores in diagrams are illustrative unless explicitly described as recorded test outcomes. UI concept images from the original blueprint are not claimed to be the present app."))
add(P("Suggested final line", "h2"))
add(callout("Close", "'InterviewEdge already connects practice, scoring and progress across three skill areas. Our next step is to validate its content and AI feedback with reviewers, then finish device and accessibility testing before calling it a release.'"))

doc.build(story)
print(OUT)
