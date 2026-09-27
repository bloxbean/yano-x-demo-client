# Yano X 0.1.0-pre3 showcase: core chain inventory for a demo client

Research date: 2026-09-27. Scope: orders-chain, registry-chain, approvals-chain, balances-chain,
documents-chain, workflow-chain. Every claim cites a source; anything not verified is marked **UNVERIFIED**.

## 0. Sources and abbreviations

| Abbrev | Path |
|---|---|
| `SRC` | `/Users/satya/work/yano-cluster/analysis/pre3-refresh-20260926/source` (pre3 Yano X source) |
| `STD` | `SRC/state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib` |
| `CON` | `SRC/state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts` |
| `SHOW` | `SRC/examples/showcase/src/main/java/org/yanoproject/x/showcase` |
| `COMP` | `SRC/composition/runtime/src/main/java/org/yanoproject/x/composite` |
| `KIT` | `/Users/satya/work/yano-cluster/preprod-contabo/pre3-ops/demo-runtime/yano-x-jvm-0.1.0-pre3/examples/showcase` |
| `DEP` | `/Users/satya/work/yano-cluster/preprod-contabo/cluster-pre3/.yano-deploy/render-2e07ad85a539-33ff7bfe0550-r30/ansible` |
| `HOST` | `/Users/satya/work/bloxbean/yano` (host repo). The deployed host jar is `0.1.0-pre17` (`.../yano-x-jvm-0.1.0-pre3/yano-distribution-v1.json`, `yano.jar` MANIFEST `Implementation-Version: 0.1.0-pre17`). HEAD is 2 commits past tag `v0.1.0-pre17`, and `AppChainResource.java`, `AppChainSubsystem.java`, `AppStateMachine.java`, `AppSubmissionRejectedException.java` changed since. **All host line numbers below are from `git show v0.1.0-pre17:<path>`**, not HEAD. |
| `RES` | `HOST@v0.1.0-pre17:app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java` |
| `SUB` | `HOST@v0.1.0-pre17:runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java` |
| `ENG` | `HOST@v0.1.0-pre17:runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainEngine.java` |
| `FLT` | `HOST/app/src/main/java/org/yanoproject/app/api/appchain/AppChainApiKeyFilter.java` (unchanged since pre17) |
| `LIVE` | Read-only GETs against `https://node{0..4}-showcase.yano-x.io` on 2026-09-27 (UA `yano-demo-research/1.0`). No POST/PUT/DELETE was sent. |

## 1. Deployed topology (applies to all six chains)

- Chain index to id to state machine: `DEP/files/application-appchain.yml` lines 15-100:
  `chains[0]` orders-chain/ordered-log, `[1]` registry-chain/kv-registry (with `machines.kv-registry.value-format: utf8`, line 37-39),
  `[2]` approvals-chain/approvals, `[3]` balances-chain/balances (no `machines.balances.minter`), `[4]` documents-chain/doc-trail,
  `[5]` workflow-chain/showcase-composite (`machines.composite.preset: order-approval-outbox-v1`, `block.max-messages: 1000`,
  effects `max-per-block: 2`, `max-payload-bytes: 65536`, lines 73-100). All use `block.interval-ms: 1000`, `membership.mode: governed`,
  MPF profile `mpf-blake2b256-v1`.
- Members/threshold per chain: `DEP/templates/node.properties.j2` lines 13-94: 5 members, `threshold=4`, `sequencer.mode=fixed`,
  proposer `2d7152...782e` (node0). Anchor `mode=script`, `every-blocks=30`, `max-interval-minutes=60`.
- Host-to-member-key mapping (LIVE `GET /chains/approvals-chain/status` `.memberKey` on each host):

| Host | memberKey (the message "sender" when you submit there) | role |
|---|---|---|
| node0-showcase.yano-x.io | `2d71529f07cb0daf45830d24b9699ed572e4cd4aefa4847179f1b6a8d257782e` | proposer |
| node1-showcase.yano-x.io | `020924a9287af880c3815021a9634493525bf22196b1403a09e9b6f9d7d06455` | member |
| node2-showcase.yano-x.io | `4aae46d4b8e660d0b91def674ae22ae5f008523a820a486ddb3b5192760bbce4` | member |
| node3-showcase.yano-x.io | `a8d9dd49ba26bbde3a42e0314a1aba047b963d0ef25f9db46af8f8df5510451c` | member |
| node4-showcase.yano-x.io | `66e6a91e1a0b45c3cb3d3b0282ae6daa8363ff273d1bc3cf10d91118098d14e0` | member |

- Live consensus profile (LIVE status `.consensusProfile`): `maxMessageBytes 65536`, `maxBlockMessages 5000` (workflow-chain 1000),
  `maxBlockBytes 4194304`, `enforceSenderSeq false`; `poolCapacity 10000`. Blocks are only produced when there are messages
  (`ENG` 789-791: `if (candidates.isEmpty()) return;`), consistent with LIVE tips of 1-6 blocks.
- Live chain list also contains `registry-audit-chain` and `approval-audit-chain`, which are not in the deployed template. Out of scope; **UNVERIFIED** origin.
- Live `stateCommitment.genesisId` values differ from `state.genesis-id` in `application-appchain.yml` (for example orders-chain config
  `c2b9c92a...` and live `c6271b32...`). Not investigated. **UNVERIFIED** whether they are the same concept.

## 2. Identity and authorization model (all chains)

1. **The HTTP caller never signs.** `POST .../messages` calls `gateway.submit(topic, body)` (`RES` 593-629) and then
   `submitWithinGeneration` then `buildSigned` (`SUB` 2010-2044, 1684-1709). The node signs the envelope with its own member key:
   `sender = signer.publicKey()`, `senderSeq` from a node-local counter, `expiresAt = now + defaultTtlSeconds` (default 600 s,
   `HOST core-api/.../AppChainConfig.java` line 79).
2. **Only members may be senders.** Inbound envelopes whose sender is not in the member group are dropped:
   `"sender not in app-chain member list"` (`SUB` 1122-1126). So there are exactly 5 possible acting identities, one per node.
3. **Choosing the acting identity = choosing the node to POST to.** Kit docs say so explicitly: "submitting through a port uses that
   member as the application sender" (`KIT/docs/MASTER_DEMO_CURL.md` line 17). The kit's approval flow proposes via node 0 and approves via
   nodes 1 and 2 (`KIT/docs/MASTER_DEMO_CURL.md` 151-174; `KIT/showcase.sh` `approval_approve`, about line 1334). LIVE confirms: approvals-chain
   heights 2 and 3 have senders `020924a9...` and `4aae46d4...`.
4. **Message id** = `AppMessage.computeMessageId(chainId, topic, sender, seq, expiresAt, body)` (`SUB` 1694). Each submit gets a new id
   even for an identical body, so the API has no body-level idempotency.
5. **Application-level authorization per machine** is based only on `context.sender()`, the member key. See the per-chain sections.

### API keys (X-API-Key)

- Filter logic (`FLT` 90-184): if `yano.app-chain.api.auth.enabled` is false (the default, `FLT` 476-481), READ and SUBMIT pass
  without a key (`FLT` 150-152). PRIVILEGED always needs an unscoped full key. If none is configured the response is 503
  `AUTH_UNAVAILABLE` (`FLT` 129-146, 363-365). Unannotated GETs default to READ and unannotated non-GETs default to PRIVILEGED (`FLT` 186-204).
- Deployed config sets `yano.app-chain.api.keys=...` (`DEP/templates/node.properties.j2` line 12). **No `api.auth.enabled`** appears in
  `node.properties.j2`, `files/application-appchain.yml`, `files/yano-x.env` (only JAVA_OPTS) or `files/yano-x.service`.
  Therefore **reads and message submission are public (no key) on the live cluster**. This is source-derived. I did not send a POST to
  confirm submission, but LIVE GETs without a key all returned 200.
- LIVE: privileged GETs `/chains/orders-chain/identity` and `/chains/orders-chain/admin/members` return **401**, not 503. This means a full key is
  configured. I did not read the key file.

### CORS / gateway

- Quarkus CORS is off (`yano.jar!/application.yml`: `quarkus.http.cors.enabled: ${YANO_HTTP_CORS_ENABLED:false}`).
- Traefik adds CORS (`DEP/templates/traefik-yano.yml.j2` 61-72): methods `GET, HEAD, POST, OPTIONS`, headers `Accept, Content-Type, X-API-Key`,
  and origins from `api_cors_allowed_origins`, which is only `http://127.0.0.1:4173` and `http://localhost:4173` (`DEP/inventory.yml` 43-45).
  LIVE: `Origin: http://localhost:4173` gets `access-control-allow-origin`, while `Origin: https://evil.example` gets none.
  **A browser SPA on any other origin cannot call the nodes directly.** The Quarkus backend must proxy, or the allow-list must change.
- Rate limit: Traefik `rateLimit average 200, burst 400` per `CF-Connecting-IP` (`DEP/inventory.yml` 41-42; `traefik-yano.yml.j2` 93-100).
  All demo traffic proxied through one backend shares that backend's budget per node.
- Cloudflare in front (`api_proxy: 'cloudflare'`, inventory line 40). Use an explicit User-Agent. The Java `AppChainClient` default UA is **UNVERIFIED** against Cloudflare.

## 3. Generic endpoints (base `https://nodeN-showcase.yano-x.io/api/v1`, chain-scoped under `/app-chain/chains/{chainId}`)

`quarkus.resteasy.path=/api/v1` (`yano.jar!/application.properties`). Access levels are from annotations in `RES` and defaults in `FLT`.
Under the live config READ and SUBMIT need no key.

| Purpose | Method + path | Access | Response (source; LIVE-verified unless noted) |
|---|---|---|---|
| Chain list | `GET /app-chain/chains` | READ | `[{chainId, tipHeight, stateRoot, stateCommitment{profile, genesisId, version, stateRoot, oldestProvableHeight, ...}}]` (`RES` 86-102) |
| Chain status | `GET .../status` | READ | `gateway.status()` plus `capabilityManifest` plus `stateCommitment` (`RES` 738-755). LIVE keys: `chainId, consensusProfile, stateCommitment, memberKey, members, threshold, membership*, running, sequencing, role, sequencer{mode, proposer, consensusProtocol, currentView, roundTimeoutMs, leader}, tipHeight, stateRoot, stateMachine, capabilityManifest{components[{id, topics, querySubjects}], workflows, crossCutting, proofSubjects}, anchor{enabled, mode, leader, bootstrapped, lastAnchoredHeight, lastAnchorTx, lastAnchorL1Slot, lagBlocks, scriptAddress, ...}, effects{...}` (workflow-chain only)`, poolSize, poolCapacity, submissionsPaused, submitted, received, relayed, duplicates, drops, admissionRejections, admissionUnavailable, stalled, peers{ip:port: bool}, peerTransports` |
| Tip | `GET .../tip` | READ | `{chainId, height, stateRoot, stateCommitment}` (`RES` 802-814) |
| Blocks page | `GET .../blocks?from=&limit=` (limit 1..200, default tip window 20) | READ | `{chainId, tipHeight, from, blocks[{height, timestamp, stateRoot, messageCount, certSignatures}]}` (`RES` 911-934) |
| Block | `GET .../blocks/{height}` | READ | `{height, chainId, prevHash, timestamp, messagesRoot, stateRoot, proposer, certSignatures, messages[{messageId, topic, sender, senderSeq, bodyHex}]}`; 404 `{"error"}` (`RES` 874-903). LIVE: every block has `certSignatures: 4` (threshold 4 of 5). |
| **Submit** | `POST .../messages` body `{"topic": str, "body": utf8-text}` or `{"topic", "bodyHex": hex}` (bodyHex wins, `RES` 602-604) | SUBMIT | **202** `{messageId, chainId, topic}`. **400** `{"code": CODE}` for application admission rejection (`RES` 616-618; code kept only if it matches `[A-Z_]{1,32}`, else `APPLICATION_REJECTED`: `HOST@pre17 core-api/.../AppSubmissionRejectedException.java` 24-27). **400** `{"error": msg}` for envelope problems: missing body, bad hex, empty body, body > maxMessageBytes, topic > 256 bytes, NUL, or leading `~` (`RES` 597-607, 626-627; `SUB` 2015-2026). **429** `{"error"}` when the pool is full. **503** `{"error"}` when not running, paused, or admission unavailable (`RES` 619-625; `SUB` 2011-2014, 2058-2078). Not LIVE-tested (no POST). At pre17 there is **no `details` field**; HEAD adds one (`git diff v0.1.0-pre17 HEAD`). |
| Message status | `GET .../messages/{messageIdHex}` | READ | Finalized only: `{messageId, chainId, height, index, topic, sender, senderSeq, bodyHex}`. **404** `{"error":"No finalized message with id ..."}` while pending or unknown (`RES` 936-970). There is **no pending/receipt/status field**; poll until 200. |
| Recent (node-local) | `GET .../messages?limit=&topic=` | READ | Ring buffer (max 1000, `SUB` line 86) including pending: `{messageId, ..., receivedAt, source: LOCAL or RELAY...}` (`RES` 709-736). LIVE returned `[]` (resets on restart). |
| By topic / sender | `GET .../messages/by-topic/{topic}?fromHeight&limit`, `GET .../messages/by-sender/{64hex}` | READ | `{chainId, topic or sender, messages[{height, index, messageIdHex}]}` (`RES` 972-980, 1161-1178; limit clamp 1..1000 `SUB` 3404) |
| Message inclusion proof | `GET .../messages/{id}/proof` (lowercase 64-hex) | READ | `{schemaVersion, treeId:"binary-merkle-blake2b256-v1", chainId, blockHeight, blockHash, messagesRoot, messageId, messageIndex, leafCount, siblings[]}` (`RES` 1357-1383) |
| Proof package | `GET .../messages/{id}/proof-package` | READ | LIVE keys: `schema, chainId, applicationIdentity, messageId, suppliedMessage, messageInclusionProof, evidence, blockMessageRootProof, stateRecordProof, anchorReference, verificationPolicy, localAvailability, verification` (`RES` 1385-1396) |
| Evidence | `GET .../evidence/{id}` | READ | LIVE keys `anchor, blocksCbor, chainId, members, messageId, stateCommitment, threshold` (`RES` 1334-1355) |
| **State entry** | `GET .../state/entry/{keyHex}?height=` | READ | Lowercase hex, at most 256 bytes. Returns the commitment view plus `{key, chainId, committedHeight, presence: PRESENT or ABSENT or TOMBSTONED, valueHex?, finalizedAtHeight?, blockHash}`. A missing key returns **200 with `presence:"ABSENT"`** (LIVE). 404 `STATE_PROOF_UNAVAILABLE` only if no snapshot is available (`RES` 1511-1517, 1847-1942). |
| **State proof** | `GET .../state/proof/{keyHex}?height=` | READ | Same fields plus `proofWireHex`, `block{version, height, prevHash, l1Slot, l1BlockHash, timestamp, messagesRoot, stateRoot, blockHash, view, consensusContextDigest, proposer, justificationDigest}`, and `finalityCertificate{scheme, signatures[{signer, signature}]}` (`RES` 1935-1940, 2238-2274). LIVE: orders-chain proof shows 4 signatures and a 135-byte wire. |
| Proof verify (server-side) | `POST .../proof/verify` `{mode: inclusion or exclusion, profile: "mpf-blake2b256-v1", presence: PRESENT, ABSENT or TOMBSTONED, expectedRootHex(64), keyHex, valueHex (inclusion only), proofWireHex}` (unknown fields rejected) | READ | `{valid, mode, profile, presence, expectedRoot, key, verifier:"release-matched-appchain-client"}` (`RES` 1948-2035). Not LIVE-tested (POST). Client-side alternative: Java `SRC/sdk/client/.../ProofVerifier.java` (`verifyInclusion` line 387, `verifyExclusion` 402, `verifyCertified` 293). |
| Typed proof subjects | `GET .../proof-subjects` then `POST .../proof-subjects/{subjectId}/proof` `{coordinates:{...}, view:"latest" or "height" or "latest-confirmed-anchor", height?, claim:{claimId, operands:{}}, includeEvidence:bool}` | READ (the POST is READ-classed, `RES` 1406-1408) | `{schemaVersion, descriptor, normalizedCoordinates, canonicalLogicalKeyHex, physicalKeyHex, proof{...stateProofView, finalityCertificate}, fact, claim, claimResult, trust, includeEvidence}` (`RES` 1406-1453). There is also `/package` (1455-1473). The POST was not LIVE-tested. Per-chain subjects are listed below (LIVE GET). |
| Committed query | `POST .../query/{path}` `{"paramsHex": lowercase-hex}` (params at most 64 KiB) | READ | `{chainId, stateMachineId, committedHeight, stateRoot, payloadHex}`; error `{code, error}` with 400, 404 (UNSUPPORTED), 413, 429, 502, 503, 504, 500 (`RES` 631-707). Not LIVE-tested. |
| Anchor commitment | `GET .../anchor/commitment` | READ | `{chainId, mode:"script", anchoredHeight, stateRoot, blockHash, transactionHash, l1Slot, stateCommitment, provenance, trustWarning}`; 404 `CONFIRMED_ANCHOR_UNAVAILABLE` (`RES` 2037-2064). LIVE approvals-chain: anchoredHeight 3, tx `caf6b8e2...`. |
| State identity / oldest provable | `GET .../state/identity`, `GET .../state/oldest-provable` | READ | `RES` 816-829, 859-872 |
| Effects (workflow-chain) | `GET .../effects?fromHeight&limit`, `GET .../effects/{h}/{o}`, `GET .../effects/{h}/{o}/proof`, `GET .../effects/stats` | READ | LIVE: effect `{height:5, ordinal:0, type:"showcase.outbox.write", scope:"showcase/order-release/<releaseId>", gate:"APP_FINAL", resultPolicy:"CHAIN", expiryHeight, payloadHex, effectHashHex, effectIdHashHex}`; detail adds `execution{status:"DONE", attempts, externalRef, updatedAt}`; proof keys `chainId, effectCount, effectHashHex, effectsRootHex, height, merklePath, ordinal, recordCborHex, stateKeyHex, stateProofWireHex, stateRootHex, version` (`RES` 982-1072) |
| **Streaming (SSE)** | `GET .../stream?fromHeight=&topic=` (`text/event-stream`) | READ | Event `app-message`, id `height:index`, data `{chainId, height, index, messageId, topic, sender, senderSeq, bodyHex}`. A `heartbeat` event (data = tip height) is sent after 15 s idle. `fromHeight` replays history, otherwise the stream is live-only (`RES` 2293-2395). **LIVE-verified through Cloudflare** (orders-chain replay of 3 events). No websocket endpoint exists in `RES`. |
| Privileged (do not use) | `admin/*`, `identity`, `state/integrity`, `snapshot` POST, `effects/*/requeue, cancel, claim, report`, `observations/*` | PRIVILEGED | LIVE 401 without key |

Plugin domain APIs (`/api/v1/plugins/{bundleId}/{path}`, `HOST .../PluginDomainResource.java` line 44): none of the six chains' machines
register one. The stdlib bundle registers only `AuthenticatedMapDomainApiProvider`
(`SRC/state-machines/stdlib/src/main/resources/META-INF/services/org.yanoproject.api.plugin.domain.DomainApiProvider`). The composite bundle
has only operator governance routes `profile-governance-status` (READ) and `profile-governance-command` (PRIVILEGED)
(`COMP/CompositeGovernanceDomainApi.java` 23-33). A demo UI does not need them.

Reference UI client: `HOST/console-ui/frontend/src/lib/api/client.ts` (about lines 227-420) wraps all of the above, including
`/stream` and `submit({topic, bodyHex})`.

**Important for a UI:** stdlib state machines apply business rejections as silent no-ops *after* finalization (see each chain). There is no
per-message outcome or receipt endpoint for these six chains. A UI must derive the outcome by reading state before and after (entry or proof).

## 4. Per-chain inventory

CBOR rules shared by stdlib contracts (`CON/internal/StdlibContractCbor.java`): the body must be a single canonical definite-length array
of the exact arity. It is re-encoded and must round-trip byte-identically (line 53). Only uint, bstr, tstr and arrays are allowed, with no
tags, at most 2048 items, depth at most 12, and 1 byte to 1 MiB (lines 20-23, 40-60, 121-143). The effective cap is `maxMessageBytes = 65536` (LIVE).
State keys must be 1-256 bytes (line 21, 106-110). Text ids must be non-blank UTF-8 of at most 256 bytes (99-104).
Example hexes below were produced locally with `python3 KIT/tools/showcase_codec.py` (source: `KIT/tools/showcase_codec.py` 119-167).

### 4.1 orders-chain (`ordered-log`)

**Payload**
- Any topic is allowed except a leading `~`, NUL, or more than 256 bytes. The kit uses `orders.command.v1` (`KIT/docs/MESSAGE_SUBMISSION.md` table).
- The body is opaque bytes, 1..65536. `OrderedLogStateMachine` does not override `validate` (`HOST@pre17 runtime/.../OrderedLogStateMachine.java` 25-79), and the default accepts (`HOST@pre17 core-api/.../AppStateMachine.java` 54-56). So a text body is fine.
- Example: `{"topic":"orders.command.v1","body":"{\"order\":\"A-100\",\"event\":\"created\"}"}`. The equivalent `bodyHex` is `7b226f72646572223a22412d313030222c226576656e74223a2263726561746564227d`.
- LIVE bodies look like `showcase-order-<unix>` (`KIT/showcase.sh` `run_orders`, line 962).

**State written** (`HOST@pre17 core-api/.../transition/FinalizedMessageIndex.java`)
- For each message: key = `sha256("~yano/finalized-message/v1/" || messageId32)`, value = `cbor [1, height, originalIndex, topic, sender32]`.
- A tip key is also written per block (lines 80-157).

**Authorization.** None. Any member can append.

**Reading results**
- `GET .../messages/{id}` returns the position.
- Typed proof: `POST .../proof-subjects/finalized-message-v1/proof` with `{coordinates:{"message-id":id}, view:"latest", claim:{claimId:"recorded", operands:{}}, includeEvidence:false}` (`KIT/showcase.sh` 908-919; `KIT/docs/MESSAGE_SUBMISSION.md`). The fact fields are height, index, sender and topic (LIVE proof-subjects).
- Raw alternative: `GET .../state/proof/{sha256(ns||id)}`. LIVE: id `393f14cc...` maps to physical key `c8105e22...` with PRESENT value `85010100716f72646572732e636f6d6d616e642e7631 5820 2d71...`.
- **Doc bug:** `KIT/docs/MASTER_DEMO_CURL.md` line 79 queries `state/proof/$ORDER_ID` with the raw message id. LIVE returns `presence: ABSENT` for that. `MESSAGE_SUBMISSION.md` correctly says the physical key is required.

**Demo defaults.** Body `{"order":"A-<n>","event":"created"}`. Show height, index, 4-of-5 cert, the MPF proof and the anchor.

**Risks.** Every message adds a permanent state key and a block. There is no business validation, so any payload up to 64 KiB is accepted.

### 4.2 registry-chain (`kv-registry`, value-format `utf8`)

**Payload** (`CON/KvRegistryContract.java` 28-37; `STD/KvRegistryTransitions.java` 48-59)
- Body: `[op uint, key bstr, value bstr]`. op 0 = PUT, op 1 = DELETE (value must be empty).
- key: 1..256 bytes.
- PUT value: non-empty. On this chain it must be valid UTF-8 (`DEP/files/application-appchain.yml` 37-39; `KvRegistryTransitions.java` 84-105).
- Topic is **not checked** by the machine (`STD/KvRegistryStateMachine.java` 85-117). The manifest advertises `kv-registry.command.v1` (LIVE status). The kit and LIVE use `kv.command.v1`, and LIVE height 1 applied it. The Java `StdlibAppChainClient.kvPut` uses `kv-registry.command.v1` (`SRC/sdk/client/.../StdlibAppChainClient.java` 50-56).

**Examples**
- PUT `product:A-100` = `active`: `83004d70726f647563743a412d31303046616374697665`
- DELETE `product:A-100`: `83014d70726f647563743a412d31303040`

**State**
- Key = raw key bytes. Value = `cbor [owner bstr32, value bstr]` (`KvRegistryTransitions.java` 124-129).
- LIVE: key `64656d6f2d6b6579` ("demo-key") maps to `8258202d71...782e4a64656d6f2d76616c7565`.

**Authorization** (`KvRegistryTransitions.java` 62-82)
- The first writer, identified by sender member key, becomes the owner. PUT or DELETE by another member is rejected with `KV_NOT_OWNER`.
- A PUT that fails the value format is rejected with `KV_VALUE_FORMAT`. DELETE of a missing key is an empty no-op.
- After a DELETE, anyone can claim the key again.
- **These codes never reach the HTTP client.** The rejection happens in `apply()` after finalization and is only logged at debug (`KvRegistryStateMachine.java` 109-115). The message is still finalized and gets 202 and a height, but the state is unchanged.
- Malformed CBOR, empty PUT value, or non-UTF-8 value fails at admission with prose `"Malformed kv-registry command..."`. That becomes **400 `{"code":"APPLICATION_REJECTED"}`** (`KvRegistryStateMachine.java` 85-93; `AppSubmissionRejectedException` 24-27).

**Reading**
- `GET .../state/entry/{hex(key)}` or `/state/proof/{hex(key)}`.
- Typed subject `registry-entry-v1`: coordinate `key` (BYTES_HEX, at most 256), claims `owner-equals` and `value-digest-equals`, facts `owner` and `value-digest` (LIVE).
- There is no query path.

**Demo**
- Put `product:A-<n>` = `active` via node0, then show the owner as node0's key.
- Then try an update via node1. It gets 202 and finalizes, but the owner and value are unchanged. That demonstrates `KV_NOT_OWNER`, and the UI must explain it.

**Risks.** Key squatting is permanent (ownership never expires). Values can be up to about 64 KiB of UTF-8. The state grows without bound.

### 4.3 approvals-chain (`approvals`)

**Payload** (`CON/ApprovalsContract.java` 25-56)
- PROPOSE `[0, itemId tstr, payload bstr, required uint>=1, deadlineMillis uint]`.
- APPROVE `[1, itemId]`. REJECT `[2, itemId]`.
- itemId: non-blank UTF-8, and `"ae/s/"+itemId` must be at most 256 bytes, so **itemId is at most 251 bytes** (lines 114-123).
- Topic is not checked. Kit and LIVE use `approvals.command.v1`, which is also the manifest topic.
- The payload may be empty (line 31). `required` can exceed the member count, which makes the item unreachable. There is no upper bound (line 47-48).

**Examples**
- propose `approval-A-100`, payload `{"order":"A-100","action":"release"}`, required 2, deadline 0: `85006e617070726f76616c2d412d31303058247b226f72646572223a22412d313030222c22616374696f6e223a2272656c65617365227d0200`
- approve: `82016e617070726f76616c2d412d313030`
- reject: `82026e617070726f76616c2d412d313030`
- with deadline 1790500000000: `85006e617070726f76616c2d412d3130314178031b000001a0e21dd100`

**State** (`STD/ApprovalsStateMachine.java` 55-58, 419-433)
- Key `"i/"+itemId`. Example: `692f617070726f76616c2d412d313030`.
- Value `cbor [status, proposer32, blake2b256(payload), required, deadline, [approver32...], rejecter(bstr, empty unless rejected)]`.
- Status values: 0 PENDING, 1 APPROVED, 2 REJECTED, 3 EXPIRED (lines 71-74).
- LIVE `i/approval-1790436150` = `8701 5820 2d71… 5820 6752… 02 00 82 [0209…, 4aae…] 40`: APPROVED, required 2, approvers node1 and node2.

**Rules / authorization** (`STD/ApprovalsTransitions.java` 73-112)
- PROPOSE for an existing id is a no-op.
- APPROVE from a sender already in the approvers list is a no-op (deduplicated by member key). When the approver count reaches `required`, the status becomes APPROVED (terminal).
- A **single REJECT from any member** makes a PENDING item REJECTED (terminal). The proposer and previous approvers can reject too.
- Any command on a PENDING item after `deadline > 0 && blockTimestamp > deadline` marks it EXPIRED. Expiry is lazy; it only happens when an item is touched.
- There is no member allow-list beyond "sender is a chain member", which is already enforced at envelope level. The proposer may also approve.
- All no-ops are silent (HTTP 202 and finalized). Malformed commands give 400 `APPLICATION_REJECTED` (`ApprovalsStateMachine.java` 133-152).

**Reading**
- `state/entry` or `state/proof` on `i/<id>`.
- Typed subject `basic-approval-outcome-v1`: coordinate `proposal-id` (STRING, at most 256), claims `payload-digest`, `quorum-reached` and `status`, facts `approval-count`, `payload-digest`, `required` and `status` (LIVE).

**Demo**
- Propose via node0 with required 2 or 3, then approve via node1, node2 (and node3). Show the approver list grow and the status flip to APPROVED.
- Optionally show "reject via node4" on another item.
- Mock: `approval-A-<n>`, payload `{"order":"A-<n>","action":"release"}`, required 2, deadline 0 (or now + 10 min in ms).

**Risks**
- Any public user can REJECT anyone's pending proposal (griefing).
- A user can also approve with all 5 identities, because the UI chooses the node. Quorum is therefore meaningless in a public demo.
- Items are never deleted. `required` has no upper bound.

### 4.4 balances-chain (`balances`, no minter configured)

**Payload** (`CON/BalancesContract.java` 22-68)
- MINT `[0, account tstr, amount uint>0]`. TRANSFER `[1, account tstr, amount uint>0]`.
- The account key is `"b/"+account` and must be at most 256 bytes, so the account is at most 254 bytes.
- The amount is a CBOR uint up to 2^64-1.
- Topic is not checked. Kit and LIVE use `balances.command.v1`.

**Examples**
- mint 1000 to node0's key: `83007840326437313532396630376362306461663435383330643234623936393965643537326534636434616566613438343731373966316236613864323537373832651903e8`
- transfer 250 to `demo-recipient`: `83016e64656d6f2d726563697069656e7418fa`

**State**
- Key `"b/"+account`. Example: `622f64656d6f2d726563697069656e74`.
- Value = `BigInteger.toByteArray()` (two's complement, big-endian, with sign padding). A zero balance deletes the key (`STD/BalancesTransitions.java` 77-85).
- Decode with `new BigInteger(1, bytes)` (`CON/BalancesContract.java` 47-51).
- LIVE: `b/demo-recipient` = `00fa` (250). `b/2d7152...782e` = `02ee` (750).

**Authorization** (`BalancesTransitions.java` 51-75; `BalancesStateMachine.java` 26-40)
- **The sender's own account is the lowercase hex of its member key.** TRANSFER always debits `b/<senderHex>`.
- MINT is allowed from **any member** because `machines.balances.minter` is unset (`STD/StdlibStateMachineProviders.java` line 272; not in `DEP/files/application-appchain.yml`).
- `BALANCE_NOT_MINTER` would only apply with a minter configured.
- A TRANSFER over balance gives `BALANCE_INSUFFICIENT`. That is a silent no-op after finalization (HTTP 202).
- Accounts can be arbitrary strings. A plain string account such as `demo-recipient` can receive funds but can never spend, because only member-hex accounts can be debited.

**Reading**
- `state/entry` or `state/proof` on `b/<account>`.
- Typed subject `account-balance-v1`: coordinate `account` (STRING at most 256), claims `exact`, `minimum` and `maximum`, fact `balance` (LIVE).

**Demo**
- Mint 1000 to the acting node's member key, then transfer 250 to `demo-recipient` or to another node's member key.
- Show both balances and try an over-transfer to show the no-op.
- Suggest recipients as the other 4 member keys so they can transfer onward.

**Risks.** Unlimited minting by anyone. There is no receipt for insufficient-balance no-ops.

### 4.5 documents-chain (`doc-trail`)

**Payload** (`CON/DocTrailContract.java` 22-76)
- `[entityId tstr, entryHash bstr (non-empty, at most 1 MiB), reference tstr]`.
- entityId: non-blank, with `"e/"+entityId` at most 256 bytes.
- The reference may be empty. Its length is not bounded beyond the message cap.
- The kit's entryHash is `blake2b256(content)` (`KIT/tools/showcase_codec.py` 138-141), but any non-empty bytes are accepted.
- Topic is not checked. Kit and LIVE use `doc-trail.command.v1`.

**Example.** entity `case-A-100`, content `demo-document-v1` (blake2b `a99aeffc...ee55`), reference `demo://document/A-100`: `836a636173652d412d3130305820a99aeffcfe180cbbd686524a90bf74bb29fbe336d0434be6c8e7aa3cc424ee557564656d6f3a2f2f646f63756d656e742f412d313030`.

**State** (`STD/DocTrailTransitions.java` 46-74)
- Key `"e/"+entityId`. Example: `652f636173652d412d313030`.
- Value `cbor [count uint, head bstr32]` with `head_n = blake2b256(head_{n-1} || entryHash || sender32)` and genesis head = 32 zero bytes.
- LIVE `e/case-1790436165` = `8201 5820 7e5b…432f` (count 1).
- The entry list itself is recoverable only from block history (bodies). A verifier can recompute it with `DocTrailContract.computeHead` (lines 54-68).

**Authorization.** None. Any member can append to any entity, and the author is folded into the head. Appends always approve.

**Reading**
- `state/entry` or `state/proof` on `e/<id>`.
- Query `POST .../query/head` with `paramsHex = hex(utf8 entityId)` returns the raw head CBOR (`STD/DocTrailStateMachine.java` 105-121; manifest querySubjects `["head"]` LIVE). Not LIVE-tested.
- Typed subject `document-head-v1`: coordinate `entity-id`, claims `digest-equals`, `revision-exact` and `revision-minimum`, facts `head-digest` and `revision` (LIVE).
- To list entries, use `messages/by-topic/doc-trail.command.v1` and decode the bodies.

**Demo**
- Upload or type document text, hash it client-side (blake2b-256), and append it to `case-A-<n>` from several nodes.
- Show the count increment and recompute the head in the UI from the history.

**Risks.** Unbounded appends. The reference string is free text and publicly displayed, so moderate it.

### 4.6 workflow-chain (`showcase-composite`, preset `order-approval-outbox-v1`)

**Composition** (`SHOW/ShowcaseCompositePreset.java` 22-76; LIVE manifest)

| Component | Machine | Topic | State namespace |
|---|---|---|---|
| `orders` | `KvRegistryStateMachine()` with **RAW** value format (not utf8) | `orders.command.v1` | `component/orders/v1` |
| `approvals` | approvals, on-approved effect disabled | `approvals.command.v1` | `component/approvals/v1` |
| `audit` | doc-trail | `audit.command.v1` (**externally submittable**) | `component/audit/v1` |
| `release` | `ShowcaseReleaseStateMachine` | no topic; query `get` | `component/release/v1` |

- Workflow `showcase-order-release` on topic `showcase.release.v1`, with `maxEffectsPerBlock = 1` (`WorkflowDescriptor(..., 1)` at line 64-66; ctor `COMP/WorkflowDescriptor.java` 42-47).
- **Topic routing is enforced** here, unlike the standalone chains. An unknown topic gives "Unknown composite message topic", so 400 `APPLICATION_REJECTED` (`COMP/CompositeStateMachine.java` 315-341).
- `~fx/result` is accepted by the machine but blocked at REST by the `~` rule (`SUB` 2022-2023).

**Payloads**
- `orders.command.v1`: the same KV CBOR as 4.2, key = order key, value = canonical order JSON bytes. The codec sorts keys with compact separators (`KIT/tools/showcase_codec.py` 58-61).
  - Example: key `order-C-200`, order `{"amount":42,"currency":"USD","id":"order-C-200"}` (blake2b `c90437f9...e0a1`): `83004b6f726465722d432d32303058317b22616d6f756e74223a34322c2263757272656e6379223a22555344222c226964223a226f726465722d432d323030227d`
- `approvals.command.v1`: as 4.3, but **the payload must be the exact same canonical order bytes**.
  - propose `proposal-C-200`, required 2: `85006e70726f706f73616c2d432d32303058317b22616d6f756e74223a34322c2263757272656e6379223a22555344222c226964223a226f726465722d432d323030227d0200`
  - approve: `82016e70726f706f73616c2d432d323030`
- `showcase.release.v1`: `[1, releaseId tstr, orderKey bstr, approvalId tstr]`, canonical (`SHOW/ShowcaseReleaseCommand.java` 15-79).
  - releaseId and approvalId: trimmed UTF-8 of 1..96 bytes. orderKey: 1..256 bytes.
  - A malformed command gets **400 `{"code":"INVALID_SHOWCASE_RELEASE_COMMAND"}`** (32 chars, so it survives the `[A-Z_]{1,32}` filter; `SHOW/ShowcaseReleaseWorkflow.java` 66-74).
  - Example: `84016d72656c656173652d432d3230304b6f726465722d432d3230306e70726f706f73616c2d432d323030`

**Release semantics** (`SHOW/ShowcaseReleaseWorkflow.java` 77-134). All steps are silent no-ops unless every check passes:
1. The order entry and `i/<approvalId>` both exist.
2. The approval status is APPROVED.
3. `payloadHash == blake2b256(orderValue)`.
4. The audit append validates.
5. `claim(releaseId, blake2b(command))` returns CLAIMED. Release ids are one-shot; a replay or conflict is a no-op (`COMP/CompositeStateMachine.java` claim, about line 980).

When all pass, the workflow:
- appends to audit `e/<releaseId>` (entryHash = orderHash, ref `showcase-order:<orderKey>`, author = release sender);
- emits effect `showcase.outbox.write` (payload = order bytes, scope `showcase/order-release/<releaseId>`, gate APP_FINAL, result CHAIN, expiry 100 blocks);
- writes release record `r/<releaseId>` as PENDING.

The node0 executor (`showcase_effect_owner: true` only on node-0, `DEP/inventory.yml` line 132; `node.properties.j2` 84-88) writes a JSON file and submits `~fx/result`. Then `onEffectResult` sets the record to CONFIRMED or FAILED (`SHOW/ShowcaseReleaseStateMachine.java` 45-63).

Release record: `cbor [1, status(0 pending, 1 confirmed, 2 failed), effectHeight, effectOrdinal, outcomeCode, externalRef, detailHash, orderHash32, approvalId]` (lines 94-126).

LIVE full run:
- h1: order registered via node0
- h2: proposal via node0
- h3: approve via node1
- h4: approve via node2
- h5: release via node0
- h6: `~fx/result`

The release record is status 1 with externalRef `showcase-outbox:dff8…`, and effect 5/0 has execution `DONE`.

**State keys (physical)** = `"yano-composite-state-v1\0" || u8 len(component) || component || u16be len(local) || local`, at most 256 bytes total (`SRC/composition/contracts/.../CompositeCommitmentV1.java` 51-67; codec `composite-key` lines 150-161). Local keys are `order-key`, `i/<proposal>`, `r/<release>` and `e/<release>`. Examples:
- orders `order-C-200`: `79616e6f2d636f6d706f736974652d73746174652d763100066f7264657273000b6f726465722d432d323030`
- approvals `i/proposal-C-200`: `79616e6f2d636f6d706f736974652d73746174652d76310009617070726f76616c730010692f70726f706f73616c2d432d323030`
- release `r/release-C-200`: `79616e6f2d636f6d706f736974652d73746174652d7631000772656c65617365000f722f72656c656173652d432d323030`
- audit `e/release-C-200`: `79616e6f2d636f6d706f736974652d73746174652d763100056175646974000f652f72656c656173652d432d323030`

LIVE: these key shapes return PRESENT for the `*-1790436291` run.

**Reading**
- `state/entry` or `state/proof` on the physical keys.
- Query `POST .../query/components/release/get` with `paramsHex = hex(releaseId)` (`KIT/showcase.sh` composite_verify; `COMP/CompositeStateMachine.java` 682-692; `SHOW/ShowcaseReleaseStateMachine.java` 65-83).
- `effects` endpoints.
- Typed subjects: `orders:registry-entry-v1`, `approvals:basic-approval-outcome-v1`, `audit:document-head-v1`, `composite-profile-v1`, `finalized-block-messages-v1` (LIVE). `query:release:get` appears in the manifest but not in the proof-subjects listing.

**Authorization**
- Orders use KV ownership (first writer per order key, `KV_NOT_OWNER` silent).
- Approvals follow the 4.3 rules.
- Release can be sent by any member.
- Audit can be appended directly by any member via `audit.command.v1`.

**Demo defaults**
- order `{"id":"order-D-<n>","amount":42,"currency":"USD"}`, key `order-D-<n>`, proposal `proposal-D-<n>`, release `release-D-<n>`, required 2.
- Register and propose via node0, approve via node1 and node2, release via node0.
- Poll the release record until status 1, then show the effect, its execution and its proof.

**Risks** (see also section 5)
- Two valid releases in one block exceed the workflow effect quota of 1 and **throw** (see 5.1).
- The executor writes one file per release on node0 disk (`/var/lib/yano/appchain-effects/showcase-outbox`, `node.properties.j2` line 87).
- Order-key squatting.

## 5. Public-demo risk register

1. **Possible chain stall on workflow-chain (source-derived, not exercised).** `ShowcaseReleaseWorkflow.apply` calls `emit` without checking
   `remainingEffectCapacity()` (`SHOW/ShowcaseReleaseWorkflow.java` 111-129). `WorkflowEmitter.emit` **throws** `IllegalStateException("workflow effect quota exceeded")`
   once the per-block quota (1) is used (`COMP/CompositeStateMachine.java` 1021-1032). The proposer does no per-message trial execution
   (`ENG` 858-947: only `validateForBlock` filtering, and the release `validate` only checks decoding). An `applyBlock` failure aborts the propose tick
   (`ENG` 822, 850-856; `RuntimeException("Failed to apply app block")` at `ENG` 2110), and the messages stay pooled.
   Two distinct, individually valid releases in the same 1 s block could therefore repeatedly fail block production until the messages
   expire (TTL 600 s). They may even reference the same approved proposal with different release ids, or come from a double-click.
   **Mitigation in the demo backend:** serialize release submissions (at most 1 per block, wait for finalization before allowing the next).
2. **Anyone can act as any of the 5 members.** Submission is unauthenticated (section 2), and the node signs as itself. Quorums, KV ownership and
   minter rules therefore protect nothing against public users. Treat the "identity picker" as a demo affordance and say so in the UI.
3. **Unbounded state growth and spam.** Every accepted message is a permanent block, and most create permanent state keys (ordered-log index, KV keys,
   approval items, balance accounts, trail heads, composite claims and records). Limits: 64 KiB per message, pool 10000, and Traefik 200 rps average per IP
   (shared by a proxy backend). There is no pruning in the demo config. **UNVERIFIED** whether any state pruning is enabled.
4. **Silent no-ops look like success.** `KV_NOT_OWNER`, `KV_VALUE_FORMAT`, `BALANCE_INSUFFICIENT`, duplicate or terminal approvals, and failed release
   preconditions all get 202 and a finalized height. The UI must diff state.
5. **Griefing.** A single REJECT kills any pending proposal. First-writer ownership squats KV and order keys. Anyone can append to any doc trail or
   to the workflow `audit` component. Unlimited mint.
6. **Content exposure.** Bodies are public forever (blocks, `stream`, `messages/by-topic`). Free-text fields (orders body, KV value, doc reference,
   approval payload) need moderation or templating in a public demo.
7. **Node0 disk.** The outbox writes one JSON file per released effect (bounded by at most 1 per block by quota, but unbounded over time).
8. **Operations that throw instead of reject.** Beyond item 1: `ApprovalsTransitions.evaluate` and `BalancesTransitions.decide` throw on invalid
   commands (`STD/ApprovalsTransitions.java` 74-77; `STD/BalancesTransitions.java` 53-56). Admission and decode filtering make those unreachable for
   well-formed traffic. Not a practical risk. Plugin exceptions at admission map to 503 (`SUB` 2072-2077).
9. **CORS** only allows `localhost:4173`, so a public SPA must go through the Quarkus backend.

## 6. Suggested backend contract (derived)

- Keep one node base URL per identity (node0..node4). "Act as memberN" means POST to nodeN.
- Submit `{"topic", "bodyHex"}` built server-side with a codec port of `KIT/tools/showcase_codec.py`, or with the Java contracts `CON/*Contract.java` plus `SRC/sdk/client` `StdlibAppChainClient`.
  Mind the topic difference: the Java client uses `kv-registry.command.v1`; workflow-chain needs `orders.command.v1`.
- Poll `GET messages/{id}` (404 until finalized, typically at most a few seconds with a 1 s interval), or subscribe to `GET stream?topic=`.
- Then `GET state/proof/{key}` for the domain key. Show `presence`, the decoded value, `committedHeight`, `finalityCertificate.signatures.length` against `threshold`,
  and `anchor/commitment` (anchors every 30 blocks or 60 min).
- Optional: `POST proof/verify`, or client-side `ProofVerifier.verifyInclusion`.

## 7. Open questions / UNVERIFIED

- Submission without X-API-Key was not tested with a POST (per rules). The conclusion that it is public rests on config plus filter source.
- The composite quota-overflow stall (5.1) was not reproduced.
- The meaning of the mismatch between config `state.genesis-id` and live `genesisId`.
- Origin of `registry-audit-chain` and `approval-audit-chain` on the live node.
- Whether Cloudflare blocks the Java HTTP client's default User-Agent.
- `POST query/head` and `POST query/components/release/get` response shapes were taken from source and kit usage, not LIVE.
