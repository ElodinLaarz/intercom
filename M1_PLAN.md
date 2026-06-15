# M1 — Tracer Bullet: design + todo

**Drafted 2026-06-14** (M0 closed the same day — see STATUS.md). **Design-gated:
review §2 with the owner before writing voice code.**

Companions: [V2_PLAN.md](V2_PLAN.md) §5 (M1), §4 (architecture), Appendix A
(landmines). [STATUS.md](STATUS.md) is the only status that counts.

---

## 0. Goal & gate

One-directional live voice, **guest → host**, over BLE. The two debug buttons
(Host / Join — the M0 stub buttons, now wired) start each role. No real UI, no
foreground service, screen on.

**Gate (V2_PLAN §5):** 120 s of continuous one-way voice; `tools/smoke` T1
asserts **rx rate == tx rate ±5%**, **jitter depth bounded**, **zero
disconnects**; a human confirms it sounds like a voice. Then update STATUS and
close M1.

Out of scope in M1 (later milestones): PTT, duplex, reconnect storms (M2);
service / screen-off / secure-bonded / real UI (M3); headset (M4); release (M5);
shared media (M6).

## 1. Module boundaries (V2_PLAN §4)

| Module | Lang | Owns |
|---|---|---|
| `ui/` | Compose | Host/Join buttons → session intents. Owns nothing. |
| `session/` | Kotlin | State machine, **single owner of link state**, single-threaded dispatcher, Epoch lifecycle. (No `ForegroundService` until M3.) |
| `radio/` | Kotlin | advertiser, scanner, GATT server/client, **the L2CAP `BluetoothSocket`**. |
| `audio/` | C++/JNI | Oboe in/out, ADPCM enc/dec, jitter buffer, ring. DSP only — no BLE. |
| `proto/` | C++ + gen Kotlin | `constants.h` (single source) + voice-frame codec. |

**Cross-boundary rule (M0 rule 3 — one owner per datum):** the L2CAP socket is
Kotlin-owned; C++ produces/consumes frame **bytes** over JNI, Kotlin does the
socket I/O. Audio callback (real-time) threads never touch session state — they
communicate through SPSC ring buffers (reuse the M0 `RingBuffer`).

## 2. Voice-path / ADPCM design — DRAFT, review before code

Locks the "codec before voice" decision (PR #8) into concrete numbers.

- **Capture:** 16 kHz mono PCM16. (Wideband speech. 8 kHz is the fallback if BLE
  throughput disappoints — a one-constant change.)
- **Codec:** IMA ADPCM, 4:1 (16-bit → 4-bit). ~64 kbps payload at 16 kHz. Fixed
  rate, no knobs (this is *why* ADPCM — deletes v1's adaptation-loop bug class).
- **Frame:** 20 ms = 320 samples → 160 ADPCM bytes. 50 fps.
- **Self-contained frames = M1 loss-resilience.** IMA ADPCM is stateful, so each
  frame carries its **initial predictor state**. A lost BLE frame then costs
  exactly one 20 ms gap — no decoder corruption, no cross-frame dependency. No
  FEC/redundancy in M1 (one-way open mic). PLC = silence-fill a gap (optional).
- **Wire frame** (little-endian; every field bounds-checked on decode — landmine #12):

  | field | type | notes |
  |---|---|---|
  | `epoch` | u32 | must match receiver's epoch or frame is dropped |
  | `seq` | u32 | per-epoch; RFC1982 compare via M0 `SeqFilter` |
  | `predSample` | i16 | IMA predictor initial value |
  | `stepIndex` | u8 | IMA step-table index, 0..88 (validate) |
  | `reserved` | u8 | = 0 (align) |
  | `adpcm` | u8[160] | 320 samples × 4 bit |

  Total **172 B/frame** → at 50 fps ≈ **68.8 kbps** incl. header (vs ~700 kbps
  on the 2M PHY). One frame per L2CAP write.
- **MTU:** request GATT MTU 517; L2CAP CoC MPS carries 172 B comfortably.
- **DIAG telemetry** (every 2 s, tag `INTERCOM`, parsed by smoke T1):
  `DIAG epoch=<n> txSeq=<n> rxSeq=<n> lost=<n> qDepth=<n> jitterMs=<n> underruns=<n>`
  (one-way: guest prints tx fields, host prints rx fields.)
- **Threading:**
  - Guest: Oboe input cb (RT) → ADPCM encode → SPSC ring → writer thread → L2CAP write.
  - Host: L2CAP reader thread → epoch+`SeqFilter` check → jitter buffer → Oboe
    output cb (RT) drains → ADPCM decode → play.
  - Session dispatcher owns link state; audio threads never call into it.
- **Epoch (rule 4):** monotonic int, bumped on each successful link-up. Every
  per-link object (SeqFilter, jitter buffer, codec state, rings) is constructed
  when the epoch begins and destroyed on disconnect. No `reset()`.

**Resolve at review:** jitter target depth (start ~3 frames ≈ 60 ms); PLC in M1
(default: silence-fill); 16 vs 8 kHz (default 16, confirm on hardware); confirm
the Kotlin-writes-socket boundary vs passing the fd to C++ (default: Kotlin).

## 3. Ordered steps (→ Forgejo `M1:` issues; respect deps)

1. **Design sign-off** — review §2, lock params. *(blocks 2–9)*
2. **proto: voice frame + codec** — add voice constants (rate, frame ms, header
   sizes) to `constants.h` → regen `Proto.kt`; Kotlin + C++ frame encode/decode
   with **bounds tests on every field** (#12). Host ctest + Kotlin test.
3. **radio: host advertise + GATT PSM** — MSD advertise, **minimal payload**
   (#13, moto canary); GATT server; publish the L2CAP PSM characteristic.
4. **radio: guest scan + GATT connect** — MSD-filtered scan + `continuousUpdates`
   (#2); GATT connect; MTU 517; `CONNECTION_PRIORITY_HIGH` **both sides** (#1);
   read PSM.
5. **radio: L2CAP CoC** — host listen (insecure for M1, §4.5), guest connect,
   epoch-scoped; push a test frame through; backoff ladder (#8).
6. **session: state machine + epoch + buttons** — single-owner dispatcher; Epoch
   lifecycle; wire the Host/Join stub buttons; connect backoff (#8).
7. **audio: guest capture→encode→send** — Oboe input, **comm-mode + route set
   before stream open** (#4); ADPCM encode; frame; hand bytes to radio.
8. **audio: host receive→decode→playout** — radio → JNI; `SeqFilter` +
   drift-tolerant jitter buffer + ADPCM decode → Oboe output: **Shared +
   Usage::VoiceCommunication + ContentType::Speech + PerformanceMode::None** (#3).
9. **DIAG + smoke T1** — 2 s DIAG lines both ends; extend `tools/smoke` to T1
   (120 s, parse DIAG, assert rx≈tx ±5%, jitter bounded, 0 disconnects).
10. **M1 gate** — run T1 on the rig; human confirms voice; update STATUS, close M1.

## 4. Landmines applied THIS milestone (Appendix A)

| # | Where | Step |
|---|---|---|
| #1 | `CONNECTION_PRIORITY_HIGH` both sides | 4 |
| #2 | MSD scan filter + `continuousUpdates` | 4 |
| #3 | Oboe out: Shared / VoiceCommunication / Speech / None | 8 |
| #4 | `MODE_IN_COMMUNICATION` + `setCommunicationDevice` before stream open | 7, 8 |
| #8 | bounded backoff ladder on every restart | 5, 6 |
| #12 | bounds-check every wire field | 2 (+ all decode) |
| #13 | minimal BLE adv payload (moto canary) | 3 |

## 5. Start here (fresh session)

1. Read this doc + V2_PLAN §4–5 + STATUS.md.
2. Confirm/adjust §2 with the owner — it's **design-gated**.
3. Pair the phones: Settings → Developer options → **Wireless debugging**;
   `adb pair <ip>:<pairport> <code>` then `adb connect`; `tools/smoke`
   auto-detects connected devices. (Rig: Pixel 10 Pro XL, moto g play 2024;
   reachable on LAN/Tailscale.)
4. Work top-down through the open `M1:` issues — **one feature in flight**; a PR
   per issue; **paste T1 smoke output in any radio/audio/native PR body** (repo
   rule 1).
5. M1 is **interactive** (human + rig in the loop). Agent-swarm work stays banned
   until the **M2** gate (rule 6).
