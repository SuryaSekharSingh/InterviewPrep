# Local feasibility measurements

## Hardware

Observed laptop: Intel Core i7-13620H, 16 GiB RAM, NVIDIA GeForce RTX 4050 Laptop GPU. Available RAM varies with Android Studio, emulator and other applications.

## Speech transcription — 2026-09-19

| Item | Observation |
|---|---|
| Runtime | whisper.cpp v1.9.2, official Windows x64 CPU binary |
| Model | base.en, SHA-256 `a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002` |
| Input | ~11-second public JFK sample bundled by whisper.cpp |
| Threads | 4 |
| Process wall time | 10.23 seconds |
| CLI's internal total | 1.422 seconds |
| Sampled peak working set | 301.6 MiB |
| Exit code | 0 |
| Result | Reviewable transcript containing the expected speech |

The wall measurement includes process startup and monitoring. Memory was sampled every 100 ms; it is not a guaranteed peak or system-wide memory measurement. One clean reference clip does not establish accuracy across accents, technical vocabulary, silence or background noise.

Source sample: [whisper.cpp v1.9.2](https://github.com/ggml-org/whisper.cpp/tree/v1.9.2/samples). Runtime: [official release](https://github.com/ggml-org/whisper.cpp/releases/tag/v1.9.2). Model: [maintainer model repository](https://huggingface.co/ggerganov/whisper.cpp).

## Text model — 2026-09-20

Ollama v0.34.2 runs locally with `qwen3:4b` (Q4_K_M), thinking disabled, context 4096 and one loaded model. Model digest: `359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7`.

| Check | Observed time |
|---|---:|
| First structured technical evaluation | 47.07 seconds |
| Later warm structured evaluation | 5.16 seconds |
| Warm follow-up after prompt/schema correction | 0.87 seconds |
| Speech through the Java adapter | 1.42 seconds |

These are individual observations, not averages or latency guarantees. The first model request includes loading overhead; no separate cold-start measurement or model-memory measurement was made. The speech adapter used the same public sample described above.

The first follow-up copied the original question. The adapter now explicitly requests a new follow-up and rejects repeats after ignoring case, whitespace and punctuation. The rerun asked why LinkedList node traversal causes linear indexed access. Contract tests also cover accepting a distinct follow-up and rejecting a repeat. This guard does not detect every semantic paraphrase.

All three opt-in live-provider checks passed in the former Spring adapter after this correction. One synthetic technical answer does not establish grading quality: the initial run rated reasoning 2/4 and a later run rated it 4/4. Reviewer calibration and repeatability evaluation remain required. FastAPI now owns the production Ollama and whisper.cpp adapters; the observations above remain historical feasibility measurements rather than current acceptance results.

## Python scoring checks — 2026-10-06

Seven opt-in checks passed with local `qwen3:4b`, thinking disabled, an 8192-token context and schema-constrained output. The schema restricts evidence to literal excerpts from the student's submitted text. A revised prompt explicitly requires grading understandable incorrect answers rather than excluding them as unscorable.

| Synthetic assessment | Score | Observed duration |
|---|---:|---:|
| Technical explanation | 85 | 4.11 seconds |
| HR teamwork response | 88.75 | 5.30 seconds |
| English self-introduction | 75 | 4.75 seconds |
| Short-answer primary key definition | 93.75 | 3.95 seconds |

The additional checks verified rejection of an answer requesting full marks, ordering of correct/partial/incorrect technical answers (85 / 67.5 / 0), and the complete POST → queued job → AI short-answer feedback → GET report path. The endpoint's five-item fixture yielded 95 overall: four objective answers at 100 and one AI short answer at 75. The result remained provisional and excluded from progress pending review.

The first run found paraphrased evidence, which validation rejected; selecting from exact source spans resolved it. A subsequent comparison found an incorrect response marked unscorable; the clarified grading instructions resolved that case. The final seven-check suite passed. These observations cover synthetic samples, not overall educational validity, stability across prompts, speech quality or concurrent-emulator performance.

## Remaining evaluation

- Broader live interview, follow-up and evaluation coverage beyond the single synthetic example.
- Cold and warm latency, model identifier/digest, memory and queue behavior.
- At least 60 anchored reviewer-consensus examples across the four assessment kinds.
- Several consenting speakers, accents, noise levels and technical terms.
- Repeat combined Android emulator + backend + one heavy provider job on the review laptop.
