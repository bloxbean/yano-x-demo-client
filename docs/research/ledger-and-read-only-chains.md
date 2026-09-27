# Yano X pre3 showcase — ledger chains, host API, and client constraints (demo-client research)

Date: 2026-09-27. Cluster: Yano 0.1.0-pre17 host + Yano X 0.1.0-pre3 plugins, public ingress `https://node0-showcase.yano-x.io`.
Method: source reading plus live **read-only GETs** (UA `yano-demo-research/1.0`). No POST/PUT/DELETE was sent.
Abbreviations: `SRC` = `/Users/satya/work/yano-cluster/analysis/pre3-refresh-20260926/source`,
`KIT` = `/Users/satya/work/yano-cluster/preprod-contabo/pre3-ops/demo-runtime/yano-x-jvm-0.1.0-pre3/examples/showcase`,
`DEP` = `/Users/satya/work/yano-cluster/preprod-contabo/cluster-pre3/.yano-deploy/render-2e07ad85a539-33ff7bfe0550-r30/ansible`.
Anything marked **UNVERIFIED** is an inference that I did not confirm in source or live.

---

## Part A — Chains

### A.1 `payments-chain` (eutxo-ledger, virtual L2-only EUTxO payments)

**Deployed configuration** (`DEP/files/application-appchain.yml` lines 113-129; `node.properties.j2` only adds members/threshold/peers/anchor for `chains[7]`):

```yaml
chains[7]:
  chain-id: payments-chain
  state-machine: eutxo-ledger
  state: { commitment-profile: mpf-blake2b256-v1, genesis-id: 6b65c806… }
  machines.eutxo:
    profile: yano-eutxo-v1                     # key payments only (no scripts)
    expected-profile-digest: 2499d01e…d1212
    genesis:
      address: addr_test1vrld3msldls64ax7c06vu85nvhk70260q970cssxzjh0hlchc79qg
      lovelace: 100000000                      # the ONLY value that will ever exist on this chain
```

**How value exists on this chain.** The only source is a single virtual genesis allocation of 100 ADA. There is no faucet and no bridge: the bridge Domain API returns `404 {"error":"bridge-unavailable"}` for payments-chain (live). Minting is also impossible, because profile v1 rejects it with `MINT_UNSUPPORTED` (`SRC/ledgers/eutxo/ledger/.../KeyPaymentTransitionEngine.java:266`).

**The genesis wallet key is public by design.** `SRC/examples/showcase/src/main/java/org/yanoproject/x/showcase/ShowcaseEutxoTransactions.java`:
- line 34, 118-126: the seed is `SHA-256("yano-showcase-virtual-eutxo-v1")`. It is used as a raw 32-byte Ed25519 seed (CCL `SecretKey.create`). The address is the enterprise testnet address (header `0x60`) of `blake2b-224(vkey)`.
- line 72-89: the genesis outpoint is `blake2b256( be32(len(addr)) ‖ addr_utf8 ‖ be32(len(outCbor)) ‖ outCbor ) # 0`, where `outCbor` is the CCL legacy-array output `[addrBytes, 100000000]`.
- I verified all of this offline in Python against the live data: seed → vkey `83981dc16a162720ade786d3be77ed5d0101f0cb2b6c7c3564a672220f52942f` → key hash `fed8ee1f…aefbff` (matches the address); computed genesis outpoint `83e8810b…760033#0` (matches the live input); tx id = blake2b-256(body) = `d503e3df…149335` (matches).

**Live state (2026-09-27)**: tip height 1. Exactly one transaction exists, the showcase self-payment. It spent genesis `83e8810b…#0` and sent 100 ADA back to the same address at `d503e3df54b98d1df20f8f6832297bd585faca7ff8b524819ca6f88716149335#0`. So the whole supply (100 ADA) sits in one UTxO owned by the public key.

**Exact wire bytes of an accepted L2 tx** (live, from `GET /api/v1/app-chain/chains/payments-chain/messages/92fb11f6…85b7`, `bodyHex`):

```
84                                   tx = [body, witnesses, isValid, auxData]
 a4                                  body map (4 keys, CCL insertion order)
  00 d9 0102 81 82 5820<txid> 00     0: inputs = tag-258 set [[txid, idx]]
  01 81 82 581d60<keyhash28> 1a05f5e100   1: outputs = [[addrBytes, coin]] (legacy array form)
  02 00                              2: fee = 0
  0f 00                              15: network id = 0 (testnet)
 a1 00 d9 0102 81 82 5820<vkey> 5840<sig>   witness set {0: tag-258 [[vkey, sig]]}
 f5                                  isValid = true
 f6                                  auxiliary data = null
```
ttl (key 3) and validity start (key 8) are left out when 0. The showcase builds this with `.ttl(0).validityStartInterval(0)`.

**Validation rules for profile v1** (`KeyPaymentTransitionEngine.java`; bounds from `SRC/ledgers/eutxo/contracts/.../EutxoProfile.java:42-60`):
- **Canonical CBOR is required.** `Transaction.deserialize(bytes).serialize()` in the host's CCL (0.8.0-pre5-dev1) must return exactly the same bytes, or the tx is rejected with `NON_CANONICAL_CBOR` (lines 193-203). This is the main trap for a hand-written codec.
- Fee must be exactly 0 (`NON_ZERO_FEE`). ADA only (`NATIVE_ASSET_UNSUPPORTED`). Every output needs lovelace > 0; there is **no min-UTxO**. Outputs must use testnet key addresses (`SCRIPT_OUTPUT_UNSUPPORTED`, `NETWORK_MISMATCH`).
- Not allowed: scripts, datum hash, reference scripts, reference inputs, collateral, certificates, withdrawals, governance, or `isValid=false`.
- Limits: tx ≤ 64 KiB, ≤ 64 inputs, ≤ 64 outputs, output CBOR ≤ 16 KiB, address index 1,024 UTxOs per address.
- Authorization: for every input's payment key hash there must be a vkey witness whose Ed25519 signature over `blake2b-256(body bytes)` verifies (lines 356-409). Otherwise `MISSING_INPUT_WITNESS`.
- Ordering: `TOO_EARLY`/`EXPIRED` are checked against the block's L1 slot, which is 0 on this chain (the live block header has `l1Slot: 0`). So leave ttl out. `DUPLICATE_TRANSACTION` applies if the tx id was already accepted.
- Within a block, the first valid spend wins and later ones get `INPUT_NOT_FOUND` (`SRC/ledgers/eutxo/README.md` "State and queries").

**Build → sign → submit flow (no SDK):**
1. Pick inputs. The simplest is the indexer: `GET …/index/v1/accounts/{addr}/utxos?chain=payments-chain`. The consensus-backed alternative is `POST /api/v1/app-chain/chains/payments-chain/query/utxos/address` with body `{"paramsHex": hex(utf8(address))}`. That returns `payloadHex` = CBOR `[1, [[1, txId(text), index, address(text), outputCbor(bytes), origin(0=GENESIS,1=TRANSACTION,2=L1_DEPOSIT)], …]]` (`EutxoCbor.java:71-89, 375-384`). The showcase uses exactly this (`KIT/showcase.sh:1632-1650`). The query is a POST but does not change state. Part B has its auth status.
2. Build the body as shown above: payee output, plus a change output back to the sender if needed, `fee = 0`, `networkId = 0`.
3. Tx id = `blake2b-256(bodyBytes)`. Signature = `Ed25519.sign(seed, txIdBytes)`. The witness set is `{0: 258([[vkey, sig]])}`.
4. Submit with `POST /api/v1/app-chain/chains/payments-chain/messages`, body `{"topic":"eutxo.transactions","bodyHex":"<signed tx hex>"}` (`KIT/docs/MESSAGE_SUBMISSION.md`; topic from `EutxoContract.TRANSACTION_TOPIC`). The response is 202 with `messageId`. That only means the message was admitted, not that the tx was accepted. 400 comes with a symbolic `code`; retry 429/503 with backoff.
5. Outcome. `GET /api/v1/app-chain/chains/payments-chain/messages/{messageId}` gives finality (height, index). The ledger outcome (ACCEPTED/REJECTED + code) comes from any of:
   - `GET /api/v1/plugins/org.yanoproject.x.eutxo/messages/{messageId}?chain=payments-chain`. This is the committed summary, including rejected attempts.
   - `GET …/plugins/org.yanoproject.x.eutxo/transactions/{txId}?chain=payments-chain`.
   - The indexer `…/index/v1/messages/{messageId}`.
   - `POST …/query/transactions/receipt` with `paramsHex = hex(utf8(txIdHex))`. The receipt CBOR is `[1, status(0=ACCEPTED,1=REJECTED), txId, msgId, appHeight, ordinal, l1Slot, code, detail]` (`EutxoCbor.java:35-45`, `EutxoReceipt.java:16-19`).

**The bridge `transfer/build` endpoint cannot be used here.** It exists as `POST /api/v1/plugins/org.yanoproject.x.eutxo.bridge.cardano/chains/{chain}/bridge/transfer/build` with body `{fromAddress,toAddress,lovelace}` and returns `{unsignedTxCborHex, transactionId, submitTopic, …}` (`SRC/ledgers/eutxo/bridge-cardano/.../EutxoBridgeDomainApi.java:52-57, 200-291, 423-429`). But it first requires `bridge.enabled()` (line 86-89), so for payments-chain it returns 404 `bridge-unavailable` (confirmed live on `/bridge/info`). It also sets `ttl = L1 tip + 7200`. A demo backend has to build payments-chain transactions itself.

**Read endpoints for payments-chain (all GET, all 200 without a key live):**

| Endpoint | Returns |
|---|---|
| `/api/v1/plugins/org.yanoproject.x.eutxo.indexer/index/v1/status?chain=` | projection status (`READY_FULL`), indexed/finalized height, lag, `normalizedDigest` |
| `…/index/v1/transactions?chain=&limit(1-100)&cursor&address&status` | tx summaries: `transactionId, messageId, sequence, appHeight, ordinal, l1Slot, status, authorizationProfile, inputs[{outpoint,address,lovelace}], outputs[…], code` |
| `…/index/v1/transactions/{txId}`, `…/messages/{msgId}` | one summary |
| `…/index/v1/accounts/{addr}` | `{address, lovelace (string), utxos[], activityTransactionIds[]}` |
| `…/index/v1/accounts/{addr}/utxos?limit&cursor` | `{items:[{outpoint,address,lovelace}], cursor}` |
| `…/index/v1/accounts/{addr}/activity?limit&cursor&type` | `{transactionIds[], cursor}` |
| `…/index/v1/lineage/outpoints/{txId}/{idx}?direction=both\|ancestors\|descendants&depth(0-6)` | graph `{nodes[{kind,id,status}], edges[{from,to,relation}]}` |
| `/api/v1/plugins/org.yanoproject.x.eutxo/transactions?chain=&limit(1-50)&before` | consensus-committed summaries, returned with `committedHeight` + `stateRoot` (`EutxoDomainApi.java:30-102`) |
| `/api/v1/app-chain/chains/payments-chain/state/proof/{hex(utf8("eutxo/v1/u/<txid>#<idx>"))}` | MPF proof of a UTxO. Live: `presence: PRESENT`, `valueHex` = record CBOR, `proofWireHex`, `block{…}`, cert (see Part B). State keys are in `EutxoStateKeys.java` (`u/` UTxO, `t/` tx receipt, `a/` attempt, `x/address/` index). |

Every indexer response is wrapped in `{apiVersion:"eutxo-index/v1", chainId, stateMachineId, projection{kind:"DERIVED", status, indexedHeight, finalizedHeight, lagBlocks, historyFromHeight, fullHistory}, data}` (`EutxoIndexDomainApi.java:111-127`). The indexer is strict about input and returns 400 for: unknown or duplicate query params; hashes that are not lowercase 64-hex; addresses not matching `addr(_test)?1[0-9a-z]{8,240}` (lines 184-322). When the index is disabled or failed it returns 409 `INDEX_DISABLED`/`INDEX_FAILED`. The indexer is node-local and derived, so for authoritative answers use the query or proof routes. There is also a built-in explorer at `/ui/app-chain/eutxo/?chain=payments-chain` (live 200).

**Can a demo backend hold demo L2 wallets and let users transfer between them? Yes, technically.** The backend would:
- (a) generate N Ed25519 keys and derive `addr_test1v…` addresses (bech32 of `0x60 ‖ blake2b224(vkey)`);
- (b) sign one funding tx with the public showcase seed that spends `d503e3df…#0` (100 ADA) into N outputs (≤ 64);
- (c) after that, build, sign and submit transfers between its own wallets, with zero fee and no min-UTxO.

Caveats the caller must decide on:
1. **Fixed 100 ADA supply**, and no way to top up. If funds get lost, the only recovery is a chain reset (new genesis).
2. **The showcase seed is public.** Anyone who reads the source can spend the genesis UTxO before the demo does, and the demo can race or grief other users. Move the funds into backend-held keys once. Serialize spends per wallet and track pending outpoints, because of `INPUT_NOT_FOUND` races.
3. **This breaks the stock showcase `run eutxo` handler**, which the ops `showcase-demo.sh eutxo` (runbook §11) uses. That handler requires the showcase wallet to own **exactly one** output ("showcase wallet must own exactly one output", `ShowcaseEutxoTransactions.java:53-55`) and always pays a hard-coded 100 ADA to itself (line 99-102). After a split it will fail. Either drop that ops step, or have the demo return exactly one 100 ADA UTxO to the showcase address afterwards.
4. **Submission needs whatever auth the public ingress enforces for POST** (Part B). The runbook §17 says "public read/submit endpoints are unauthenticated by design (showcase)".

### A.2 `payment-chain-settlement` (read-only view)

Config: `DEP/files/application-appchain.yml` lines 144-203. Key values:
- profile `yano-eutxo-v3-bridge-settlement`, network preprod, `l1.stability-depth: 2`;
- vault `addr_test1wr46zv7m2wsraetk2t5mktjjkls5ppjyql7nms7rsx67ygcuuqlqk` (script hash `eba133db…e223`);
- withdrawal (claim-forming) address `addr_test1vr2kynejhg887rjumrjjf3twtwkvkar8v7rhx2ccklv2mxc06vxdn`;
- `max-withdrawal-lovelace 50 ADA`, observer `max-lovelace 100 ADA`, `fallback-delay-slots 21600`, `soft-batch-cap 1`;
- executor addresses: shard `addr_test1wquntm5n…`, root `addr_test1wzwp3v9g…`, root unit `3773…526f6f74`, operator `addr_test1vqwzez9h…`.

Ops context is in the runbook §13: deposits come only through the node Domain API builder, the bounty is 2 tADA, and hotfix D-12 is required.

**Endpoints and fields worth showing** (all GET, all 200 live):

| Endpoint | Display fields |
|---|---|
| `/api/v1/plugins/org.yanoproject.x.eutxo.bridge.cardano/chains/payment-chain-settlement/bridge/info` | `vaultAddress, vaultScriptHash, withdrawalAddress, bridgeEpoch (0), maxDepositLovelace, withdrawalsPaused (false), stabilityDepth` (source `EutxoBridgeDomainApi.java:100-114`; `stabilityDepth` reads 0 because it comes from bundle config, not the chain's `l1.stability-depth: 2`) |
| `…/eutxo.indexer/index/v1/bridge/deposits?chain=payment-chain-settlement&limit&cursor&address&status` | `acceptedOutpoint` (L1 vault tx#idx → cardanoscan), `stagingOutpoint` (L1), `mirroredOutpoint` (L2), `l2Address, l1Slot, l1BlockHash, creditedHeight`. Live: 3 deposits, all to operator L2 address `addr_test1vqwzez9h…` |
| `…/bridge/deposits/{l1TxId}/{idx}` | same, one record |
| `…/bridge/withdrawals?chain=…&limit&cursor&address&status` | `claimId, status (PENDING/CONFIRMED…), withdrawalOutpoint (L2), destinationAddress (L1), lovelace (net payout), bounty, requestedHeight, settlementTransactionId (L1 → cardanoscan), confirmedSlot, confirmedBlockHash, updatedHeight`. Live: claim `fab928bf…` CONFIRMED (3 ADA payout + 2 ADA bounty, settle tx `ee716081…dc1fc`). Claim `37a41a7c…` has been **PENDING** since height 2; it is the payout = fee-payer case, runbook L-31, and cannot be amended. |
| `…/bridge/withdrawals/{claimId}` | same, one record |
| `…/index/v1/transactions?chain=payment-chain-settlement`, `…/accounts/{addr}[/utxos\|/activity]`, `…/lineage/outpoints/{tx}/{i}` | L2 activity. Lineage shows edges from DEPOSIT to TRANSACTION (`SPENT_BY`) |
| `/api/v1/app-chain/chains/payment-chain-settlement/effects` | `l1.settlement` effects: `height, ordinal, type, scope ("bridge/settlement/0/<batch>"), gate (APP_FINAL), resultPolicy, expiryHeight, payloadHex, effectHashHex, effectIdHashHex` |
| `/api/v1/addresses/{vaultAddress}/utxos` (host L1 API, Blockfrost-shaped) | vault inventory: `tx_hash, output_index, amount[{unit,quantity}], inline_datum, block`. Live: 35 ADA (settlement continuation, constr-0 marker datum) + 10 ADA (constr-0 vault deposit datum, ABI 2). The same call on the root address shows the SettleRoot NFT thread datum. |
| `/api/v1/txs/{l1TxId}` | L1 tx summary: `block, block_height, block_time, slot, fees, output_amount, valid_contract` |

Cardanoscan links use preprod: `https://preprod.cardanoscan.io/transaction/<acceptedOutpoint tx | settlementTransactionId>`. Do not let the demo POST to `deposit/build`, `claim/build` or `transfer/build` on this chain. Also do not let it send plain ADA to the vault address: a malformed vault output stalls the observers (runbook L-32, §18).

### A.3 `cardano-history-chain` (cardano-history, `params-only-v1`)

Config: `DEP/files/application-appchain.yml` lines 215-240. Settings: preset `params-only-v1`, observer `epoch-params` of type `l1-epoch-params-v1`, `l1-proof-consumption-required: true`, block max-messages 1000.

The Domain API base is `/api/v1/plugins/org.yanoproject.x.cardano-history/` (bundle id from `SRC/products/cardano-history/runtime/.../CardanoHistoryProduct.java:5`). Routes are defined in `CardanoHistoryDomainApi.java:46-52`. All are GET, `?chain=cardano-history-chain`:

| Route | Live result |
|---|---|
| `status` | `{apiVersion:1, chainId, applicationId:"cardano-history", committedHeight:1, stateRoot, latestEpoch:315}` |
| `epochs?limit(1-15)` | `latestEpoch:315, epochs:[315…307,305…301]`. Epoch 306 is absent (`found:false`). The list covers at most the last 15 epochs. |
| `epochs/{epoch}/parameters` | `dataset:"protocol-parameters", epoch, found, formatVersion:2, canonicalValueHex, fields[{id,type,value}]` (34 fields: `min-fee-a/b, max-tx-size, key-deposit, pool-deposit, coins-per-utxo-byte, protocol-version, cost-models, execution-unit-prices, drep-*, governance-action-*, …`) plus `proof{kind:"primary", physicalKey}` |
| `epochs/{epoch}/parameters/fields/{field_id}` | e.g. `key-deposit` → `{type:"lovelace", value:"2000000", canonicalValueHex, complete:true, proof{kind:"primary-pair", factPhysicalKey, completenessPhysicalKey}}` |
| `epochs/{e}/stake/…`, `stake-address/…`, `dreps/…`, `proposals/…` | These exist in code, but params-only has no such datasets. Expect not-found. |

To prove a value, pass the returned `physicalKey` to the host `GET /api/v1/app-chain/chains/cardano-history-chain/state/proof/{physicalKey}` (Part B).

The chain is at height 1 because every retained epoch boundary was ingested in the first block. New facts only arrive at the next stable L1 epoch transition, roughly every 5 days on preprod (`KIT/docs/CARDANO_HISTORY.md` "Cardano History does not invent an attestation…"). Before any fact finalizes, status and epoch queries return 404.

---

## Part B — Generic host REST API

A sub-agent researched this part in the Yano host source. Its findings are merged below; the full per-endpoint table is in `research-host-api-partB.md` next to this file.

Confirmed live by me:
- `GET /api/v1/app-chain/chains` works without a key and lists 15 chains with `tipHeight`, `stateRoot`, `stateCommitment{profile, genesisId, version, oldestProvableHeight,…}`.
- `GET …/chains/{id}/status` returns 200.
- `GET …/chains/{id}/messages/{messageId}` returns `{messageId, chainId, height, index, topic, sender, senderSeq, bodyHex}`.
- `GET …/chains/{id}/state/proof/{keyHex}` returns the commitment profile + `key, chainId, committedHeight, presence, valueHex, blockHash, proofWireHex, block{version,height,prevHash,l1Slot,l1BlockHash,timestamp,messagesRoot,stateRoot,blockHash,view,consensusContextDigest,proposer,…}` and a finality certificate.
- `GET …/chains/{id}/effects` works.
- The L1 read API: `/api/v1/txs/{id}`, `/api/v1/addresses/{addr}/utxos`.
- Runbook §17: "Public read/submit endpoints are unauthenticated by design (showcase); admin endpoints need the API key file. CORS allows only localhost:4173 origins."
- Runbook L-28: Cloudflare returns `403 error code: 1010` for the default Python-urllib User-Agent. **A Quarkus backend must send an explicit User-Agent.** The default `Java-http-client/…` is UNVERIFIED but may also be blocked.

### B.0 Summary (from the host-source research, checked against live GETs)
- **Base path:** `/api/v1/app-chain/chains/{chainId}/...`. The chain-less aliases return 400 because node0 hosts 15 chains.
- **Status:** `GET …/status` returns `members` (5), `threshold` (4), `tipHeight`, `stateRoot`, `anchor{lastAnchorTx, pendingTx, threadPolicyId, lagBlocks, …}`, pool counters, `peers`/`peerTransports`, `observers`, `capabilityManifest`.
- **Blocks:** `GET …/blocks?limit≤200` and `…/blocks/{h}` give only a `certSignatures` count. The full signer list (`finalityCertificate.signatures[{signer,signature}]`) plus the header is only available through `GET …/state/proof/{keyHex}`, typed proof-subject proofs, or `/evidence/{id}`.
- **Submit:** `POST …/messages` with `{topic, bodyHex|body}`. Responses:
  - 202 `{messageId, chainId, topic}`: admitted, not final;
  - 400 `{code}`; 429 pool full; 503 paused.
- **Status of a submitted message:** there is no pending-status endpoint. Poll `GET …/messages/{id}` (404 until finalized) or watch SSE. Get the business outcome from the plugin query or Domain API.
- **Live updates:** SSE only, `GET …/stream?fromHeight=&topic=`. It sends `app-message` events (id `height:index`, finalized only) and a `heartbeat` after 15 s idle. The server ignores `Last-Event-ID`, so reconnect with `fromHeight` and de-duplicate. There is no websocket or long-poll.
- **Proofs:**
  - MPF `mpf-blake2b256-v1` (`proofWireHex`);
  - message inclusion: binary blake2b256 tree, odd node duplicated;
  - block hash: blake2b256 of the CBOR header;
  - finality cert: Ed25519 over `blake2b256("yano-appchain-commit-v2\0"‖height‖view‖ctx‖blockHash‖valueHash)`, need ≥ threshold distinct members;
  - server-side `POST …/proof/verify` exists but means trusting the node;
  - `…/messages/{id}/proof-package` encodes bytes as base64, everything else is hex.
- **Anchor:** `GET …/anchor/commitment` returns `transactionHash, anchoredHeight, stateRoot, blockHash, l1Slot, mode`. Use it for preprod Cardanoscan links.
- **Auth:** `AppChainApiKeyFilter`, header `X-API-Key`, property `yano.app-chain.api.keys` (entries are `key` or `key=topicA|topicB`; node-wide, no per-chain keys). `yano.app-chain.api.auth.enabled` defaults to false and is **not set** in the deployed template. Result:
  - READ routes (plain GETs, `query`, `proof/verify`, `proof-subjects`) are open;
  - SUBMIT (`POST messages`) is open according to source (not tested live, POSTs were off limits);
  - PRIVILEGED routes (`identity`, `state/integrity`, `admin/*`, plugin operations) return 401 without a key (confirmed live).
- **CORS:** host CORS is off; the Traefik gateway allows only `http://localhost:4173` and `http://127.0.0.1:4173`. Live, `Origin: https://demo.example.com` got no Allow-Origin header, so a browser SPA must go through the Quarkus backend, or the gateway must allow its origin.
- **Limits:** 64 KiB per message, 64 KiB for query params, 64 KiB for plugin POST bodies, 1 MiB for plugin responses. `quarkus.http.limits` is not set, so the Quarkus default body limit applies.
- **Rate limiting:** none in the host beyond 429 backpressure. Traefik allows 200 req/s average with a 400 burst, per `CF-Connecting-IP`.
- **Plugin routes:** `/api/v1/plugins/{bundleId}/{path}` go through the same filter, with access level declared per route. INTERNAL or unknown routes return 404. Paths with `%`, `;`, `//`, a trailing `/` or dot-segments are rejected.
- **OpenAPI:** the live `/q/openapi-app-chain` (0.1.0-pre17) can generate a typed client.

## Yano host app-chain REST API: generic client reference (Part B)

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

### 0. Base path and conventions

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

### 1. Endpoint table

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

### 2. Chain list and chain status

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

### 3. Message submit and message status

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

### 4. State reads, state proofs and verification

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

#### How verification works (what a client must compute)

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

### 5. Anchor commitment and anchor status (Cardano tx ids)

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

### 6. Live updates (SSE)

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

### 7. Authentication (`X-API-Key`)

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

### 8. CORS, body-size limits, rate limiting

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

### 9. Plugin endpoint routing

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

### 10. Deployed config template (`node.properties.j2`): which keys are set (no values)

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

### 11. Practical notes for the demo client

1. Use `/api/v1/app-chain/chains/{chainId}/...` everywhere, because there are 15 chains.
2. Reads are anonymous. Submit is anonymous on this deployment per source (UNVERIFIED live). Hold any key in the Quarkus backend.
3. Browser cross-origin calls fail unless the origin is added to Traefik's allow-list. Proxying via the backend avoids that. SSE through Cloudflare works.
4. Show finality with `certSignatures` (count) against `status.threshold`. For signer detail use `state/proof/{key}` → `finalityCertificate.signatures[]` or `/evidence/{id}`.
5. Show anchoring with `anchor/commitment.transactionHash` and `status.anchor.lastAnchorTx` / `pendingTx`, linked to preprod Cardanoscan.
6. The message lifecycle is: 202 `messageId` → poll `messages/{id}` or watch SSE → `messages/{id}/proof` → plugin receipt query for business result → anchor covering `height` (`anchoredHeight >= height`).
7. `proof-package` returns base64 bytes, while everything else is hex.
8. Rate limit is 200 rps average and 400 burst per IP at the gateway.

---

## Part C — Technical constraints for the demo client

### C.1 Avoiding the Yano X SDK
- Yano X is "Java 25, JVM-only… Do not add GraalVM/native-image tasks" (`/Users/satya/work/bloxbean/yano-x/AGENTS.md:7, 46-47`; identical in `SRC/AGENTS.md`). The SDK (`SRC/sdk/client`) is not built or tested for native, and it pulls in `yano-core-api`, `yano-appchain-proof-verifier` and the stdlib/composition contracts (`SRC/sdk/client/build.gradle:5-16`). **Recommendation: do not depend on it.** Everything the demo needs is plain REST + JSON, plus a few small CBOR/crypto codecs.
- The codecs the demo needs are small:
  - EUTxO tx body/witness encoding (the exact layout is in A.1);
  - record/receipt decoding, only if using the POST `query/*` routes; the indexer and Domain API return JSON;
  - optionally, block-hash and commit-digest recomputation for certificate checks (see C.2).
- **The canonical-CBOR trap.** The ledger re-serializes the tx with CCL and requires byte equality. Either:
  - (a) write the exact layout shown in A.1 by hand: tag-258 input set, legacy array outputs `[addrBytes, uint coin]`, keys in order 0,1,2,15, uint minimal encoding, a witness set with tag 258, `f5 f6` trailer. Then test it against a CCL round-trip in a unit test. Or
  - (b) use `com.bloxbean.cardano:cardano-client-lib` / `-core` + `-transaction-spec` + `-address` + `-crypto` **0.8.0-pre5-dev1**, the same version the host uses (`SRC/gradle/libs.versions.toml:4`; on Maven Central, checked). Using the same serializer is the safest way to pass the byte-equality check. An older CCL (0.7.x) may encode the input set differently (UNVERIFIED).

### C.2 Crypto needed
| Need | Where | JDK built-in? |
|---|---|---|
| Ed25519 sign (tx witness) | `KeyPaymentTransitionEngine.java:372` verifies `sig` over `blake2b256(body)` | **Yes for signing**: `Signature.getInstance("Ed25519")` + `EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed32)`. The JDK has **no public API to derive the public key from a seed**. Workarounds: generate keys with `KeyPairGenerator("Ed25519")` and take the raw 32 bytes from the X.509/PKCS#8 encodings; or use BouncyCastle `Ed25519PrivateKeyParameters(seed).generatePublicKey()`. For the showcase genesis wallet the vkey is public (`83981dc1…942f`, visible in the live witness), so it can be a constant. |
| Ed25519 verify (finality-certificate signatures) | JS example `verify.js` | Yes (JDK EdDSA) |
| BLAKE2b-256 (tx id, block hash, commit digest, MPF, address index) | everywhere | **No.** The JDK has no BLAKE2. Use BouncyCastle `Blake2bDigest(256)` (lightweight API, no JCA provider) or a ~100-line pure implementation (the JS example ships one: `yano-x-examples/02-service-registry-js/src/blake2b.js`, tested against RFC 7693 vectors). |
| BLAKE2b-224 (payment key hash → address) | `ShowcaseEutxoTransactions.java:123` | No. Same options as BLAKE2b-256 (digest length 28). |
| SHA-256 (showcase seed derivation) | line 120 | Yes |
| Bech32 (addr_test) | address encoding | No. Trivial to write, or use CCL `cardano-client-address`. |
| MPF proof verification (`mpf-blake2b256-v1`, `proofWireHex`) | host proof wire | No JDK support. Use `com.bloxbean.cardano:cardano-client-merkle-patricia-forestry` `MpfTrie.verifyProofWire(root, key, value, inclusion, wire)`, the same call the SDK makes (`SRC/sdk/client/.../ProofVerifier.java:357-410`). It is available on Maven Central at 0.8.0-pre5-dev1. |

**GraalVM native-image:**
- BouncyCastle through its lightweight API (`Blake2bDigest`, `Ed25519Signer`) needs no reflection and no provider registration. It is generally native-friendly (UNVERIFIED for this exact version).
- JDK EdDSA (SunEC, pure Java since JDK 17) works in native (UNVERIFIED here).
- The CCL jars ship **no** `META-INF/native-image` metadata (checked the 0.8.0-pre5-dev1 jars in the distribution). However, the Yano host itself is a Quarkus native image that uses CCL, with hand-written configs. `/Users/satya/work/bloxbean/yano/app/src/main/resources/META-INF/native-image/org.yanoproject/yano-app/reflect-config.json` (Address, Credential, PlutusData serializers, NativeScript…) and `native-image.properties` (`--initialize-at-build-time=com.bloxbean.cardano.client.plutus.spec.serializers`) are a reusable reference.
- CCL crypto depends on `bcprov-jdk18on` and `net-i2p-crypto-eddsa 0.3.1`.
- **Lowest-risk native path:** hand-roll the EUTxO CBOR (C.1a) with BouncyCastle for BLAKE2b and Ed25519. Pull in only `cardano-client-merkle-patricia-forestry` if MPF verification is required, and test it under native.

### C.3 Official JS/TS clients to reuse
- There is **no official JS/TS SDK**. `yano-x-examples/02-service-registry-js/src/client.js:3` says: "There is no Yano SDK for JavaScript… Everything below is fetch()".
- **Reusable: `02-service-registry-js/src/verify.js`**, which verifies a certified root without the SDK:
  - `blockHash` = blake2b256(CBOR[version, chainId, height, consensusContextDigest, view, prevHash, l1Slot, l1BlockHash, timestamp, messagesRoot, stateRoot, proposer, justificationDigest]);
  - `valueHash`;
  - `commitDigest` = blake2b256("yano-appchain-commit-v2\0" ‖ int64 height ‖ int64 view ‖ ctxDigest ‖ blockHash ‖ valueHash);
  - Ed25519 check of `finalityCertificate.signatures[{signer, signature}]` against pinned member keys and a threshold.
  - Support files: `cbor.js` (canonical encoder/decoder), `blake2b.js`, `test/selftest.js`.
  - **Limitation stated in its README (l.106-113):** it does *not* verify the MPF inclusion proof. Only Java `ProofVerifier` does that.
  - That repo's last commit is 2026-09-22 (`552c38f`). Its header encoding targets the example's Yano version; re-check it against a live pre17 envelope before relying on it (UNVERIFIED for pre17).
- `07-webhook-effects-js` (client.js, approvals.js, cbor.js) shows the same fetch-only style for approvals commands and effects.
- **The TypeScript product UI in the pre3 source:** `SRC/products/eutxo/ui` (SvelteKit + TS; `src/lib/api.ts`, `wallet.ts`, `types.ts`, `model.ts`) is the EUTxO Explorer served at `/ui/app-chain/eutxo/`.
  - It has typed wrappers for chain list/status, indexer routes, the eutxo Domain API, bridge `deposit/build|assemble`, `transfer/build`, `claim/build`, L2 submit (`POST …/messages {topic, bodyHex}`), `anchor/commitment`, `messages/{id}/proof-package`, and L1 `/txs/{id}`.
  - It adds `X-API-Key` only when configured (api.ts:304).
  - L2 signing is delegated to a CIP-30 wallet (`wallet.ts`). The TS types are a good template for the demo SPA or backend DTOs.
