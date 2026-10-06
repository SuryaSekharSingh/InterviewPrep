import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const workspaceDir = "C:/Users/surya/Desktop/Project Dump/InterviewPrep";
const skillDir = "C:/Users/surya/.codex/plugins/cache/openai-primary-runtime/presentations/26.909.12148/skills/presentations";
const modulePath = "C:/Users/surya/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs";
const pythonExecutable = "C:/Users/surya/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe";
process.env.RUNTIME_NODE_MODULES = "C:/Users/surya/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules";
process.env.RUNTIME_NODE = "C:/Users/surya/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe";
const { Presentation, PresentationFile } = await import(pathToFileURL(modulePath).href);
const { finalizePresentation } = await import(pathToFileURL(path.join(skillDir, "container_tools/artifact_tool_utils.mjs")).href);

const W = 1280, H = 720;
const COLORS = {
  navy: "#112B31", ink: "#17343A", teal: "#00786F", mint: "#8FD8C6",
  paper: "#F4F8F6", white: "#FFFFFF", gray: "#4A6366", light: "#D6E5E0",
};
const FONT = "Arial";
const pres = Presentation.create({ slideSize: { width: W, height: H } });

function txt(slide, content, x, y, w, h, size, color, bold = false) {
  const box = slide.shapes.add({
    geometry: "textbox",
    position: { left: x, top: y, width: w, height: h },
    fill: "none", line: { fill: "none", width: 0 },
  });
  box.text = content;
  box.text.style = { typeface: FONT, fontSize: size, bold, color, autoFit: "none" };
  return box;
}
function slide(title, index, dark = false) {
  const s = pres.slides.add();
  s.background.fill = dark ? COLORS.navy : COLORS.paper;
  if (title) txt(s, title, 74, 60, 1130, 70, 40, dark ? COLORS.white : COLORS.ink, true);
  txt(s, String(index).padStart(2, "0"), 1164, 649, 48, 30, 16, dark ? COLORS.mint : COLORS.gray);
  return s;
}
function notes(s, text) {
  s.speakerNotes.textFrame.setText(text);
}

// 1 — 15 seconds
{
  const s = slide(null, 1);
  const appMark = await fs.readFile(path.join(workspaceDir, "design/presentation-assets/interviewedge-mark.png"));
  const nittEmblem = await fs.readFile(path.join(workspaceDir, "design/presentation-assets/nitt-official-emblem.png"));
  s.images.add({ blob: appMark, contentType: "image/png", alt: "InterviewEdge speech bubble and rising edge mark", fit: "contain", position: { left: 66, top: 77, width: 152, height: 152 } });
  s.images.add({ blob: nittEmblem, contentType: "image/png", alt: "Official emblem of National Institute of Technology Tiruchirappalli", fit: "contain", position: { left: 1034, top: 66, width: 176, height: 176 } });
  txt(s, "InterviewEdge", 238, 109, 708, 95, 69, COLORS.ink, true);
  txt(s, "Placement practice with feedback students can act on", 82, 291, 1100, 76, 31, COLORS.teal);
  txt(s, "Presented by", 82, 454, 550, 42, 20, COLORS.gray);
  txt(s, "Surya Sekhar Singh (205124098)", 82, 509, 980, 42, 28, COLORS.ink, true);
  txt(s, "Shivam Das (205124086)", 82, 556, 980, 42, 28, COLORS.ink, true);
  txt(s, "Faculty project presentation  ·  7 October 2026", 82, 641, 1030, 35, 18, COLORS.gray);
  notes(s, "0:00–0:15. I am Surya Sekhar Singh, presenting with Shivam Das. InterviewEdge is our Android placement preparation app. It brings interview practice, computer science tests and spoken English into one place. I will show the path from practice to feedback and a useful next step. Sources: InterviewEdge project report, pages 1–2; NIT Tiruchirappalli official emblem from https://www.nitt.edu/home/about/High-Resolution-Emblem.png. The institute owns the emblem; it is shown separately and unaltered.");
}

// 2 — 35 seconds
{
  const s = slide("The student problem", 2);
  txt(s, "Practice is scattered", 74, 168, 490, 75, 36, COLORS.teal, true);
  txt(s, "An interview answer, a DBMS test and a spoken introduction are often judged in separate tools.", 74, 270, 500, 190, 26, COLORS.ink);
  txt(s, "One record of progress", 684, 168, 500, 75, 36, COLORS.teal, true);
  txt(s, "InterviewEdge connects those attempts to a shared competency profile and recommends the next useful activity.", 684, 270, 500, 190, 26, COLORS.ink);
  txt(s, "For students preparing for entry-level software roles", 74, 573, 1060, 44, 22, COLORS.gray);
  notes(s, "0:15–0:50. Students usually practise technical knowledge, interview communication and spoken English separately. That makes it difficult to see whether, for example, a weak SQL interview answer and a missed DBMS question point to the same gap. InterviewEdge keeps those attempts in one profile. Its recommendation rules then suggest another relevant activity. The score is a practice indicator, not a prediction of placement. Source: project report, pages 2–3 and 10.");
}

// 3 — 50 seconds
{
  const s = slide("Three ways to practise", 3);
  const rows = [
    ["01", "Mock interviews", "Technical, HR or mixed questions. Students type or record answers, then receive follow-ups and an answer-level report."],
    ["02", "Subject tests", "DSA, DBMS and Operating Systems. Objective items use published answer keys; short answers enter a review workflow."],
    ["03", "Speaking practice", "Record a self-introduction, review its transcript, receive text-based feedback and compare a retry."],
  ];
  rows.forEach((r, i) => {
    const y = 165 + i * 163;
    txt(s, r[0], 76, y, 110, 65, 40, COLORS.teal, true);
    txt(s, r[1], 202, y, 360, 62, 31, COLORS.ink, true);
    txt(s, r[2], 582, y + 2, 605, 117, 23, COLORS.gray);
  });
  notes(s, "0:50–1:40. There are three modules. First, mock interviews can be technical, HR or mixed. A student chooses a role, difficulty, answer mode and time budget. The system stores each answer and can ask a follow-up tied to it. Second, subject tests cover DSA, DBMS and Operating Systems. Multiple-choice and code-output marks come from published keys; an AI-marked short answer stays provisional until review. Third, the English module starts with self-introduction. It records audio, transcribes it locally and gives feedback on the confirmed text. It does not claim to measure pronunciation from that transcript. Source: project report, pages 5–8.");
}

// 4 — 40 seconds
{
  const s = slide("A student journey", 4);
  const steps = [
    ["Choose", "Select a five-question DSA test"],
    ["Answer", "Save responses under a server deadline"],
    ["Review", "See marks and checked explanations"],
    ["Improve", "Open progress and the next suggestion"],
  ];
  steps.forEach((r, i) => {
    const y = 160 + i * 119;
    txt(s, String(i + 1), 76, y, 70, 56, 39, COLORS.teal, true);
    txt(s, r[0], 165, y, 260, 55, 31, COLORS.ink, true);
    txt(s, r[1], 455, y + 3, 730, 75, 25, COLORS.gray);
  });
  notes(s, "1:40–2:20. A practical demo starts with a five-question DSA test. The student selects a topic and difficulty, saves answers, and submits before the backend deadline. The app then shows a report with reviewed explanations. That completed activity updates the Test module and can change a recommendation. Five questions is deliberate: the current starter bank has five selectable questions per subject and difficulty, so longer tests need more reviewed content. Source: project report, pages 5, 7 and 14.");
}

// 5 — 50 seconds
{
  const s = slide("How interview scoring works", 5, true);
  txt(s, "Student answer", 76, 174, 290, 56, 29, COLORS.mint, true);
  txt(s, "Local AI rates the rubric", 397, 174, 425, 56, 29, COLORS.mint, true);
  txt(s, "Backend calculates the score", 827, 174, 405, 85, 29, COLORS.mint, true);
  txt(s, "Technical example", 76, 316, 400, 50, 25, COLORS.white, true);
  txt(s, "Correctness 4  ·  Reasoning 2  ·  Clarity 3  ·  Relevance 4", 76, 385, 1100, 55, 26, COLORS.white);
  txt(s, "Weighted result  =  80 / 100", 76, 468, 1000, 72, 43, COLORS.white, true);
  txt(s, "Relevant, scorable interview answers now earn at least 10 / 100.", 76, 579, 1130, 48, 22, COLORS.mint);
  notes(s, "2:20–3:10. For an open interview answer, the laptop runs Qwen3 through Ollama. The model returns ratings from zero to four for fixed rubric dimensions, plus feedback and evidence copied from the answer. The backend checks the response and computes the total. In this technical example, weighted ratings of four, two, three and four give 80 out of 100. We recently made the rubric more lenient: a relevant, understandable interview answer gets at least ten points through relevance, while correctness and reasoning remain separately rated. Unrelated or unintelligible answers stay unscored. If AI fails, the answer is saved for retry rather than shown as a false zero. Source: project report, page 9; current docs/SCORING-API.md and backend-python/app/ai.py.");
}

// 6 — 50 seconds
{
  const s = slide("Local architecture and data", 6);
  txt(s, "Android emulator", 74, 174, 345, 60, 32, COLORS.teal, true);
  txt(s, "Java screens, recording and a Room cache", 74, 263, 350, 135, 24, COLORS.ink);
  txt(s, "Python API", 461, 174, 330, 60, 32, COLORS.teal, true);
  txt(s, "Accounts, activity rules, scores, jobs and ownership checks", 461, 263, 335, 165, 24, COLORS.ink);
  txt(s, "Laptop services", 841, 174, 350, 60, 32, COLORS.teal, true);
  txt(s, "PostgreSQL, Ollama, whisper.cpp and private recordings", 841, 263, 345, 165, 24, COLORS.ink);
  txt(s, "The emulator connects to the laptop through an Android Debug Bridge port forward.", 74, 528, 1110, 78, 25, COLORS.gray);
  notes(s, "3:10–4:00. The native Android app is built in Java and XML. Room stores device drafts and cached reports. The Python FastAPI service on the laptop is authoritative: it owns accounts, deadlines, accepted answers, scoring and ownership checks. PostgreSQL stores content, attempts and jobs. Ollama handles local text evaluation, and whisper.cpp handles recorded speech transcription. Recordings stay in private laptop storage. Android Debug Bridge forwards the emulator’s port 8080 to the backend. Passwords and session tokens are hashed, and a user can access only their own attempts and media. This is a local prototype; network deployment requires HTTPS and deployment settings. Source: project report, pages 4, 11–12; README.");
}

// 7 — 40 seconds
{
  const s = slide("Progress and recommendations", 7);
  txt(s, "40%", 76, 189, 290, 96, 67, COLORS.teal, true);
  txt(s, "Interview", 79, 286, 300, 50, 29, COLORS.ink);
  txt(s, "40%", 460, 189, 290, 96, 67, COLORS.teal, true);
  txt(s, "Tests", 463, 286, 300, 50, 29, COLORS.ink);
  txt(s, "20%", 845, 189, 290, 96, 67, COLORS.teal, true);
  txt(s, "English", 848, 286, 300, 50, 29, COLORS.ink);
  txt(s, "An overall score appears after all three modules have eligible evidence.", 76, 423, 1115, 92, 30, COLORS.ink, true);
  txt(s, "Recommendations name a weak or missing area and suggest one next activity.", 76, 551, 1115, 62, 23, COLORS.gray);
  notes(s, "4:00–4:40. The Home screen combines eligible evidence into a practice score: forty percent interview, forty percent tests and twenty percent English. A new user sees no overall number until they have evidence from all three modules. The app also tracks topic competencies and when they were last assessed. It groups immediate retries so repeating the same prompt does not quickly inflate progress. Recommendations are simple, explainable rules: fill a missing baseline first, then suggest practice for weaker areas. Weekly completion is a separate goal metric. Source: project report, page 10.");
}

// 8 — 20 seconds
{
  const s = slide("Current status and next steps", 8, true);
  txt(s, "Working local prototype", 76, 168, 1080, 64, 39, COLORS.mint, true);
  txt(s, "Accounts, practice flows, reports and progress are implemented. Backend scoring and key API paths have automated checks.", 76, 258, 1080, 160, 27, COLORS.white);
  txt(s, "Next: review more content, calibrate AI feedback with faculty, and finish emulator and accessibility testing.", 76, 476, 1070, 120, 26, COLORS.white);
  notes(s, "4:40–5:00. This is a working local prototype, not yet a faculty-reviewed release. The key backend journeys and scoring rules have automated checks, and the Android app has built successfully. Before release, we need more independently reviewed questions, at least sixty reviewer-scored AI examples, and full emulator checks for recording interruptions, small screens and accessibility. Our goal is feedback students can understand and use for the next practice attempt. Source: project report, pages 13 and 15; docs/IMPLEMENTATION-STATUS.md.");
}

const staging = path.join(workspaceDir, "tmp/presentation-build");
const finalPath = path.join(workspaceDir, "output/presentation/InterviewEdge_5_Minute_Presentation_v2.pptx");
await fs.mkdir(staging, { recursive: true });
await fs.mkdir(path.dirname(finalPath), { recursive: true });
const candidatePath = path.join(staging, "candidate.pptx");
await (await PresentationFile.exportPptx(pres)).save(candidatePath);
for (let i = 0; i < pres.slides.items.length; i++) {
  const preview = await pres.export({ slide: pres.slides.items[i], format: "png", scale: 1 });
  await fs.writeFile(path.join(staging, `slide-${i + 1}.png`), new Uint8Array(await preview.arrayBuffer()));
}
const result = await finalizePresentation({
  workspaceDir,
  candidatePath,
  finalPath,
  pythonExecutable,
  integrityValidatorPath: path.join(skillDir, "container_tools/inspect_presentation_package_integrity.py"),
  layoutValidatorPath: path.join(skillDir, "container_tools/inspect_presentation_layout_geometry.py"),
  layoutArgs: ["--expected-slide-size-emu", "12192000,6858000", "--validate-heading-fit"],
  explicitTotalSlideCount: 8,
  requiredNativeTableOwnerSlides: [],
  requiredNativeChartOwnerSlides: [],
  fontPolicy: { basis: "design", families: [FONT] },
  verifyArtifactToolImport: true,
  receiptPath: path.join(staging, "validation-v2.json"),
});
console.log(JSON.stringify({ finalPath, result }, null, 2));
