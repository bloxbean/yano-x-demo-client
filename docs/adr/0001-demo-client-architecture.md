# ADR 0001 — Yano X demo client: architecture, identity model and public-demo safety

- Status: Accepted
- Date: 2026-09-27
- Deciders: Yano X maintainers
- Target cluster (first deployment): the 5-member Yano X 0.1.0-pre3 / Yano 0.1.0-pre17 preprod showcase
  (`node{0..4}-showcase.yano-x.io`), app served at **https://client-showcase.yano-x.io** from node-0.
- Research this ADR relies on (file/line citations, live evidence): `docs/research/*.md`.

## 1. Context

The showcase cluster runs 15 app chains, each with its own state machine: ordered log, KV registry, approvals, balances,
document trail, a composite order→approval→release workflow, role approvals, document review, authenticated maps (MPF
and JMT), EUTxO payments, an L1-settled EUTxO bridge, Cardano history, and two declarative-binding chains.

The node console (`/ui/app-chain/`) is an operator tool. External demos need something else. A visitor should be able
to pick a chain and understand what it does. They should submit realistic data with editable mock defaults, act as
different participants, and watch the result finalize, change state, get proven and get anchored on Cardano. None of
that should need a guide.

Facts from research that shape the design:

1. **The HTTP caller never signs the envelope.** `POST /chains/{id}/messages` is signed by the receiving node with its
   member key, and only the 5 member keys can be senders. Acting as member N therefore means submitting through node N.
2. **Some chains also authorize by actor signatures inside the payload** (Ed25519):
   - document-review and authenticated-map use demo actors registered in genesis, with seeds from a public release
     formula `sha256("yano-showcase-demo-actor:" + actorId)`;
   - roles-chain starts with an empty registry; the members govern it (4 of 5).
3. **Reads and submissions need no API key** on this cluster; only admin routes do.
   - CORS allows only `localhost:4173`, so a public browser app cannot call the nodes directly.
   - Cloudflare blocks default user agents.
   - Traefik rate-limits at 200 req/s (burst 400) per client IP.
4. **Most stdlib state machines reject business-rule violations silently after finalization.** Examples: KV not-owner,
   insufficient balance, duplicate vote. The message gets a height but changes no state; there is no receipt endpoint.
   The binding chains do have receipts (`composite/binding-receipt-v1/<messageId>`).
5. **The cluster is public and nothing is ever pruned.**
   - Every submission is a permanent block.
   - Quorum and ownership rules cannot protect anything when every visitor can act as any member.
   - Some inputs throw instead of rejecting: binding-chain key lengths, and two releases in one workflow block.
6. **Payload encodings are small deterministic CBOR structures.** Golden vectors come from the release tool
   `showcase_codec.py`.
7. **The Yano X SDK is JVM-only and deliberately not native-image friendly.**

## 2. Decisions

### D1. Separate repository, one deployable

`bloxbean/yano-x-demo-client` (this repo), not `yano-x-examples`.

- The examples are small, single-use-case samples that start their own local devnet.
- This app is a deployable product. It targets an existing cluster, follows that cluster's chain catalog, and has its
  own release and hosting lifecycle.
- The examples README links here.

### D2. Stack: Quarkus backend + React SPA in one binary

- Backend: Quarkus 3.39 (Java 25), REST (Jackson), SmallRye Health, YAML config.
- UI: Vite + React 19 + TypeScript + Tailwind CSS 4, TanStack Query and lucide icons. Quinoa builds it and serves it
  from the same Quarkus app (SPA routing, dev-server proxy in `quarkus dev`).
- Primary artifact: a **GraalVM native executable for linux-amd64**. It is built in a container
  (`-Dquarkus.native.container-build=true`, Mandrel JDK 25 builder) because development machines are arm64 macOS and
  node-0 is x86_64 Ubuntu 24.04. A JVM uber-jar build is kept as a fallback.
- No Yano X SDK dependency (fact 7). The app uses only:
  - its own deterministic CBOR codec (tested against the release golden vectors);
  - an RFC 7693 BLAKE2b;
  - JDK Ed25519;
  - the JDK HTTP client.

  All of these are native-friendly.

### D3. All node access goes through the backend; no node URL is compiled in

- `demo.nodes` in an external `config/application.yml` lists the members: id, label (organisation), base URL and default.
  The binary contains no cluster URLs; the dev profile points at loopback tunnels.
- The UI shows the configured nodes and lets the user choose the default read node.
- Custom node URLs are optional: `demo.custom-node.enabled`, off by default.
  - Only `https://host[:port]` is accepted, with the host matching `demo.custom-node.host-pattern`.
  - Hosts that resolve to loopback, private, link-local or ULA addresses are refused, to prevent SSRF from the public app.
- The backend is the only HTTP client of the nodes. It:
  - sends an explicit User-Agent and never follows redirects;
  - limits reads to `/api/v1/app-chain/**` and `/api/v1/plugins/**`;
  - builds every write server-side (`POST /chains/{id}/messages` only);
  - sends no user-supplied hex, topics or paths to a node.
- An optional API key (`demo.http.api-key-file`) is read from a file and sent only to configured members. It is not
  needed on the showcase cluster.

### D4. Identity model: two kinds of persona, both server-side

| Persona kind | Used by | How "act as" works | Keys |
|---|---|---|---|
| **Member** (organisation running a node) | orders, registry, approvals, balances, documents, workflow; authenticated-map owner/member collections; registry-audit, approval-audit | the backend submits through that member's node | none held by the app (the node signs) |
| **Actor** (a named person with an Ed25519 key) | document-review (issuer-a, auditor-a, auditor-b), authenticated-map governed collections (issuer-a + auditors); roles-chain (app-registered actors) | the backend signs the actor statement with that actor's key, then submits through a member node | server-side only; never sent to the browser |

- The UI has one **"Acting as"** switcher. It lists only the personas valid for the selected chain and explains why
  (for example "votes are cast by member organisations" versus "the QA reviewer signs personally").
- Actor keys come from `demo.actors` in server config. Each actor has a `seed-source`:
  - `showcase-formula`: `sha256("yano-showcase-demo-actor:" + id)`, already registered on showcase genesis;
  - `file`: a 0600 file, for app-owned actors;
  - `derived`: HMAC of a deployment secret file, for reproducible app-owned actors.

**Which keys each chain needs** (answers "recreate at deployment or provide"):

| Chain | Needed | Already on chain | Deployment action |
|---|---|---|---|
| document-review-chain | issuer-a, auditor-a, auditor-b (registry-admin-a for registry reads only) | yes (genesis) | none — derived from the public formula |
| authenticated-map-chain / -jmt | issuer-a; issuer-a + 2 auditors of different orgs for released-products | yes (genesis) | none — derived from the public formula |
| roles-chain | the app's own organisations, actors and policies | no (registry empty) | one-time **bootstrap** by the app: generate app actor seeds (file or derived), propose org → actor (with key proof) → policy via node-0, approve via nodes 1–3 (4-of-5), activate. Idempotent; needs no operator secrets |
| registry-audit / approval-audit | none (member identity) | n/a | none |
| all other chains | none (member identity) | n/a | none |

Registry governance with the public `registry-admin-a` key is **not exposed** in the UI: revoking actors, rotating keys or
replacing the admin authority could lock the demo. The UI offers only curated, non-destructive operations.

### D5. Chain modules: server-side commands, descriptor-driven UI

Each chain is a backend **module** that declares:

- **Metadata:** chain id, title, one-line story, state machine and capability tags. These fill the chain catalog.
- **Commands:**
  - typed fields with constraints (length, regex, range);
  - mock-value generators (realistic names, amounts and ids, prefixed with the visitor's session tag);
  - the personas allowed;
  - a payload builder (CBOR);
  - an **outcome evaluator**.
- **Views:** read models built from state entries or proofs, plugin APIs and receipts, e.g. the approval item with its
  votes, a KV entry with its owner, balances, a document trail head, a workflow record, or binding receipts.

The UI renders commands and views from descriptors served by `GET /api/demo/chains`, so most changes are server-side.
Richer chains (workflow, document-review, bindings) get hand-built guided flows on top: stepper, persona hints and
"next step" suggestions.

### D6. Outcome evaluation instead of "accepted"

A submission is tracked through these stages:

1. **admitted** (202 plus messageId, or 400 with a code);
2. **finalized** (`GET messages/{id}` returns a height), polled by the backend;
3. **outcome**, from the module's evaluator:
   - a state entry changed as expected → *applied*;
   - no change → *no effect*, with the likely rule (for example "key is owned by Member 0", "already voted",
     "insufficient balance");
   - on binding chains, the receipt's `ACCEPTED`/`REJECTED <code>`;
4. **proven**: a state proof with its finality certificate (signers of 5, threshold 4);
5. **anchored**: the chain anchor catches up with that height, shown with the Cardano tx and an explorer link.

The UI shows this as a live timeline for each submission.

### D7. Public-demo safety

- **Rate limits on chain writes:** 20 per minute per client IP and 120 per minute globally, configurable, with 429 and
  Retry-After. Reads are not rate-limited by the app but are cached briefly.
- **Session namespacing.** Mock ids and keys carry a short per-browser session tag, e.g. `demo-7f3k-order-12`, so
  visitors don't collide or squat on each other's keys. Visitors can still browse all data.
- **Input caps below the known crash thresholds:**
  - KV keys ≤ 100 bytes; approval item ids ≤ 200 bytes;
  - document-review proposal ids `[a-z][a-z0-9-]{0,62}`;
  - payload ≤ 16 KiB; free text ≤ 280 characters.
- **One workflow release in flight** (serialized per chain), plus a global release budget (default 10 per hour).
  Two releases in one block can stall workflow-chain, and each release writes an outbox file on node-0.
- **Read-only chains:**
  - `payment-chain-settlement`: L1 deposits spend real tADA. Show bridge info, deposits, withdrawals and vault UTxOs.
  - `cardano-history-chain`: epoch parameters.
  - `payments-chain` in v1: the supply is a fixed 100 ADA in one UTxO from a public seed, and the ops showcase
    handler expects it untouched. A demo-wallet transfer flow is future work that needs an owner decision.
- **No arbitrary hex/topic submission, no privileged endpoints, no governance-destructive operations.**
- The UI shows a visible **"Public test network: everything you submit is permanent and public"** notice, and
  free-text fields carry a hint not to enter personal data.

### D8. Verification in the UI

- State proof panel: key, value, block, **finality certificate signers** (named by member), and optional server-side
  `proof/verify`.
- **"All members agree"** panel: the same height, state root and block hash read from every configured node.
- **Anchor panel:** anchored height, Cardano tx with an explorer link (`demo.explorer-tx-url`), and the lag in blocks.

### D9. Look and feel

- Yano X brand:
  - the logo (`logo.svg`, animated `logo-flying.svg` with a reduced-motion fallback, `favicon.svg` from the yano-x site);
  - site tokens: bg `#0b1115`, surface `#111c22`, ink `#eef4f5`, muted `#a7bac3`, line `#2b3b43`, accent `#9af0d4`,
    logo lime `#b9f57d`;
  - Space Grotesk headings, Inter body.
- Dark-first. A light theme follows the system setting.
- Layout: a catalog of chain cards (story, live height, anchor status); a chain workspace with Story | Try it | State |
  Verify tabs; a persona switcher; and a live submission timeline. Mobile-friendly.
- It must be self-explanatory: each command has a one-line "what this demonstrates" and "try next" hints.

### D10. Hosting on node-0

- Native binary under `/opt/yano-x-demo-client/<version>/`, with a `current` symlink.
- Runs as the unprivileged user `yano-demo` from a systemd unit with `MemoryMax=512M` and hardening
  (NoNewPrivileges, ProtectSystem=strict, PrivateTmp).
- Listens on `127.0.0.1:8090`. Config in `/etc/yano-x-demo-client/application.yml`, secrets 0600 in
  `/etc/yano-x-demo-client/keys/`.
- Existing Traefik (file provider) routes `client-showcase.yano-x.io` to it, with the same TLS and Cloudflare setup as
  the node hosts. Logs go to journald, which is already bounded on the VMs.
- Node URLs in production config are the **public** node URLs, not localhost, so the app is portable. Cost: one
  Cloudflare/Traefik hop and a shared 200 req/s budget.
- Deployment is an Ansible playbook in the cluster ops repo (`yano-cluster/preprod-contabo/pre3-ops`). This repo keeps
  generic assets under `deploy/`: a systemd unit template, an example config and an example Traefik route.

## 3. Consequences

- **Positive:**
  - one small, native, low-memory service;
  - no secrets needed from the operator;
  - no node URL lock-in;
  - every write is bounded and server-built;
  - the demo tells the "finalized → proven → anchored" story that differentiates Yano X.
- **Negative:**
  - per-chain modules duplicate a little of the release codecs; golden-vector tests pin them to the release;
  - silent-rejection chains need evaluators that know each state layout;
  - the demo can still be griefed within rate limits (unlimited mint, single-reject kills); session namespacing only
    limits the confusion.
- **Future work:**
  - payments-chain demo wallets (owner decision);
  - SSE fan-out for live cross-visitor activity;
  - light theme polish;
  - per-chain "tour" mode.

## 4. Delivery plan

1. Skeleton: config, node gateway, rate limiter, CBOR/BLAKE2b with golden tests, cluster overview API, UI shell with
   branding, node picker and chain catalog.
2. Member-persona chains: orders, registry, approvals, balances, documents, with outcome evaluators and state/proof views.
3. Workflow (guided order → approval → release, serialized) and the binding chains (receipts).
4. Actor chains: document-review, authenticated-map (MPF/JMT), then roles-chain bootstrap and staged approvals.
5. Read-only views: settlement, Cardano history, payments.
6. Verification panels (proof, all members agree, anchor).
7. Native build, deployment assets, node-0 deployment behind Traefik at client-showcase.yano-x.io.
