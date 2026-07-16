
---

# ① TSP v1 Draft 1（协议文档）

```markdown
# Translex Style Protocol (TSP)
Version: Draft 1

## 1. Overview

TSP (Translex Style Protocol) is a lightweight rich-text transmission protocol designed for AI-assisted translation.

Its purpose is to preserve text styles while allowing LLMs to freely reorder sentence structures.

TSP binds styles to content instead of positions.

---

## 2. Design Goals

G1. Style follows content, never position.

G2. LLM does not understand styles.

G3. Protocol is independent of Minecraft.

G4. Protocol is deterministic.

G5. Protocol is self-validating.

G6. Decoder can recover gracefully from malformed input.

---

## 3. Style Registry

Style information is never sent to the LLM.

Each translation request owns a local Style Registry.

Example:

ID -> Style

0 -> Gray
1 -> Green
2 -> Aqua
3 -> Gold

The registry only exists during encoding/decoding.

Encoder should deduplicate identical Style objects.

---

## 4. Token Format

Draft 1 uses the following syntax:

[[ID||TEXT]]

Example:

Gain [[1||56%]] chance to receive [[2||❄ Cold]].

The LLM may freely move tokens.

The LLM must never modify:

- opening brackets
- closing brackets
- separator
- ID

Only TEXT should be translated.

---

## 5. Encoding

Encoder traverses styled text.

For each style segment:

- lookup Style Registry
- allocate ID if needed
- output token

Plain text remains unchanged.

---

## 6. Decoding

Decoder receives:

- encoded text
- local Style Registry

Decoder reconstructs styled text by replacing each token with the corresponding Style.

Unknown IDs fall back to Style.EMPTY.

---

## 7. Parser

Parser validates:

- balanced delimiters
- numeric IDs
- valid separator ||
- no nested tokens

Parser reports parse errors instead of crashing.

---

## 8. Recovery

Draft 1 recovery is intentionally simple.

Malformed tokens are treated as plain text.

Parser continues parsing remaining content.

No semantic recovery is implemented.

---

## 9. Determinism

Given identical input:

Encoder must always generate identical output.

Style IDs are assigned according to first appearance order.

Style deduplication should preserve insertion order.

---

## 10. Non Goals

TSP does not define:

- Prompt design
- Translation quality
- OpenAI integration
- Minecraft rendering
- Cache implementation
- Network protocol

TSP only defines style transmission.

---

## 11. Future Extensions

Possible future versions may support:

- Bold
- Italic
- Click Events
- Hover Events
- Fonts
- Escape sequences
- Protocol version headers

These are outside Draft 1.
```
