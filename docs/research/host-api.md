# Yano host app-chain REST API: generic client reference (Part B)

Scope: the generic host REST surface that any client (for example a Quarkus backend plus SPA demo) uses against a Yano **0.1.0-pre17** host running Yano X **0.1.0-pre3** plugins.

Sources:
- Host source: `/Users/satya/work/bloxbean/yano`, at `v0.1.0-pre17-2-g885f31fee`. Only one app-chain REST file differs from the `v0.1.0-pre17` tag: `AppChainResource.java` adds the submit-rejection `details` field (see section 3).
- Yano X pre3 SDK cross-check: `/Users/satya/work/yano-cluster/analysis/pre3-refresh-20260926/source/sdk/client`.
- Live cluster: read-only GETs against `https://node0-showcase.yano-x.io` on 2026-09-27. The live `/q/openapi-app-chain` reports `info.version = 0.1.0-pre17`, and its path list matches the source exactly.

Abbreviations:
- `ACR` = `app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java`
- `FLT` = `app/src/main/java/org/yanoproject/app/api/appchain/AppChainApiKeyFilter.java`
- `PDR` = `app/src/main/java/org/yanoproject/app/api/appchain/PluginDomainResource.java`

---

## 0. Base path and conventions

- **REST prefix `/api/v1`.** The prefix is baked into the artifact (`META-INF/yano-api-prefix-v1`) and checked at startup. The `quarkus.resteasy.path` and `yano.api-prefix` defaults are `/api/v1`, and `quarkus.http.root-path` must stay `/`. See `app/src/main/java/org/yanoproject/app/ApiPrefixContract.java:34,55-57,88-95`.
- **Chain-scoped root:** `/api/v1/app-chain/chains/{chainId}/...` (`ACR:65,104-113`). An unknown chain returns 404 `{"error":"Unknown app chain: X"}`.
- **Legacy chain-less aliases** such as `/api/v1/app-chain/status` exist, but they work only when exactly one chain is hosted. Otherwise they return 400 (`ACR:379-389`). The live node hosts 15 chains, so **always use the chain-scoped paths**.
- **Content types:** resources produce and consume `application/json` (`ACR:66-67,421-422`). The exceptions are `stream` (`text/event-stream`) and the `observations/*` POSTs (`application/octet-stream`).
- **Encoding:**
  - Almost all byte fields are **lowercase hex strings**.
  - Timestamps are epoch **milliseconds**.
  - Error bodies are `{"error": "..."}` or `{"code": "...", "error": "..."}`.
  - Many request-side hex inputs must be *canonical lowercase*, for example state keys and message ids in `/proof` (`ACR:1365,2280-2295`).
  - **Quirk:** `GET .../messages/{id}/proof-package` serialises `byte[]` fields as **base64** (Jackson default), not hex. Live, `messageId` came back as `"PUOgHhh7..."`. Decode accordingly.
- **OpenAPI** is publicly served, so a typed client can be generated from it:
  - `GET /q/openapi-app-chain?format=json`: live 200, 57 KB.
  - `/q/swagger-ui` (`app/src/main/resources/application.yml:55-96`).

---

## 1. Endpoint table

"Auth" gives the host access level (section 7) and what it means on the **live deployment**, where `api.keys` is set and `api.auth.enabled` is not set (so it defaults to false):
- READ = open
- SUBMIT = open
- PRIVILEGED = needs a full key

All paths are relative to `/api/v1/app-chain/chains/{chainId}` unless they start with `/`.

| Method | Path | Auth (level → live) | Purpose | Key response fields | Source |
|---|---|---|---|---|---|
| GET | `/api/v1/app-chain/chains` | READ → open | List hosted chains | `[ {chainId, tipHeight, stateRoot, stateCommitment{...}} ]` | ACR:86-102 |
| GET | `/status` | READ → open | Full chain status | see §2 | ACR:742-759; runtime `AppChainSubsystem.java:3971-4162` |
| GET | `/tip` | READ → open | Tip height and root | `chainId, height, stateRoot, stateCommitment` | ACR:806-818 |
| GET | `/identity` | PRIVILEGED → **401 live** | Redacted config digests | `consensusProfileDigest, compositeProfileDigest, pluginCatalogFingerprint, ...` | ACR:762-790 |
| GET | `/blocks?from=&limit=` | READ → open | Page of block summaries (limit 1..200, default 20; default is the tip window) | `{chainId, tipHeight, from, blocks:[{height,timestamp,stateRoot,messageCount,certSignatures}]}` | ACR:916-938 |
| GET | `/blocks/{height}` | READ → open | One finalized block | `height, chainId, prevHash, timestamp, messagesRoot, stateRoot, proposer, certSignatures (count only), messages[{messageId,topic,sender,senderSeq,bodyHex}]`. **No blockHash here** | ACR:878-907 |
| POST | `/messages` | SUBMIT → open (per source; not POST-tested) | Submit a message | 202 `{messageId, chainId, topic}` | ACR:593-633 |
| GET | `/messages?limit=&topic=` | READ → open | Node-local *recently received* (not finalized) messages | `[{messageId,chainId,topic,sender,senderSeq,expiresAt,bodyHex,receivedAt,source}]` (live: `[]`) | ACR:713-740 |
| GET | `/messages/{messageIdHex}` | READ → open | Finalized message lookup (404 until finalized) | `messageId, chainId, height, index, topic, sender, senderSeq, bodyHex` | ACR:941-974 |
| GET | `/messages/by-topic/{topic}?fromHeight=&limit=` | READ → open | Finalized refs on a topic | `{chainId, topic, messages:[{height,index,messageIdHex}]}` | ACR:977-984 |
| GET | `/messages/by-sender/{senderHex}?fromHeight=&limit=` | READ → open | Finalized refs by 32-byte sender key | `{chainId, sender, messages:[...]}` | ACR:1166-1182 |
| GET | `/messages/{id}/proof` | READ → open | Message → messagesRoot Merkle path | `schemaVersion, treeId, chainId, blockHeight, blockHash, messagesRoot, messageId, messageIndex, leafCount, siblings[]` | ACR:1361-1387 |
| GET | `/messages/{id}/proof-package` | READ → open | ADR-037 `appchain-message-proof-v1` bundle (**base64 bytes**) | `schema, chainId, applicationIdentity, messageId, suppliedMessage, messageInclusionProof, blockMessageRootProof, stateRecordProof, evidence, anchorReference` (UNVERIFIED: full key list, from the SDK verifier) | ACR:1389-1400 |
| GET | `/evidence/{messageIdHex}` | READ → open | Portable evidence: block CBOR, members, threshold, anchor | `chainId, messageId, blocksCbor[], members[], threshold, anchor{anchoredHeight, anchoredBlockHash, txHash, l1Slot}, stateCommitment` | ACR:1344-1359; `core-api/.../evidence/EvidenceBundleCodec.java:100-140` |
| GET | `/state/proof/{keyHex}?height=` | READ → open | MPF/JMT proof for a state key, plus the certified block and **finality certificate** | see §4 | ACR:1507-1513,1851-1946 |
| GET | `/state/entry/{keyHex}?height=` | READ → open | Same without proof wire, block or cert | commitment view plus `key, committedHeight, presence, valueHex?, blockHash` | ACR:1515-1521 |
| GET | `/state/identity` | READ → open | Commitment profile identity | commitment view (§4) | ACR:821-833 |
| GET | `/state/integrity` | PRIVILEGED → **401 live** | Backend integrity check | commitment view plus `valid, detail` | ACR:836-861 |
| GET | `/state/oldest-provable` | READ → open | Oldest provable height | `chainId, oldestProvableHeight, profile, backend` | ACR:864-876 |
| POST | `/proof/verify` | READ (annotated) → open | Server-side proof verification (trusts the node) | `valid, mode, profile, presence, expectedRoot, key, verifier` | ACR:1952-2039 |
| POST | `/query/{path}` | READ (annotated) → open | Plugin read hook on a committed snapshot; body `{"paramsHex":"<lowercase hex>"}` (≤64 KiB) | `chainId, stateMachineId, committedHeight, stateRoot, payloadHex` | ACR:640-711 |
| GET | `/proof-subjects` | READ → open | Typed proof subjects catalogue | `{schemaVersion, chainId, subjects:[{subjectId, coordinates, ...}]}` | ACR:1403-1408 |
| POST | `/proof-subjects/{subjectId}/proof` | READ (annotated) → open | Typed proof; body `{coordinates:{..}, view:"latest"\|"height"\|"latest-confirmed-anchor", height?, claim?, includeEvidence}` | `descriptor, normalizedCoordinates, canonicalLogicalKeyHex, physicalKeyHex, proof{...,finalityCertificate}, fact, claim, claimResult, trust` | ACR:1410-1457 |
| POST | `/proof-subjects/{subjectId}/package` | READ → open | `appchain-state-claim-proof-v1` package | package JSON | ACR:1460-1477 |
| GET | `/anchor/commitment` | READ → open | Latest **L1-confirmed** anchor | `chainId, mode, anchoredHeight, stateRoot, blockHash, transactionHash, l1Slot, stateCommitment, provenance, trustWarning`; 404 `CONFIRMED_ANCHOR_UNAVAILABLE` if none | ACR:2041-2068 |
| GET | `/stream?fromHeight=&topic=` | READ → open | **SSE** stream of finalized messages | §6 | ACR:2297-2399 |
| GET | `/effects?fromHeight=&limit=` | READ → open | Effect records | `{chainId, effects:[...]}` | ACR:986-993 |
| GET | `/effects/{h}/{ord}` | READ → open | One effect plus runtime status | `{record, execution?}` | ACR:995-1010 |
| GET | `/effects/{h}/{ord}/proof` | READ → open | Record → effectsRoot → stateRoot proof | `recordCborHex, effectHashHex, merklePath[{side,siblingHashHex}], effectsRootHex, stateKeyHex, stateRootHex, stateProofWireHex` | ACR:1018-1068 |
| GET | `/effects/stats` | READ → open | Effect gauges | `{chainId, stats}` | ACR:1070-1076 |
| GET | `/snapshots`, `/snapshots/{series}/{seq}`, `/snapshots/status` | READ → open | Authenticated snapshots (404 `CAPABILITY_DISABLED` if off) | `items, nextCursor, viewHeight, viewRootHex` | ACR:1535-1577,1774-1779 |
| POST | `/snapshots/{series}/{seq}/proof`, `/snapshots/proof/verify` | READ (annotated) | Snapshot proof and verification | … | ACR:1579-1760 |
| GET | `/admin/members` | PRIVILEGED → 401 live | Members and threshold | `members[], threshold` | ACR:1192-1199 |
| POST | `/admin/*` (pause, resume, drain-pool, force-anchor, anchor/bootstrap, members/add\|remove\|reset, threshold), `/snapshot`, `/effects/claim`, `/effects/{h}/{o}/requeue\|cancel\|report`, `/observations/reports\|wake` | PRIVILEGED (unannotated POST defaults to PRIVILEGED) → full key | Operator actions — **not for a demo client** | … | ACR:1079-1163,1201-1336,550-591 |
| POST / GET | `/api/v1/app-chain/chains/{chainId}/admin/snapshots/...` | SNAPSHOT_ADMIN (separate key realm) | Snapshot lifecycle admin | … | ACR:1781-1849 |
| GET / POST | `/api/v1/plugins/{bundleId}/{path}` | Per-route level declared by the plugin (READ / PRIVILEGED / INTERNAL) | Plugin domain routes (§9) | Plugin JSON or octet-stream | PDR:43-124; FLT:326-361 |
| GET | `/api/v1/plugin-operations[/bundles[/{id}]]` | Always PRIVILEGED → 401 live | Plugin operations inventory | … | FLT:187-188 |

Live confirmation: every READ GET above that was tried returned 200 **without any API key**: chains, status, tip, blocks, blocks/13, messages/{id}, messages/{id}/proof, proof-package, evidence, state/proof, proof-subjects, state/oldest-provable, anchor/commitment and stream. `identity`, `state/integrity`, `admin/members` and `/api/v1/plugin-operations/bundles` returned **401** `{"error":"Missing or invalid X-API-Key"}`.

---

## 2. Chain list and chain status

**`GET /api/v1/app-chain/chains`** (`ACR:86-102`) returns `[{chainId, tipHeight, stateRoot, stateCommitment}]`. The live node lists 15 chains: orders-chain, registry-chain, approvals-chain, balances-chain, documents-chain, workflow-chain, roles-chain, payments-chain, authenticated-map-chain, authenticated-map-jmt-chain (profile `jmt-blake2b256-v1`), payment-chain-settlement, document-review-chain, cardano-history-chain, registry-audit-chain and approval-audit-chain. All chains except the JMT one use `mpf-blake2b256-v1`.

**`GET .../status`**: the map built in `runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java:3971-4162`. The resource adds a rendered `capabilityManifest` and `stateCommitment` (`ACR:742-759`). Fields, verified against the live `authenticated-map-chain`:

- **Identity and consensus**
  - `chainId`.
  - `consensusProfile{schemaVersion, digest, maxMessageBytes (65536), maxBlockMessages (5000), maxBlockBytes, l1StabilityDepth, enforceSenderSeq, effectsEnabled}`.
  - `stateCommitment{...}` (§4).
- **Finality and quorum**
  - `memberKey` (this node's Ed25519 public key).
  - `members` (count, live 5) and `threshold` (live 4 = quorum for finality certs).
  - `membershipMode` (`governed` / `static`), `membershipEpochFromHeight`, `membershipEpochActive`, `membershipActiveMembers`, `membershipActiveThreshold`, `memberActiveForNextBlock`.
- **Liveness**
  - `running`, `sequencing`, `configuredBlockIntervalMs`, `role` (`proposer` / `member`).
  - `sequencer{mode, proposer, consensusProtocol ("certified-v2"), currentView, roundTimeoutMs, leader, splitVotesObserved}`.
  - `tipHeight`, `stateRoot`, `stateMachine`, `stateMachineStatus{mode, activeProfileDigest}`.
  - `lastBlockAtMillis`, `blockIntervalMs` (average), `stalled`.
- **Anchor**: `anchor{...}`, present when anchoring is configured on this node or the script anchor is bootstrapped.
  - Script mode (`ScriptAnchorService.java:1589-1642`): `enabled, mode:"script", leader, address, walletAddress, bootstrapped, identityCandidatePending, threadPolicyId, scriptHash, scriptAddress, lastAnchoredHeight, observedAnchorCount, anchoredCount, pendingBootstrapTx?, cosignPending ("n/m")?, cosignRange?, pendingTx?, pendingRange ("from..to")?, confirmationObservedAtL1Slot?, lastAnchorTx, lastAnchorL1Slot, lastError?`, plus `lagBlocks`.
  - Metadata mode (`AnchorService.java:530-556`): `enabled, address, lastAnchoredHeight, anchoredCount, pendingTx?, pendingRange?, confirmationObservedAtL1Slot?, lastAnchorTx?, lastAnchorL1Slot?, lastError?`, plus `lagBlocks`.
- **Observers**: `observers{...}` (L1 observers), `epochObservers{...}`, `genericObservations{..., ready, preview}`. Present only when configured.
- **Effects and snapshots**: `activations`, `effects{enabled,maxPerBlock,...,openCount,executor}`, `authenticatedSnapshots`, `sinks`, `webhooks`, `sinkActivationFailures`. Present only when enabled.
- **Pool and counters**: `poolSize`, `poolCapacity` (live 10000), `submissionsPaused`, `submitted`, `received`, `relayed`, `duplicates`, `seenIds`, `drops{}`, `admissionRejections{APPLICATION_REJECTED}`, `admissionUnavailable{CALLBACK_FAILED, STATE_UNAVAILABLE}`, `l1RefDeferrals?`, `storedMessages`.
- **Peers**: `peers{"ip:port": connectedBool}`, `peerTransports{"ip:port": "dedicated"|...}`. Live: 4 peers, all `true`.
- **`capabilityManifest`**: `{schemaVersion, applicationId, applicationVersion, manifestDigest, components[{id,version,configurationId,stateNamespace,topics,querySubjects,origin}], workflows[], crossCutting[], proofSubjects[{subjectId,...}]}` (`ACR:2103-2163`).

When the subsystem is stopped, a reduced map is returned (`AppChainSubsystem.java:4165+`).

---

## 3. Message submit and message status

**`POST .../messages`** (`ACR:593-633`; SDK `AppChainClient.java:180-205`):

- Headers: `Content-Type: application/json`, `Accept: application/json`, plus `X-API-Key` only if auth is enabled.
- Body: the JSON `SubmitRequest(topic, body, bodyHex)` (`ACR:447`):
  - `topic`: a string. Ordinary submissions cannot use `~` topics (`docs/appchain/submission.md:51-52`).
  - Either `bodyHex` (hex bytes, preferred and used by the SDK) **or** `body` (UTF-8 text). If both are present, `bodyHex` wins.
  - It is **not raw CBOR over the wire**: CBOR application payloads go inside `bodyHex`.
- The body is an opaque application payload. The host wraps it in an AppMessage signed by **the receiving node's member key**. Live, `sender` equals the node `memberKey` `2d71...782e`.
- Yano X plugin payloads are plugin-defined CBOR. Live, block 13's body starts with `830158608401...`, which suggests an inner signed command envelope (UNVERIFIED; plugin-specific, see Part A).
- The size limit is `consensusProfile.maxMessageBytes` = 65536.

Responses (`ACR:609-632`, `docs/appchain/submission.md:8-13`):

| Status | Body | Meaning |
|---|---|---|
| 202 | `{messageId (64 hex), chainId, topic}` | Locally admitted and relayed. **Not finality and not business success.** |
| 400 | `{"code":"<UPPER_SNAKE>"}` | Application admission rejection. |
| 400 | `{"error":...}` | Malformed input. |
| 429 | `{"error":...}` | Pool full (backpressure). |
| 503 | `{"error":...}` | Chain stopped or paused, or admission unavailable. |

The structured `details` field (`{"code":..., "details":{"rule","deny","write"}}`) was added **after** the pre17 tag (`git diff v0.1.0-pre17` on ACR:614-622), so the deployed pre17 host returns only `{"code"}`.

**Message status and receipt.** There is **no pending-status or receipt endpoint in the host**. Options:
1. Poll `GET .../messages/{messageId}`. It returns 404 `{"error":"No finalized message with id ..."}` until the message is finalized, then `{messageId, chainId, height, index, topic, sender, senderSeq, bodyHex}` (`ACR:941-974`).
2. Watch `GET .../stream` (SSE) for the `messageId`.
3. For **business outcome**, use the plugin's receipt query. Examples in pre3 docs:
   - `POST .../query/authenticated-map/receipt-v1`
   - `POST .../query/transactions/receipt`
   - `POST .../query/components/document-review-receipts/receipt`

   These are READ-level POSTs (Part A territory). A finalized business rejection still consumes the message id (`submission.md:62-65`).
4. For proof of inclusion, use `GET .../messages/{id}/proof` or `/proof-package` or `/evidence/{id}`.

---

## 4. State reads, state proofs and verification

**`GET .../state/proof/{keyHex}[?height=N]`** (`ACR:1507-1513,1851-1946`):
- The key must be canonical lowercase hex, 1–256 bytes. A longer key returns 413 and a non-canonical one returns 400.
- Without `height`, the endpoint uses the latest committed version. With `height`, it uses a retained historical version (≥ `oldestProvableHeight`).
- A missing proof returns 404 `STATE_PROOF_UNAVAILABLE`.

Response, confirmed live (2962 bytes for an ABSENT key):
- **Commitment view** (`ACR:2070-2101`): `schemaVersion, profile ("mpf-blake2b256-v1"), backend ("mpf"), commitmentFormatId ("mpf-blake2b256-format-v1"), proofEncodingId ("mpf-proof-wire-v1"), nativeVersioning, physicalDelete, formatFingerprint, implementation{compatibility:"Cardano Client Lib MPF compatible", testedImplementations, verifierAvailable, verificationTarget:"off-chain-and-on-chain"}, genesisId, version, stateRoot, oldestProvableHeight`.
- **Proof fields**:
  - `proofSchemaVersion`, `key`, `chainId`, `committedHeight`.
  - `presence` (`PRESENT` / `TOMBSTONED` / `ABSENT`).
  - `valueHex` (absent ⇒ exclusion).
  - `finalizedAtHeight?` (legacy; only for message-id keys).
  - `blockHash`, `proofWireHex`.
- **`block`**, the certified header (`ACR:2242-2262`): `version (3), height, prevHash, l1Slot, l1BlockHash, timestamp, messagesRoot, stateRoot, blockHash, view, consensusContextDigest, proposer, justificationDigest`.
- **`finalityCertificate`** (`ACR:2264-2278`): `{scheme (0), signatures:[{signer (32B pubkey hex), signature (64B Ed25519 hex)}]}`. Live: 4 signatures with threshold 4.

**This is the only generic JSON endpoint that exposes the individual finality-certificate signers.** `/blocks` and `/blocks/{h}` expose only `certSignatures` (a count). The typed `proof-subjects/.../proof` response also contains `proof.finalityCertificate`, and `/evidence` contains full block CBOR including the cert.

**`GET .../state/entry/{keyHex}`** returns the same data without `proofWireHex`, `block` and `finalityCertificate` (`ACR:1939-1944`).

**Known reserved key:** the finalized-message index key is `SHA-256("~yano/finalized-message/v1/" || messageId)` (`core-api/.../transition/FinalizedMessageIndex.java:26-29,126-131,151-158`). On live `authenticated-map-chain` this key is ABSENT, meaning the index is not enabled for that composite, which gives a working exclusion-proof example. Application keys are plugin-defined (Part A, or `/proof-subjects` coordinates).

### How verification works (what a client must compute)

**(a) State proof → stateRoot**
- Profile `mpf-blake2b256-v1` is a Merkle Patricia Forestry (Aiken-compatible) proof.
- Wire format: CBOR list of steps using Plutus-constr tags 121/122/123 (Branch/Fork/Leaf), 4×32-byte branch neighbours and a 64-nibble max path (`appchain/appchain-proof-verifier/.../MpfProofWirePreflight.java:10-15`).
- Verification: `AppChainProofVerifier.verify(profile, presence, expectedRoot, key, value, proofWire)` → `MpfTrie.verifyProofWire(root, key, value, inclusion, wire)` (`AppChainProofVerifier.java:34-89`).
- UNVERIFIED (library internals not read): the MPF path is blake2b-256(key) and the leaf commits blake2b-256(value), per the standard MPF / cardano-client-lib vds implementation.
- `jmt-blake2b256-v1` uses the classic JMT codec. `jmt-poseidon-bls12381-v1` always fails verification (`AppChainProofVerifier.java:51-53`).
- Limits: key ≤256 B, value ≤1 MiB, wire ≤1 MiB.

Client options:
1. **Java/Quarkus backend**: depend on Yano's `appchain-proof-verifier` module (GraalVM-oriented per `appchain/README.md`) or Yano X `sdk/client` `ProofVerifier`. UNVERIFIED: whether it is published to Maven Central.
2. **SPA JS**: an Aiken MPF JS verifier. Wire compatibility is UNVERIFIED.
3. **`POST .../proof/verify`** with `{mode:"inclusion"|"exclusion", profile, presence, expectedRootHex, keyHex, valueHex?, proofWireHex}`. It returns `{valid,...}`, but this trusts the node, so it is only a convenience.

**(b) stateRoot → finalized block**
- Check `block.stateRoot == stateRoot`.
- `blockHash = blake2b-256(CBOR[version, chainId, height, consensusContextDigest, view, prevHash, l1Slot, l1BlockHash, timestamp, messagesRoot, stateRoot, proposer, justificationDigest])` (`AppBlockCodec.java:44-57,219-231`).

**(c) Finality certificate**
- Each signature is Ed25519 over `commitDigest = blake2b-256("yano-appchain-commit-v2\0" || int64BE height || int64BE view || consensusContextDigest(32) || blockHash(32) || valueHash(32))` (`core-api/.../consensus/ConsensusDigests.java:19-20,33-59`).
- `valueHash = blake2b-256(CBOR[version, chainId, height, consensusContextDigest, prevHash, l1Slot, l1BlockHash, timestamp, messagesRoot, stateRoot])` (`AppBlockCodec.java:71-84`).
- Require ≥ `threshold` **distinct** signers from an independently pinned member set (`EvidenceVerifier.java:328`). The SDK `ProofVerifier.verifyCertified` does all of this.

**(d) Message inclusion** (`GET .../messages/{id}/proof`, tree `binary-merkle-blake2b256-v1`)
- The leaf is the raw 32-byte messageId, with no leaf hashing or prefix.
- `parent = blake2b-256(left || right)`.
- An odd level width duplicates the last node, and the sibling in that case must equal the node.
- The root must equal `messagesRoot`. An empty block has a 32-zero-byte root (`core-api/.../evidence/MessageInclusionProof.java:84-98,138-143`; `AppBlockCodec.java:87-110`).
- Live example: a single-message block where `messagesRoot == messageId` and `siblings: []`.

**(e) Trust anchor**
- Compare an (anchoredHeight, stateRoot, blockHash) triple from `GET .../anchor/commitment` against the Cardano anchor tx datum/metadata, checked independently. The host itself warns about this in `trustWarning` (`ACR:2058-2060`).
- For heights below the anchored height, link the older block to the anchored block via the prevHash chain and cert checks (SDK README).

---

## 5. Anchor commitment and anchor status (Cardano tx ids)

**`GET .../anchor/commitment`** (`ACR:2041-2068`) returns `{chainId, mode ("script"|"metadata"), anchoredHeight, stateRoot, blockHash, transactionHash, l1Slot, stateCommitment, provenance:"L1-confirmed by this node", trustWarning}`. It returns 404 `CONFIRMED_ANCHOR_UNAVAILABLE` before the first confirmed anchor.

Live `authenticated-map-chain`:
- `transactionHash` = `547291831a6f2f9368d4c8675dd303fa931347df53457a384130610f8cf1fc10`
- `anchoredHeight` = 13
- `l1Slot` = 134759116

Anchor state is also reported in **`status.anchor`** (§2):
- `lastAnchorTx`: the last confirmed tx id.
- `pendingTx`: in flight.
- `pendingBootstrapTx`.
- `address` / `scriptAddress` / `walletAddress`: bech32 `addr_test1...`.
- `threadPolicyId`, `scriptHash`.
- `lastAnchoredHeight`, `lagBlocks`, `cosignPending`.

Evidence bundles repeat the anchor as `anchor.txHash`.

Cardanoscan links (preprod):
- `https://preprod.cardanoscan.io/transaction/{transactionHash|lastAnchorTx|pendingTx}`
- `https://preprod.cardanoscan.io/address/{address}`
- policy: `https://preprod.cardanoscan.io/tokenPolicy/{threadPolicyId}`. The URL pattern is UNVERIFIED.

Note: the live `anchoredCount` was 0 while `lastAnchorTx` was set. `anchoredCount` is an in-memory counter since restart, and `lastAnchorTx` falls back to persisted meta (`ScriptAnchorService.java:1631-1637`).

---

## 6. Live updates (SSE)

**`GET .../stream?fromHeight=N&topic=T`**, `Produces text/event-stream` (`ACR:2297-2399`):
- `fromHeight`:
  - omitted or -1: live only from the tip.
  - ≥0: first replays finalized history from `max(1, fromHeight)`, then follows.
- `topic`: optional exact-match filter.
- Events:
  - `event: app-message`, `id: <height>:<index>`, `data: {"chainId","height","index","messageId","topic","sender","senderSeq","bodyHex"}`.
  - `event: heartbeat`, `data: <tipHeight>`, sent after 15 s of idle.
- It emits **only finalized messages**. There are no block, anchor or status events, and empty blocks produce no event.
- The server ignores `Last-Event-ID`. Clients reconnect with `fromHeight=<lastHeight>` and de-duplicate on `(height, index)`, which is what the SDK does (`AppChainClient.java:1719-1811`).
- The live queue is bounded at 1024 blocks, and gaps are backfilled from the ledger.
- Live check: `curl ... /stream?fromHeight=12` returned `HTTP/2 200`, `content-type: text/event-stream`, and events `12:0` and `13:0` came through Cloudflare.
- For a browser, `EventSource` works with no custom headers, because reads are open. EventSource cannot send `X-API-Key`.

There are **no WebSocket or long-poll endpoints** in the app-chain REST surface. For anchors, tip or status, poll `/tip`, `/status` or `/anchor/commitment`. Heartbeat data carries the tip height.

---

## 7. Authentication (`X-API-Key`)

The filter is `FLT` (`@Provider ContainerRequestFilter`). It applies only when the matched resource class is `AppChainResource`, `ChainScopedResource`, `PluginDomainResource` or `PluginOperationsResource` (`FLT:206-213`). The node/core API (`/api/v1/...` non-app-chain) is not covered by this filter.

Properties (`core-api/.../config/YanoPropertyKeys.java:716-724`):
- `yano.app-chain.api.auth.enabled` (default **false**).
- `yano.app-chain.api.keys`: a comma list of `key` entries (full key) or `key=topicA|topicB` entries (submit-only, topic-restricted) (`FLT:449-474`).
- `yano.app-chain.api.snapshot-admin-keys`: a separate realm.

Header: `X-API-Key` (`FLT:55`).

Access levels (`AppChainAccess.java:8-27`, `FLT:186-204`):
- An explicit `@AppChainAccess` wins.
- Otherwise unannotated **GET/HEAD/OPTIONS = READ** and **every other method = PRIVILEGED**.
- Explicit annotations:
  - `POST messages` = SUBMIT.
  - `POST query/*`, `proof/verify`, `proof-subjects/*`, `snapshots/*/proof` and `snapshots/proof/verify` = READ.
  - `GET identity`, `state/integrity`, `admin/members` and `observations/*` = PRIVILEGED.
  - Plugin operations = always PRIVILEGED.

Decision logic (`FLT:91-184`):

| Level | auth.enabled=false (live) | auth.enabled=true |
|---|---|---|
| READ, SUBMIT | open, no key needed | need a configured key; topic-scoped keys may READ and SUBMIT only to listed topics (403 otherwise) |
| PRIVILEGED | need a **full (unscoped) key**. 503 `AUTH_UNAVAILABLE` if none is configured; plugin routes become 404 | full key; scoped key → 403 |
| SNAPSHOT_ADMIN | snapshot-admin key (503 if none configured) | same |
| INTERNAL (plugin) | always 404 | always 404 |

There are **no per-chain keys**: keys are node-global. Only topic scoping exists, and topics are free strings.

Live deployment: `yano.app-chain.api.keys` **is set** (value not printed) and `api.auth.enabled` **is not set**. So:
- All READ GETs are open. Confirmed live (200 without a key).
- PRIVILEGED GETs return 401. Confirmed live.
- **Per source, `POST .../messages` (SUBMIT) and READ POSTs such as `query/*` are also open without a key on this deployment.** This is not tested live, because POSTs are forbidden in this research.

For a demo: keep any key server-side in the Quarkus backend and never ship it to the SPA.

---

## 8. CORS, body-size limits, rate limiting

**CORS, host level**: Quarkus CORS is **off by default** (`quarkus.http.cors.enabled: ${YANO_HTTP_CORS_ENABLED:false}`, `app/src/main/resources/application.yml:13-14`), and the deploy template does not set it. There is no custom CORS filter in the host.

**CORS, deployed gateway**: Traefik `yano-cors` middleware (`.../ansible/templates/traefik-yano.yml.j2:61-72`):
- `accessControlAllowMethods: [GET, HEAD, POST, OPTIONS]`
- `accessControlAllowHeaders: [Accept, Content-Type, X-API-Key]`
- `accessControlMaxAge: 600`
- `addVaryHeader: true`
- `accessControlAllowOriginList` from `api_cors_allowed_origins` = **`http://127.0.0.1:4173`, `http://localhost:4173` only** (`.../ansible/inventory.yml:43-45`).

Live check:
- `Origin: https://demo.example.com` gave 200 with **no `Access-Control-Allow-Origin`**, plus `Vary: Origin`, so a browser would block it.
- `Origin: http://localhost:4173` gave `access-control-allow-origin: http://localhost:4173`.

A demo SPA served from another origin must either:
- be added to `api_cors_allowed_origins`, or
- (recommended) call through its Quarkus backend, which also keeps the API key private.

**Security headers** (`traefik-yano.yml.j2:80-92`): `Cache-Control: no-store`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`.

**Body limits**: there is no `quarkus.http.limits.*` in `application.yml` or `node.properties.j2`, so the Quarkus default `max-body-size` (10 MiB) applies. The value is UNVERIFIED because it is the framework default. App-level bounds:
- Messages: `maxMessageBytes` 65536.
- `query` JSON body: ≤132 KiB raw, ≤64 KiB params (`FLT:56,413-414`; `ACR:664`).
- Plugin domain POST: ≤64 KiB (`FLT:57`, `PDR:71-74`, `DomainApiRequest.java:16`).
- Snapshot-verify JSON: ≤8 MiB + 4 KiB.
- Plugin query params: ≤32 names, ≤128 values, ≤2048 chars each (`DomainApiRequest.java:18-22`).
- Plugin response: ≤1 MiB (`DomainApiResponse.java:7`).

**Rate limiting**:
- Host: none (no rate-limit code in `app/src/main`). Only backpressure applies: 429 on a full pool, `BUSY` queries, and a saturated snapshot proof service (with `Retry-After: 1`).
- Gateway: Traefik `rateLimit average 200, burst 400` per client IP, keyed on `CF-Connecting-IP` behind Cloudflare (`traefik-yano.yml.j2:93-100`; `inventory.yml:41-42`).
- Cloudflare fronts the API (live `server: cloudflare`).

---

## 9. Plugin endpoint routing

- **Mount**: `@Path("plugins/{bundleId}")` with `GET {path: .+}` and `POST {path: .+}` gives **`/api/v1/plugins/{bundleId}/{path}`**. It is **not** chain-scoped in the URL; the bundle's route decides which chain it serves (`PDR:43-76`). Examples from pre3 docs:
  - `/api/v1/plugins/org.yanoproject.x.stdlib/authenticated-map/entries/...`
  - `/api/v1/plugins/org.yanoproject.x.role-workflow/proposals/{id}`
  - `/api/v1/plugins/org.yanoproject.x.cardano-history/status`
- **Media types**: produces `application/json` or `application/octet-stream`. POST consumes the same types.
- **Routing**: bundles declare framework-neutral `DomainApiRoute(routeId, method, template, access)`. Templates have ≤8 segments, whole-segment `{param}` captures and ≤256 characters (`core-api/.../plugin/domain/DomainApiRoute.java:10-73`). The host dispatches via `DomainApiGateway`.
- **Error mapping** (`PDR:105-123`): all errors are `{"code","error"}`.
  - INVALID_REQUEST→400, NOT_FOUND→404, CONFLICT→409, BUSY→429, TIMEOUT→504, RESULT_TOO_LARGE→502, UNAVAILABLE→503, FAILED→500.
- **Same auth filter**: yes, `PluginDomainResource` is in the protected set (`FLT:206-213`). Per-route access comes from the plugin declaration (`FLT:326-361`):
  - READ follows the READ rules above (open on live).
  - PRIVILEGED needs a full key.
  - INTERNAL or undeclared routes are always a 404 indistinguishable from an absent route.
  - A PRIVILEGED route with no full key configured also becomes 404.
- **Strict path checks** (`FLT:239-268`): any `%`, `;`, `//`, a trailing `/` or a dot-segment in the raw path gives 404. A GET with a body gives 400.
- `/api/v1/plugin-operations/...` (bundle inventory) is always PRIVILEGED.
- Live: `GET /api/v1/plugins/org.yanoproject.x.evidence-profile/stats` returned 404 "No domain API route matches the request". That bundle or route is probably not installed or not READ on this cluster (UNVERIFIED which).

---

## 10. Deployed config template (`node.properties.j2`): which keys are set (no values)

Path: `/Users/satya/work/yano-cluster/preprod-contabo/cluster-pre3/.yano-deploy/render-2e07ad85a539-33ff7bfe0550-r30/ansible/templates/node.properties.j2`

**Set:**
- HTTP: `quarkus.http.host=127.0.0.1` and `quarkus.http.port=7070`, bound to loopback behind Traefik.
- Server, relay and storage: `yano.server.enabled/port`, `yano.relay.connection.max-connections-per-ip`, `yano.relay.connection.max-inbound-connections`, `yano.storage.path`, `yano.app-chain.storage.path`, `yano.account-state.snapshot-retention-epochs`.
- Plugins: `yano.plugins.directory`, `yano.plugins.bundle."org.yanoproject.x.eutxo.indexer".storage-path`.
- **`yano.app-chain.api.keys`** (set; secret).
- Per chain `chains[i]`:
  - `signing-key`, `members`, `threshold`, `peers`.
  - `sequencer.mode`, `sequencer.proposer`.
  - Anchor-leader only: `anchor.enabled/mode/signing-key/every-blocks/max-interval-minutes`.
  - `state.commitment-profile/format-fingerprint/genesis-id`.
  - `observation.l1-network-genesis-id`.
  - `machines.authenticated-map.genesis-cbor-hex`.
  - `effects.executor.enabled` and `effects.executors.*` (showcase effect owner only).

**Not set (host defaults apply):**
- `yano.app-chain.api.auth.enabled`, so false.
- `yano.app-chain.api.snapshot-admin-keys`.
- `quarkus.http.cors*`, so off at the host; CORS is handled by Traefik.
- `quarkus.http.limits.*`.
- `yano.api-prefix`, so `/api/v1`.
- `YANO_SWAGGER_UI_ENABLED`, which defaults to true.

---

## 11. Practical notes for the demo client

1. Use `/api/v1/app-chain/chains/{chainId}/...` everywhere, because there are 15 chains.
2. Reads are anonymous. Submit is anonymous on this deployment per source (UNVERIFIED live). Hold any key in the Quarkus backend.
3. Browser cross-origin calls fail unless the origin is added to Traefik's allow-list. Proxying via the backend avoids that. SSE through Cloudflare works.
4. Show finality with `certSignatures` (count) against `status.threshold`. For signer detail use `state/proof/{key}` → `finalityCertificate.signatures[]` or `/evidence/{id}`.
5. Show anchoring with `anchor/commitment.transactionHash` and `status.anchor.lastAnchorTx` / `pendingTx`, linked to preprod Cardanoscan.
6. The message lifecycle is: 202 `messageId` → poll `messages/{id}` or watch SSE → `messages/{id}/proof` → plugin receipt query for business result → anchor covering `height` (`anchoredHeight >= height`).
7. `proof-package` returns base64 bytes, while everything else is hex.
8. Rate limit is 200 rps average and 400 burst per IP at the gateway.
