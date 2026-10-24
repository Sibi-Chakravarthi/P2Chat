# 🚨 EMERGENCY STABILIZATION OVERRIDE (HIGHEST PRIORITY — OVERRIDES ALL BELOW RULES & PRD)
You are operating in **Emergency Bug-Fix & Simplification Mode**. Where any rule below, in `PRD.md`, or in the Implementation Plan conflicts with these 5 directives, **THESE DIRECTIVES WIN**:

1. **Full Refactor Authority:** You have explicit permission to delete dead files, collapse redundant managers/repositories, and simplify state machines without stopping for commit-granularity checks, branch rules, or architectural approval.
2. **Group Chat Key Simplification:** Do NOT let pairwise ECDH key-rotation failures block group messaging. Use a single deterministic or directly provisioned shared Group AES-256-GCM key embedded in the group invite/sync payload so all members in a group stay permanently in sync even if peers disconnect and reconnect.
3. **Resilient BLE Framing & GATT Queueing:** You may modify packet chunking/reassembly and GATT transport code so it works reliably whether MTU is 512 bytes (478-byte frame + 24-byte header) or falls back to smaller chunk sizes. Enforce a single sequential `Mutex`/Channel queue per `BluetoothGatt` connection.
4. **Non-Blocking TFLite Moderation:** Wrap the `all-MiniLM-L6-v2` TFLite receiver-side filter in a safe `runCatching` with a 500ms timeout. If TFLite fails or times out, allow the decrypted message through to Room DB so AI moderation NEVER drops a chat message.
5. **Strict Directory Ignore:** Never read or scan `.git/`, `.gradle/`, `build/`, `app/build/`, `.venv/`, or `*.tflite` binary files. Only operate on `app/src/main/`.

Read AGENTS.md, docs/PRD.md and docs/IMPLEMENTATION_PLAN.md.
Implement task T0.1 only. Respect "Touches" and "Not in this task".
Plan first and wait for my approval before editing.
Run the verify command and report each "Done when" item.