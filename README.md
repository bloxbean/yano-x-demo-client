# Yano X demo client

A guided, public demo client for a Yano X app-chain cluster. Visitors pick an app chain and submit realistic data
(editable examples). They act as the member organisations that run the cluster, or as named people who sign with
their own keys. They then follow each message: admitted, finalized by 4 of 5 members, evaluated against the chain's
rules, proven with a finality-certified state proof, and anchored on Cardano.

Live: **https://client-showcase.yano-x.io** (Yano X 0.1.0-pre3 preprod showcase, 5 members).

Architecture and decisions: [`docs/adr/0001-demo-client-architecture.md`](docs/adr/0001-demo-client-architecture.md).
Research notes (payload formats, identities, APIs, risks): [`docs/research/`](docs/research/).

## What it covers

| Chain | What visitors do | Identity |
|---|---|---|
| orders-chain (ordered-log) | append order events | member |
| registry-chain (kv-registry) | publish entries; non-owner writes are refused | member |
| balances-chain | mint and transfer; overdrafts are refused | member |
| documents-chain (doc-trail) | record document fingerprints; the trail head is recomputed and verified | member |
| approvals-chain | propose and vote to a quorum; duplicate and terminal votes are ignored | member |
| workflow-chain (composite) | register → propose → approve → release, with the outbox effect confirming on chain | member |
| document-review-chain | issuer proposes; two independent auditors approve; any member relays the release; On Cardano: mint a certified release NFT | actor (Ed25519) + member relay |
| registry-audit-chain, approval-audit-chain | declarative binding cascades with provable receipts | member |
| authenticated-map-chain (stock `authenticated-map` recipe) | issuer proposes a product release; two auditors of different organisations approve; a member writes it to `released-products`, consuming the approval once (people, roles and policy are in the chain's YAML) | actor (Ed25519) + member relay |
| roles-chain (stock `role-approval` recipe) | members onboard organisations, people and a policy at runtime by governance (`role bootstrap`); a proposer submits an application, a reviewer from another organisation approves or rejects; check an application against the decided hash | actor (Ed25519) + member relay |

Other chains on the cluster are listed as coming soon: authenticated-map-jmt-chain and the read-only
payments, settlement and Cardano history views.

## On Cardano: act on anchored facts

Optional (`demo.cardano.enabled`). Three Aiken validators on Cardano read a chain's **script anchor** as a reference
input, prove an app-ledger fact against its state root with the official
[`aiken-lang/merkle-patricia-forestry`](https://github.com/aiken-lang/merkle-patricia-forestry) (v2.1.0), and spend a
**nullifier** so the same fact is used once. The server holds a testnet wallet that pays the fees.

| Chain | On Cardano tab | The fact proven | Nullifier |
|---|---|---|---|
| document-review-chain | look up a released proposal, mint a **certified release NFT** | the release receipt (approval consumed by the release) | proposal id in the registry's on-chain MPF claimed set |
| orders-chain | lock ADA in a **delivery escrow**, release it to the supplier | the finalized-message record of `{"event":"delivered","order":…}` (optionally from the carrier's member) | the escrow UTxO itself |
| balances-chain | **snapshot rewards**: claim POINTS for a member's balance | `b/<member key> → amount` at the season's snapshot | account key in the season's claimed set |

The **Anchor & mint** page (`#/cardano`) shows each chain's anchored height and the blocks to the next anchor, and
holds the operator actions: open the certificate registry and a rewards season, give members points, and
**Populate until anchored** (demo writes until the chain is due: the showcase anchors every 30 blocks or at most
every 60 minutes). With a node admin key configured, **Anchor now** asks the members to anchor immediately.

- **Validators** live in `onchain/` (Aiken v1.1.x). `aiken build` there, then copy `onchain/plutus.json` to
  `src/main/resources/cardano/plutus.json`; both are checked in. `aiken check` runs 19 tests, including real
  showcase bytes (an order message, a release receipt and a balance proven against their anchored roots).
- **Parameters** (each chain's anchor identity, a one-shot seed UTxO per registry) are applied at runtime with
  julc's UPLC codec (`julc-core`), so the server needs no Aiken toolchain. `ScriptParamsTest` checks the result
  is the same program `aiken blueprint apply` produces.
- **Cardano access** goes through a member's own Blockfrost-compatible API with cardano-client-lib's supplier
  interfaces (`cardano/L1.java`): no Retrofit/OkHttp backend in the native image.
- **State**: registry and season deployments, recorded claims and escrows in `demo.cardano.state-file`. Only the
  claimed-set roots are on chain; the client checks its local set against them before every claim.
- **Safety in a public demo**: at most `transactions-per-hour` Cardano transactions (all visitors together), a fixed
  escrow amount, operator actions behind `operator-token-file`. The validators trust an anchored root because a
  threshold of the chain's members co-signed it; they cannot tell whether those members recorded the truth.

## Chain configuration view

Every chain (including ones without an interactive demo) has a **Config** tab with three views:

- **As written:** the chain's entry in the `<app>.draft.yaml` the operators sealed, plus its bindings or role plan.
- **Sealed genesis:** every setting fixed at seal (defaults included), from the sealed `<app>.chain`.
- **Running:** the configuration parts of the node's `/status` (consensus limits, membership, sequencer, state
  commitment, anchoring, effects, and for composite chains the components and workflow).

The first two need the public app-chain files: set `demo.config-dir` (see `deploy/example-application.yml`) or open a
file in the page (it stays in the browser). On a local cluster:

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance demo     # in the Yano X distribution
./mvnw quarkus:dev -Ddemo.config-dir=<distribution>/examples/showcase/data/showcase/demo ...
```

## How it works

- **Backend (Quarkus 3.39, Java 25).** It is the only client of the Yano nodes.
  - Node URLs come from configuration; none are compiled in.
  - It builds and validates every payload server-side, using a deterministic CBOR codec pinned by golden vectors from
    the release tooling.
  - It holds actor keys and signs statements with JDK Ed25519.
  - It rate-limits writes.
  - It judges outcomes from state (at h−1 vs h), per-message role result codes, or binding receipts, because many
    state machines refuse silently after finalization.
- **UI (React 19, Vite, Tailwind 4)** is served by the backend through Quinoa. It uses the Yano X brand in a light
  theme.
- **Native.** It ships as a GraalVM/Mandrel native executable, about 60 MB and about 50–120 MB RSS.

## Develop

```bash
# loopback tunnels to a cluster on 127.0.0.1:18070..18074 (see the "%dev" profile), then:
./mvnw quarkus:dev -Dquarkus.http.port=8095      # UI and API on http://localhost:8095 (Vite on 5188)
./mvnw test -Dquarkus.quinoa=false               # codec, key and signature golden vectors
```

## Build and deploy

```bash
# linux-amd64 native executable, built in a container (works from arm64 macOS)
./mvnw package -Dnative -DskipTests -Dquarkus.native.container-build=true \
  -Dquarkus.native.container-runtime-options=--platform=linux/amd64
```

The `deploy/` directory holds:
- a hardened systemd unit;
- an example configuration (nodes, actors, rate limits);
- an example Traefik route.

Behind a reverse proxy, set `quarkus.http.host-validation.allowed-hosts` to the public host name. Otherwise Quarkus,
bound to 127.0.0.1, answers every proxied request with 400.

The showcase deployment is managed from the cluster ops repository (`pre3-ops/ansible/demo-client.yml`).
