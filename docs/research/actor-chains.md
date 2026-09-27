# Yano X 0.1.0-pre3 showcase: actor/role/binding chains and the keys a demo client needs

Researched 2026-09-27. Sources are the pre3 source tree, the release kit, the deployed render and overlay, and **read-only GETs**
against `https://node{0..4}-showcase.yano-x.io`. I made no POST, PUT or DELETE calls.
Anything I could not verify is marked **UNVERIFIED**.

Abbreviations used below:

- `SRC` = `/Users/satya/work/yano-cluster/analysis/pre3-refresh-20260926/source`
- `KIT` = `/Users/satya/work/yano-cluster/preprod-contabo/pre3-ops/demo-runtime/yano-x-jvm-0.1.0-pre3`
- `SHOW` = `KIT/examples/showcase`
- `OPS` = `/Users/satya/work/yano-cluster/preprod-contabo/pre3-ops`
- `RENDER` = `/Users/satya/work/yano-cluster/preprod-contabo/cluster-pre3/.yano-deploy/render-2e07ad85a539-33ff7bfe0550-r30/ansible`

---

## 0. Cross-cutting facts (apply to every chain)

### 0.1 Two identities

- **Member (node) key, implicit.** The REST endpoint of node N wraps `{topic, bodyHex}` into an AppMessage signed with node N's
  member key. The machine sees that key as `message.getSender()`.
  - Evidence: `binding_demo.py` gets "non-owner put from node-1 → KV_NOT_OWNER" (`OPS/scripts/binding_demo.py:139`), and the
    recorded public run shows it (`/Users/satya/work/yano-cluster/evidence/pre3-20260926/demo/binding-live-public-pub1.json`).
  - Node → member key mapping on the live cluster (from the `roles-chain/status` memberKey on each node):

    | Node | Member key | Notes |
    |---|---|---|
    | node0 | `2d71529f…782e` | fixed proposer |
    | node1 | `020924a9…6455` | |
    | node2 | `4aae46d4…bbce4` | |
    | node3 | `a8d9dd49…451c` | |
    | node4 | `66e6a91e…14e0` | |

  - Every chain has 5 members with finality threshold 4 (`RENDER/templates/node.properties.j2`, `members=`/`threshold=4`).
- **Actor key, explicit.** An Ed25519 signature carried inside the payload. It is verified against a public key committed in
  the chain's domain-actor registry. Relaying nodes add no business vote
  (`SRC/docs/APP_CHAIN_DOMAIN_ROLES.md` §2, §4; `SRC/capabilities/role-workflow/README.md`).

### 0.2 Public API surface

- **Submit and read need no API key.** `POST /api/v1/app-chain/chains/{chain}/messages` and all GET reads work without a key.
  The runbook says: "Public read/submit endpoints are unauthenticated by design (showcase); admin endpoints need the API key
  file" (`/Users/satya/work/yano-cluster/REMOTE_VM_DEPLOYMENT_RUNBOOK.md` §17, ~line 317).
  - `DEMO_PUBLIC=1 binding_demo.py` ran with no key through all 5 public hosts (evidence file above).
  - Verified by GET: `/admin/members` returns `401 Missing or invalid X-API-Key`; plugin routes return 200/404 without a key.
- **CORS** allows only `localhost:4173` origins (runbook §17; `RENDER/templates/traefik-yano.yml.j2`).
  - A browser app must call its own backend, and the backend calls the nodes. That matches the planned design.
- **Rate limit** per gateway: average 200, burst 400, keyed on the Cloudflare `CF-Connecting-IP` header
  (`RENDER/inventory.yml:40-43`).
- **Blocks are produced only when messages arrive** (runbook L-23). All height-based deadlines and expiries therefore advance
  only with traffic.

### 0.3 Demo actor seed formula (public)

`seed = SHA-256(UTF-8("yano-showcase-demo-actor:" + actorId))`. This is a 32-byte raw Ed25519 seed (RFC 8032).
It is defined in two places:

- `SHOW/showcase.sh:1201-1205` (`demo_actor_seed`)
- `SRC/examples/showcase/src/main/java/org/yanoproject/x/showcase/ShowcaseAuthenticatedMapConfig.java:193-205`, whose
  comment says: "Demo-only … Never reuse outside the packaged showcase."

The signer is `SHOW/tools/showcase_signer.py` (pure Python RFC 8032). I checked that it reproduces the CLI signature
byte for byte (§2.1).

| Actor | Org | Role | Key id | Derived public key (matches genesis and live chain) |
|---|---|---|---|---|
| registry-admin-a | acme-manufacturing | registry-admin | registry-admin-a-k1 | `88b06723691dad781e5419a47516de3b82418a35de2b3559a9d915fd6cedfa67` |
| issuer-a | acme-manufacturing | issuer | issuer-a-k1 | `67ddc1ac79b76d0e6abae1c133195fa48904948af3b202b761a4c727c4e169ff` |
| auditor-a | auditor-guild-a | auditor | auditor-a-k1 | `f19f6042d44cb82663d9357435c9ed71bece07a7f5fe58fcaaa0c3732581f612` |
| auditor-b | auditor-guild-b | auditor | auditor-b-k1 | `b0dc3aadb1754c025699b57789292055d100dddee5e868c569dee8317898c8d2` |

The other example uses a different formula: `yano-x-examples/03-batch-release-java` `Cast.java:38-43` computes
`SHA-256("yano-x-example-03-demo-actor\0" || id)`. Those actors are **not** registered on any pre3 showcase chain.

### 0.4 Wire formats that are the same on every chain (`SRC/capabilities/role-workflow-contracts/src/main/resources/META-INF/yano/contracts/role-workflow/v1/role-workflow-v1.cddl`)

**Identifiers**

- identifier: `[a-z][a-z0-9-]{0,62}` (`RoleWorkflowIdentifiers.java:16`). This is stricter than some app-level regexes; see §2.6.
- payload-domain: `[a-z][a-z0-9.-]{0,63}`.

**Actor statement and signed command**

- `actor-statement` = `[1, action(0 propose|1 approve|2 reject|3 cancel), chainId, proposalId, policyId, policyRev,
  payloadDomain, payloadHash(32), deadlineHeight, actorId, actorRev, keyId, clauseId-or-""]`
  - approve and reject require a clause; propose and cancel must use `""` (`ActorStatementV1.java:24-38`).
- `signed-actor-command` = `[1, bstr(statement), bstr(sig64)]`.
- Signing preimage = `"yano:role-approval:v1\0" || u32be(len(statement)) || statement` (`ActorStatementV1.java:21,63-67`).
- CLI: `KIT/yano.sh appchain role sign --action … --seed-file f` (`RoleWorkflowCli.java:37-41`).

**Key proof-of-possession**

- `key-proof` = `[1, chainId, actorId, actorRev, [keyId, pub32, validFrom, validUntil, status], sig64]`.
- Preimage = `"yano:actor-key-proof:v1\0" || u32be(len) || unsigned` (`ActorKeyProofV1.java:17-18,77-79`).
- CLI: `appchain role key-proof` or `appchain role key-proof-signature`.

**Registry records**

- `registry-mutation` = `[1, op(0 org|1 actor|2 authority), bstr(record), [key-proof bstr…]]` (`RegistryMutationV1.java:55-80`).
- org record = `[1, orgId, rev, status(0 ACTIVE|1 SUSPENDED|2 REVOKED), metadata(0|32 bytes)]`.
- actor record = `[1, actorId, orgId, rev, status, [roles sorted], [key-epochs sorted by keyId], metadata]`.

**Command results**

- Result codes 0..22: ACCEPTED, INVALID_PAYLOAD, …, DISTINCTNESS_DUPLICATE, GOVERNANCE_THRESHOLD_NOT_MET, …, NOT_READY, …
- They are stored at `r/c/<msgIdHex>` on the genesis-bound chains (authmap, document-review) but **not** on roles-chain (§1.2).

**Physical keys**

- Physical state keys are composite-namespaced:
  `"yano-composite-state-v1\0" || u8(len comp) || comp || u16be(len local) || local` (`SHOW/tools/showcase_codec.py` `composite-key`).
- Local key layouts:
  - `a/{actor}/current`, `a/{actor}/r/{rev}`
  - `o/{org}/…`, `p/{policy}/…`, `d/{directPolicy}/…`, `h/{authority}/…`
  - `q/{proposal}`, `g/{mutationId}`, `s/proposals/v1`
  - a `*/current` value is a raw 8-byte big-endian revision.

---

## 1. roles-chain (state machine `role-approvals`, profile `role-approvals-v1`)

### 1.1 Configuration and live state

- Deployed with no machine settings, so it uses the stock preset (`RENDER/files/application-appchain.yml:101-111`).
- The live genesis id is `c8e8ddee…` (overlay fresh id). Tip height is 2 (two `role-probe` no-ops).
- `RoleApprovalsPreset.java` composes:
  - `domain-actors`, a member-governed registry on topic `actors.command.v1`;
  - `role-approvals`, the policy store;
  - workflow `role-approval` on topic `role-approvals.command.v1`.
- **The registry is empty at genesis.** There are no organizations, actors or policies.
- Verified live by GET:

  | GET | Result |
  |---|---|
  | `/api/v1/plugins/org.yanoproject.x.role-workflow/actors/issuer-a?chain=roles-chain` | `404 {"error":"not-found"}` (empty record; the route itself exists: a bogus route gives "No domain API route matches") |
  | `…/policies/product-release?chain=roles-chain` | 404 not-found |
  | `…/stats?chain=roles-chain` | 404 |
  | state proof of composite `role-approvals`/`s/proposals/v1` | `ABSENT` |

### 1.2 Identity and authorization (source cited)

- **Administrators are the genesis members.** `RoleWorkflowGovernanceConfig.from()` takes `membershipView().epochAt(1).members()`
  as administrators and uses the genesis threshold. Live that means 5 administrators, threshold 4. Maximum mutation lifetime is
  1000 blocks (`RoleWorkflowGovernanceConfig.java:43-52`).
- **Organization, actor and policy changes are member-governed.** They use `GovernedMutationCommandV1`
  (`GovernedMutationProcessor.java:18-50`):
  - `governed-command` = `[1, op(0 propose|1 approve|2 activate|3 cancel), mutationId, mutation (propose) | SHA-256(mutation) (others), expiryHeight (propose) | 0]`.
  - The processor ignores commands whose `sender` is not a member.
  - PROPOSE records the proposer's own approval.
  - APPROVE adds `sender` to the approval set, so duplicates count once.
  - ACTIVATE needs at least 4 distinct member approvals; any admin member may send it.
  - CANCEL is accepted only from the proposer member.
  - Expiry must satisfy `height < expiry ≤ height + 1000`.
  - Organization and actor mutations go on `actors.command.v1`. Policy mutations
    (`policy-mutation = [1, op(0 put approval policy|1 cancel proposal|2 put direct policy), bstr(payload)]`) go on
    `role-approvals.command.v1` (`RoleApprovalWorkflow.java:73-100`).
- **Actors sign decisions.** `role-approvals.command.v1` also carries `SignedActorCommandV1`. The processor applies them with
  `ActorApprovalProcessor(chainId, defaults)`, which puts **no payload-domain restriction** on roles-chain.
- **No result records on roles-chain.** This workflow writes no `r/c/<msg>` command-result records. The outcome of a
  submission can only be read from the resulting proposal or governance state.
- **The CLI covers only part of this** (`RoleWorkflowCli.java:33-72`):
  - it has `role govern-propose|govern-approve|govern-activate`, `role key-proof`, `role public-key` and `role sign`;
  - it has **no encoder for org/actor/policy records**. Build those with the Java contracts
    (`org.yanoproject.x.roles.contracts.*`; the classes ship inside `KIT/plugins/yano-x-role-workflow-bundle-0.1.0-pre3.jar`
    and in `~/.m2/.../yano-x-role-workflow-contracts`) or with hand-written CBOR (example below).
  - `yano-x-examples/03-batch-release-java` `Registry.java:150-175` is a complete worked example of PROPOSE via member 0,
    APPROVE via member 1, then ACTIVATE.

### 1.3 Can a demo client self-register actors? Yes, without any secrets (live submission UNVERIFIED)

The procedure:

1. Choose actor seeds. For example reuse the published formula with new ids, such as
   `sha256("yano-showcase-demo-actor:demo-reviewer-a")`, or use any new random seeds held server-side.
2. Build the org mutation, then the actor mutation with key proofs signed by each actor seed for `chainId=roles-chain`, then
   the policy mutation(s).
3. Submit each mutation in four steps:
   - PROPOSE via node0;
   - APPROVE via node1, node2 and node3;
   - ACTIVATE via any node.
4. Wait for finality between steps. Order matters: the organization must be active before the actor, because the actor record
   needs an ACTIVE organization.

No member or admin secret is needed, because every public node signs with its own member key server-side. This follows from
the code and from the binding demo's per-node sender behaviour. I did **not** submit anything, so live success is UNVERIFIED.

Once registered, the demo actors are chain state that the client itself created, and their seeds live in the client backend.

### 1.4 Example hex (produced locally with the release CLI; mutation id `org-demo-org-a-r1`, expiry 900)

**Organization `demo-org-a`**

```
ORG_MUT    = 8401005085016a64656d6f2d6f72672d6101004080
SHA256     = e8cb7eae550eb74753407d0c7ebe8ee0ba88d88e39c54116e7722f317d4d23d6
PROPOSE    = 850100716f72672d64656d6f2d6f72672d612d7231558401005085016a64656d6f2d6f72672d6101004080190384
APPROVE    = 850101716f72672d64656d6f2d6f72672d612d72315820e8cb7eae550eb74753407d0c7ebe8ee0ba88d88e39c54116e7722f317d4d23d600
ACTIVATE   = 850102716f72672d64656d6f2d6f72672d612d72315820e8cb7eae550eb74753407d0c7ebe8ee0ba88d88e39c54116e7722f317d4d23d600
```

**Key proof for `demo-reviewer-a`** (seed `cd227c98…e65a`, pub `72691b14e1c546a5380459bd4e107859228023b3ba7526a673519dc01de141b3`)

```
86016b726f6c65732d636861696e6f64656d6f2d72657669657765722d6101857264656d6f2d72657669657765722d612d6b31582072691b14e1c546a5380459bd4e107859228023b3ba7526a673519dc01de141b30100005840315ce755cd164bdd26dbe972abbc28866554def9f3d181ae91f9bc4fe9c020bc3e0051dfe61a05ff67148c0fe6c198e94034404028c0cabd3db8f3701d44a005
```

Governance messages to `actors.command.v1` use `bodyHex` set to the governed-command hex.

The existing `role-probe` (`showcase_codec.py role-probe`) is `[1,1,id,sha256(id),0]`, an APPROVE of an unknown mutation.
It is a deliberate no-op.

### 1.5 Read APIs (no key needed)

- **GET** `/api/v1/plugins/org.yanoproject.x.role-workflow/{organizations|actors|policies}/{id}?chain=roles-chain[&revision=N]`,
  plus `/proposals/{id}` and `/stats` (`RoleApprovalsDomainApi.java:41-45`).
  - Each response includes `committedHeight`, `stateRoot`, `proofKey`, `recordValue`, and, when the current revision is
    resolved, the current-pointer proof key and value.
- **Committed queries** (POST, read-only): `/api/v1/app-chain/chains/roles-chain/query/components/domain-actors/actor-current`
  with `{paramsHex: hex(id)}`, and similar.
- **Proofs:** `GET /api/v1/app-chain/chains/roles-chain/state/proof/{proofKeyHex}[?height=h]`, then `POST …/proof/verify`.
- **Governance mutation record** is not exposed as a domain route on roles-chain. Read `g/{mutationId}` under component
  `domain-actors` (org/actor) or `role-approvals` (policy) through a state proof. **UNVERIFIED** which component holds policy
  governance records. The code writes via `approvalState`, so it should be `role-approvals`.

### 1.6 Demo flows and mock defaults

- **Onboarding scene:** create organizations and actors, showing the 4-of-5 member governance
  (propose, then three approvals, then activate).
- **Multi-stage workflow** modelled on example 03:
  - `qc-results`: 2 QC, DistinctBy.ACTOR
  - `qa-review`: 1 QA
  - `qp-release`: 1 QP + 2 auditors, DistinctBy.ORGANIZATION
- **Stage chaining:** each stage's payload hash commits to the previous stage's proposal id and hash.
- **Negative cases:**
  - same-organization auditors satisfy the clause only once;
  - wrong role gives a no-op;
  - a statement signed with an old actor revision after key rotation is rejected;
  - a revoked actor is rejected;
  - a rejection by an eligible actor ends the proposal as REJECTED.
- **Default policy suggestion:** rejection mode ANY_ELIGIBLE; maximum lifetime ≤ 1000 blocks.
- **Payload domain:** any string matching `[a-z][a-z0-9.-]{0,63}`, for example `com.demo.batch.v1`.

---

## 2. document-review-chain (state machine `document-review`, profile `document-role-approval-v1`)

### 2.1 Configuration and live state

- The genesis is **compiled into the plugin**, not configured.
  - `DocumentReviewPreset.create()` calls `ShowcaseAuthenticatedMapConfig.documentReviewGenesis(chainId)`
    (`…/showcase/DocumentReviewPreset.java:30-35`; `ShowcaseAuthenticatedMapConfig.java:218-266`).
  - The genesis-bound registry uses **actor-signed administration**: authority `registry-admins` rev 1, administrators
    `[registry-admin-a]`, threshold 1, lifetime 1000.
  - Organizations: acme-manufacturing, auditor-guild-a, auditor-guild-b.
  - Actors: the 4 demo actors in the table in §0.3, each with key `<id>-k1`, valid from height 1.
  - Approval policy `document-release` rev 1:
    - proposer roles `[issuer]`;
    - clause `independent-auditors`: role auditor, minimum 2, DistinctBy.ORGANIZATION;
    - rejection mode ANY_ELIGIBLE;
    - maximum lifetime 600 blocks.
  - There are no direct policies.
- **The demo actors are already registered live.** I checked with GET state proofs of composite `domain-actors`/`a/<id>/r/1`
  on `document-review-chain`: all four are `PRESENT`, and each record value embeds the public key listed above.
  - `role-approvals`/`p/document-release/r/1` is PRESENT.
  - `domain-actors`/`h/registry-admins/r/1` is PRESENT, with value `[1,"registry-admins",1,["registry-admin-a"],1,1000]`.
  - Stats `s/proposals/v1` = `[1, created 1, pending 0, approved 1, 0, 0, 0]`, from the one earlier `showcase-demo.sh` run.
  - Tip height is 4.
- **The admin-statement genesis id is not the chain's `state.genesis-id`.** It is
  `applicationId = blake2b-256(GovernedGenesisV1.encode())`. The live status `capabilityManifest` shows it as
  `document-role-approval-v1:819200b17ab76f3518b1b6605b923ce3fd071bd6dd38bb046c7f2b63d74eebd8`, so applicationId = `819200b1…ebd8`.
  I read it from the status JSON and did not recompute it (**UNVERIFIED** recompute).

### 2.2 Topics and encodings

**Actor proposals and decisions**

- Topic `role-approvals.command.v1`, body `SignedActorCommandV1` (§0.4).
- `chainId=document-review-chain`, `policy=document-release`, `policyRevision=1`, `payloadDomain=document.review.release.v1`
  (enforced; `DocumentReviewCommandV1.PAYLOAD_DOMAIN`).
- `payloadHash = blake2b-256(release command bytes)`.
- PROPOSE comes from issuer-a with clause `""`. APPROVE or REJECT come from auditors with clause `independent-auditors`.

**Release**

- Topic `document-review.release.v1`.
- Body = `[1, proposalId, "document-release", 1, entityId, bstr(docHash32), reference]` (`DocumentReviewCommandV1.java:44-56`).
- Constraints:
  - proposalId and entityId must match `[a-z0-9][a-z0-9._-]{0,127}`;
  - docHash is exactly 32 bytes;
  - reference is at most 512 UTF-8 bytes;
  - encoding is canonical.
- **Any member may relay the release. No actor key is needed for it.**
- The release applies only once the proposal is APPROVED and the payload hash equals the commitment. The approval is consumed
  exactly once (`DocumentReviewWorkflow.java`), which appends the doc-trail entry and writes the receipt.
- Codec: `showcase_codec.py document-review <proposal> <entity> <hash64> <ref>`.

**Worked example** (`demo1`, deadline 304; signatures produced locally from the public demo seeds; the Python signer matched
the CLI byte for byte)

```
DR_CMD      = 870175646f63756d656e742d7265766965772d64656d6f3170646f63756d656e742d72656c65617365016e646f63756d656e742d64656d6f31582027ae8fec57354a0bcf499207fc2ce817ac7ba923e4ca9bb4f9ffb35120a9e73f782373686f77636173653a2f2f646f63756d656e74732f646f63756d656e742d64656d6f31
DR_COMMIT   = d7734cebc4bdb339f3fa23227cf5c32800945185db87e2d03b263c346322e3b9   (blake2b-256 of DR_CMD)
PROPOSE(issuer-a) = 830158998d010075646f63756d656e742d7265766965772d636861696e75646f63756d656e742d7265766965772d64656d6f3170646f63756d656e742d72656c6561736501781a646f63756d656e742e7265766965772e72656c656173652e76315820d7734cebc4bdb339f3fa23227cf5c32800945185db87e2d03b263c346322e3b9190130686973737565722d61016b6973737565722d612d6b3160584050d7eb3ac30713a2ca0c0952ac5baada315ccea089a4625c8f5e2bae8ce38a1218dfa5664ca023808d966f29ad4bc611d2680134412096d0aadb676db15ddc0c
APPROVE(auditor-a)= 830158af8d010175646f63756d656e742d7265766965772d636861696e75646f63756d656e742d7265766965772d64656d6f3170646f63756d656e742d72656c6561736501781a646f63756d656e742e7265766965772e72656c656173652e76315820d7734cebc4bdb339f3fa23227cf5c32800945185db87e2d03b263c346322e3b91901306961756469746f722d61016c61756469746f722d612d6b3174696e646570656e64656e742d61756469746f72735840d10561a898c5868a58998d2527f03ec8dfeb67246d8ff6853c14ea8b19429f2f4dc27186c4a95cad1f88d0516934a22da7b91785c06fb390152cd6fed66dda08
```

The deadline must satisfy `deadline ≤ createdHeight + 600`. The showcase uses tip + 300 (`SHOW/showcase.sh:1389-1397`).

**Actor governance (registering new actors)**

- Topic `actors.command.v1` (organizations, actors, authority) or `role-approvals.command.v1` (policies).
- Body = `actor-governance-command` = `[1, op(0 propose|1 approve|2 activate|3 cancel), mutationId, mutation-or-empty, [signed-administrator-statement…]]`.
- Each `administrator-statement` is:
  `[1, decision(0 propose|1 approve|2 cancel), chainId, genesisId32 (the applicationId above), "registry-admins", 1, mutationId, mutationHash, notBeforeHeight, expiryHeight, actorId, actorRev, keyId, pub32, issuedHeight, deadlineHeight, 0]`.
- Hashes and preimages:
  - `mutationHash = blake2b-256("yano:authenticated-map:governed-mutation:v1\0" || u32be(len) || mutation)` (`ActorGovernanceCommandV1.java:27-58`);
  - statement preimage domain `"yano:authenticated-map:administrator:v1\0"` (`AdministratorStatementV1.java:35`).
- Rules (`ActorGovernanceProcessor.java:170-420`):
  - PROPOSE requires `notBefore ≥ inclusion height` and `expiry ≤ height + 1000`;
  - `issued ≤ height < deadline`;
  - the admin actor, its organization and its key must be ACTIVE;
  - the proposer's vote counts, so threshold 1 is met at once;
  - ACTIVATE carries an empty authorization list, is valid only when `notBefore ≤ height ≤ expiry`, and any member may relay it.
- **There is no CLI subcommand for administrator statements.** Use the Java contracts
  (`SignedAdministratorStatementV1.sign`, `ActorGovernanceCommandV1`, `RegistryMutationV1.PutActor`, `ActorKeyProofV1.sign`)
  or reimplement the CBOR by hand.

### 2.3 Conclusion

- **(a) Yes.** The four demo actors are registered live, and their seeds are derivable from the public release formula.
  A demo client can act as issuer-a, auditor-a or auditor-b right away. Relaying through any node is enough.
- **(b) Yes, without operator secrets (UNVERIFIED live).** New actors (for example `auditor-c` in `auditor-guild-a`, to show
  organization distinctness) can be added:
  1. registry-admin-a signs an administrator statement with its public demo seed; the actor mutation carries the new key's
     proof-of-possession; submit the PROPOSE.
  2. Submit the ACTIVATE once the height reaches notBefore. Blocks appear only with traffic, so either set
     notBefore = the expected inclusion height, or keep sending (harmless NOT_READY) activates or other traffic until the
     height is reached.
  - This needs custom code; the CLI does not cover it.
- **(c) Not needed.**
- Caveat: anyone on the internet can do the same, because the seeds are public (§7).

### 2.4 Read APIs

- **The role-workflow domain API is broken on this chain.** `GET /api/v1/plugins/org.yanoproject.x.role-workflow/{actors|stats}/…?chain=document-review-chain`
  returns **HTTP 500 "Domain API execution failed"**, and the stdlib API does too. Verified live. Use these instead:
  - committed queries (POST): `/query/components/domain-actors/actor-current` and `…/actor` (params `id` or `id@rev`),
    `/query/components/role-approvals/{policy|policy-current|proposal|stats}`,
    `/query/components/documents/head` (params entityId),
    `/query/components/document-review-receipts/receipt` (params `hex(proposalId)`; see `SHOW/showcase.sh:1419-1423`);
  - GET state proofs of composite keys, for example `domain-actors`/`a/<id>/r/1`, `role-approvals`/`q/<proposal>`,
    `role-approvals`/`r/c/<msgIdHex>` (per-message result code), and `documents`/`e/<entity>`.

### 2.5 Demo flows and mock defaults

- **Happy path:**
  1. Hash the document (sha256 of the content, 32 bytes).
  2. Build DR_CMD, then commitment = blake2b(DR_CMD).
  3. issuer-a proposes with deadline tip + 300.
  4. auditor-a approves, then auditor-b approves; the proposal becomes APPROVED.
  5. Any member relays the release. The document head advances and the receipt appears.
  6. Show the proof.
- **Negative cases:**
  - auditor-a approves twice: DISTINCTNESS_DUPLICATE or EXACT_REPLAY no-op; the `r/c/<msg>` code shows which;
  - an issuer tries to approve: ROLE_MISMATCH;
  - an auditor rejects: REJECTED (ANY_ELIGIBLE);
  - a release with a different document hash has no matching approval: no-op;
  - replaying the release: no-op, because the receipt already exists;
  - an optional new auditor-c in guild-a (from (b)) shows two approvals from the same organization counting once.
- Mock defaults: entity `document-<suffix>`, reference `showcase://documents/<entity>`, proposal `document-review-<suffix>`.

### 2.6 Risk: identifier regexes do not agree

- The codec and `DocumentReviewCommandV1` accept proposal ids `[a-z0-9][a-z0-9._-]{0,127}`.
- The role workflow requires `[a-z][a-z0-9-]{0,62}`.
- A proposal id that starts with a digit, or contains `.` or `_`, or exceeds 63 characters, is valid for the release but can
  never be proposed.
- The client should enforce the intersection: `[a-z][a-z0-9-]{0,62}`.

---

## 3. authenticated-map-chain and authenticated-map-jmt-chain (state machine `authenticated-map`, profile `authenticated-map-v1`)

### 3.1 Configuration and live state

- The genesis is in `yano.app-chain.chains[8|9].machines.authenticated-map.genesis-cbor-hex` (`RENDER/templates/node.properties.j2:191,195`).
- I decoded it with a local CBOR decoder (`scratchpad/cbordump.py`). It contains:
  - collections:

    | Collection | Auth (1 owner, 2 member, 3 governed-role, 4 approval) | Max key bytes | Max value bytes | Encoding / validator / policy |
    |---|---|---|---|---|
    | attachments | owner | 64 | 32768 | opaque |
    | canonical-events | member | 64 | 16384 | canonical CBOR |
    | governed-catalog | governed-role | 64 | 4096 | policy `issuer-write` |
    | gtins | owner | 14 | 15 | validator `gs1-gtin-v1` |
    | products | owner | 64 | 4096 | CBOR schema `product-v1` |
    | released-products | approval | 64 | 4096 | policy `product-release` |

  - governed genesis: the same organizations, the same four actors and keys as document-review (key proofs are bound per chain
    id), authority `registry-admins` `[registry-admin-a]` threshold 1 lifetime 1000;
  - direct policy `issuer-write`: role `issuer`, maximum authorization lifetime 100 blocks;
  - approval policy `product-release`: issuer proposes; 2 auditors, DistinctBy.ORGANIZATION; ANY_ELIGIBLE; lifetime 600.
- **Verified live by GET** on `/api/v1/plugins/org.yanoproject.x.stdlib/…?chain=authenticated-map-chain`:
  - `actors/issuer-a` and `actors/registry-admin-a` are ACTIVE with the public keys in §0.3;
  - `authenticated-map/administrator-authorities/registry-admins` is `{registry-admin-a, threshold 1}`;
  - `policies/product-release` and `authenticated-map/direct-policies/issuer-write` are present;
  - `stats`: created 1, approved 1;
  - `pending/actor-governance`: empty;
  - the JMT chain has `actors/auditor-b` present.
- **Admin statements and direct evidence use the authenticated-map genesis id, not the chain genesis id.**
  - authenticated-map-chain: `3d61b633afbd3409c41e7763d93f47bc8d7bd2b512bebba9e48cd3a1e49d6e24`
  - authenticated-map-jmt-chain: `116b8e3c3a05da22f33b9988617c8c5de516f86d7854e1a7004f96325dd408cb`
  - Read either from GET `/api/v1/plugins/org.yanoproject.x.stdlib/authenticated-map?chain=…` → `record.genesisId`.
  - The chain-listing genesis ids (`95489846…`, `a64a36eb…`) are the overlay's fresh `state.genesis-id` values and must not be
    used here.

### 3.2 Topics and encodings

**Topic `authenticated-map.command.v1`**, final envelope = `[1, bstr(action), [evidence…]]`.

- The machine accepts only the final v1 envelope (`SHOW/showcase.sh:1043-1052`).
- Build it offline:
  1. Legacy mutation: `showcase_codec.py authmap put <collection> <key> <valueHex>`
     = `[1, 0, [[0, collection, bstr key, bstr value, 0, h'', h'']]]` (bounds in the codec: collection ≤ 64 bytes, key ≤ 128).
  2. `yano.sh appchain authenticated-map action --command-hex L --assignments "0:<owner|member|governed-role|approval>:<policy>:<evidenceIndex>"`.
  3. `yano.sh appchain authenticated-map command --action-hex A --evidence-hex <E or ''>` (`AuthenticatedMapAuthorizationCli.java:21-27`).

**Owner put example** (no actor key; identity = the relaying node's member key, which becomes the controller of the key):

```
CMD_OWNER = 8301583d8401008187006b6174746163686d656e7473506174746163686d656e742d64656d6f315064656d6f2d62797465732d64656d6f3100404081840001600080
```

**Governed-role write to governed-catalog** (issuer-a signs with its public demo seed)

- Direct-preimage arguments: `--authorization-id <unique 32B> --chain authenticated-map-chain --genesis-id 3d61… --indexes 0 --policy issuer-write --policy-revision 1 --actor issuer-a --actor-revision 1 --key issuer-a-k1 --public-key 67dd… --issued-height H --deadline-height H+≤99`.
- Then: sign the preimage, run `direct-complete`, run `command`.
- Example with H=20, deadline 110, authz = sha256("yano-showcase-authz:demo1"):

```
CMD_GOV = 8301584f84010081870070676f7665726e65642d636174616c6f674d636174616c6f672d64656d6f3154676f7665726e65642d656e7472792d64656d6f31004040818400036c6973737565722d777269746501818200590112830158cc8f0158207f8f9d382067ae3e7f9ec0ddf5ebe9a53abebb4f540544187b97d35ccefbd8ab7761757468656e746963617465642d6d61702d636861696e58203d61b633afbd3409c41e7763d93f47bc8d7bd2b512bebba9e48cd3a1e49d6e2458207ec152e7850f4564736842130f29cbe63e5f61cd60a9094ed774ad2d648ec8a581006c6973737565722d777269746501686973737565722d61016b6973737565722d612d6b31582067ddc1ac79b76d0e6abae1c133195fa48904948af3b202b761a4c727c4e169ff14186e005840a6454bda072911a27f64ed8d96529b5271bc9a5fd9b2c890b97805d3b3f5fcc856493da0226b8cbc6d75a360abc3fcbefad1869bc9be9753d33f9ff47501520e
```

This example is valid only if submitted while the tip is between 20 and 109; regenerate it from the live tip.
Each `(actor, authorizationId)` is consumed exactly once.

**Approval flow for released-products**

1. `action --assignments 0:approval:product-release:1`.
2. `approval-payload --action-hex A --genesis-id G` gives payloadHash (example `689d02fc…c59b` for key `release-demo1`).
3. Actors sign statements on `role-approvals.command.v1` with payloadDomain `yano.authenticated-map.action.v1` and policy
   `product-release` rev 1: issuer-a PROPOSE, then auditor-a and auditor-b APPROVE with clause `independent-auditors`.
4. After APPROVED: `approval-reference --action-hex A --proposal P --indexes 0 --policy product-release --policy-revision 1`
   gives evidence E.
5. `command --action-hex A --evidence-hex E`; any member relays it.

The whole sequence is scripted in `SHOW/showcase.sh:1273-1323`. Example final command for proposal `release-demo1`:

```
830158558401008187007172656c65617365642d70726f64756374734d72656c656173652d64656d6f315672656c65617365642d70726f647563742d64656d6f31004040818400046f70726f647563742d72656c6561736501818201584586016d72656c656173652d64656d6f3158205ae8fd3894197a61271044fd7ffa3699672863dcb2d5874a3793ff0c512a6e4e81006f70726f647563742d72656c6561736501
```

Nothing in it is height-bound; the evidence item is the approval reference `86016d72656c656173652d64656d6f31…`.

**Value codecs** (`showcase_codec.py authmap-value`):

- opaque: raw bytes;
- event: `[name, seq]`;
- product: canonical map `{note?, quantity, sku, status}`, with status in `{active, held, retired}` per the schema IR. `unknown`
  is rejected at admission.
- gtin: text, checksum-validated. `95012346` is accepted; `95012345` is rejected.

**Member collection canonical-events**: any active member sender may write it.

### 3.3 Conclusion

Same answer as document-review:

- **(a) Yes.** issuer-a, auditor-a, auditor-b and registry-admin-a are registered on both chains, and their seeds follow the
  public formula.
- **(b) Yes, via registry-admin-a** with its public seed and custom code (`actors.command.v1`). There is no CLI for it.
  This is also how a client could add its own named people ("Alice @ auditor-guild-a").
- **(c) Not needed.**
- **The JMT chain is barely used.** Tip height is 1. The governed flow has never run there but should behave the same
  (UNVERIFIED live).

### 3.4 Read APIs (GET, no key)

Base: `/api/v1/plugins/org.yanoproject.x.stdlib/`

- `authenticated-map` (metadata and collections)
- `authenticated-map/entries/{collection}/{keyHex}`
- `authenticated-map/receipts/{messageId}` (applied or rejection receipt)
- `authenticated-map/direct-consumptions/{actor}/{authzId}`
- `authenticated-map/approval-consumptions/{proposal}`
- `authenticated-map/direct-policies/{id}`, `authenticated-map/administrator-authorities/{id}`
- `authenticated-map/actor-governance/{id}`, `authenticated-map/policy-governance/{id}`
- `authenticated-map/command-results/{component}/{messageId}` (role-workflow result codes)
- `authenticated-map/pending/{approvals|actor-governance|policy-governance}`
- the shared `organizations|actors|policies|proposals/{id}` and `stats`

All take `?chain=…` (`AuthenticatedMapDomainApi.java:59-77`). Each record returns `proofKey`, `recordValue` and root.

Also available:

- POST `/query/authenticated-map/entry-v1` (paramsHex = `showcase_codec.py authmap query …`) returns a root-attested entry;
- GET `/state/proof/{proofKey}`.

### 3.5 Demo flows

1. Owner put, then a non-owner update from another node is rejected.
2. Member put of canonical-events.
3. Schema-rejected product and GTIN checksum rejection (admission rejects with HTTP 4xx, so nothing is sequenced).
4. Direct-role write by issuer-a. Replaying the same authorization id is rejected; auditor-a signing gives a role mismatch.
5. Approval: issuer proposes, 2 auditor organizations approve, the approved execution runs once, and a second execution is a
   replay rejection.
6. Governance (admin view): registry-admin-a onboards a new auditor or rotates a key.

Keys over the collection's `maxKeyBytes` (64, or 14 for gtins) are rejected at admission.

---

## 4. registry-audit-chain and approval-audit-chain (`declarative-composite`, operator-owned overlay)

### 4.1 Configuration

- Defined by the overlay (`OPS/ansible/templates/pre3-overlay.properties.j2:33-64`): fixed profile, `binding-ir` from
  `OPS/overlay/bindings/out/<chain>/binding-ir.hex`, same 5 members, threshold 4.
- **registry-audit-chain** (`…/registry-to-audit.yaml`):
  - components: `records` (kv-registry) and `audit` (doc-trail);
  - binding `audit-record`: on `kv-registry.entry-put.v1`, when `event.valueLength < 100`, append to audit with
    `entityId = hex(key)`, `entryHash = valueHash`, `reference = "published"`.
- **approval-audit-chain** (`…/approval-to-audit.yaml`):
  - components: `reviews` (approvals) and `audit` (doc-trail);
  - on `approvals.item-approved.v1`, append with `entityId = itemId`, `entryHash = payloadHash`, `reference = "approved"`.
- Live tips: 13 and 12. The binding demo has been run twice (`run1` and `pub1`).

### 4.2 Identity

- **There are no actor keys at all.** The only identity is the member key of the node the message is submitted to.
  - kv-registry: the first writer of a key becomes its owner; only that member may update or delete it
    (`SRC/docs/appchain/state-machines/kv-registry.md:35-47`).
  - approvals: the sender is the voter. Each member counts once. One member rejection makes the item REJECTED. The deadline is
    in epoch milliseconds, compared with the block timestamp (`…/approvals.md:30-58`).
- **"Acting as" a person on these chains means choosing which public node relays.** Five personas map to node0..node4.
  Nothing needs registering or providing, and no secret is needed.

### 4.3 Encodings (`KIT/docs/appchain/tutorials/tools/stdlib_command.py`; the release copy is used by `binding_demo.py`)

- **records.command.v1** (registry-audit)
  - PUT = `[0, bstr key, bstr value]`; DELETE = `[1, bstr key, h'']`.
  - Key is 1–1024 UTF-8 bytes (tool bound); value must be non-empty.
- **reviews.command.v1** (approval-audit)
  - PROPOSE = `[0, text itemId, bstr payload, uint required, uint deadlineMillis]`.
  - APPROVE = `[1, itemId]`; REJECT = `[2, itemId]`.
- The topic is the component id plus `.command.v1`, as used in `binding_demo.py:143,152`.
- Examples:

  | Command | Hex |
  |---|---|
  | `kv-registry put supplier-x --value-text "ISO-9001 certified"` | `83004a737570706c6965722d785249534f2d3930303120636572746966696564` |
  | `approvals propose po-1 --required 2 --payload-text "PO 1 release"` | `850064706f2d314c504f20312072656c656173650200` |
  | approve item `po-1` | `820164706f2d31` |

  All three examples were generated with the tool.

### 4.4 Read APIs

All are open without a key, as the public run shows.

- `GET /api/v1/app-chain/chains/{chain}/messages/{messageId}` gives the finalized height.
- `POST /query/composite/binding-receipt-v1/{messageId}` with `{paramsHex:""}` gives the receipt CBOR.
  Decode it with `OPS/scripts/receipt_summary.py`: status ACCEPTED/REJECTED, failed step, code (for example `KV_NOT_OWNER`,
  `MALFORMED_DERIVED_COMMAND`), per-step events, and binding condition match or skip.
- `POST /query/composite/binding-receipt-key-v1/{messageId}` gives the physical proof key.
- `GET /state/proof/{key}?height=h`, then `POST /proof/verify` (mode inclusion, profile `mpf-blake2b256-v1`).
- `GET /blocks/{h}` on each node, to compare roots across nodes (`binding_demo.py:67-99`).
- Component queries: `POST /query/components/{records|reviews|audit}/…`. doc-trail supports `head` with params entityId.
- State keys:
  - kv-registry local key = the raw key bytes;
  - approvals = `i/<itemId>`;
  - doc-trail = `e/<entityId>`;
  - all wrapped with the composite key.
- Other composite queries: `composite/active-profile-v1` and `composite/aggregate-v1` (`CompositeStateMachine.java:628-700`).

### 4.5 Demo flows

These are proven live in both evidence runs (`binding-live-run1.json`, `binding-live-public-pub1.json`).

**registry-audit-chain**

- accepted put: ACCEPTED, with two steps: records, then audit via `audit-record`;
- value ≥ 100 bytes: ACCEPTED, condition skip, no audit;
- put by a non-owner node: REJECTED `KV_NOT_OWNER`;
- 150-byte key: REJECTED `MALFORMED_DERIVED_COMMAND` with the whole cascade rolled back;
- identical resubmission: a new message id and a second audit entry.

**approval-audit-chain**

- propose with required 2 → node1 votes (pending) → node1 votes again (no-op) → node2 votes: approved plus exactly one audit
  append;
- propose, then node3 rejects: REJECTED, no audit.

**Mock defaults:** key `supplier-<run>`, value "ISO-9001 certified", item `po-<run>`, required 2.

### 4.6 Hazard (must guard)

The runbook (`REMOTE_VM_DEPLOYMENT_RUNBOOK.md:99-101`, and L-10 at line 347) says:

- kv keys of **112–127 or 223–256 bytes** make `machine.apply` throw ("composite physical state key exceeds 256 bytes")
  instead of producing a rejected receipt;
- approval item ids of **218–251 bytes** do the same.

`binding_demo.py:131-132` asserts the key length is outside these ranges.

Recommended client caps:

- kv key ≤ 100 bytes (keys up to 111 still give a clean derived-append; 128–222 give a clean `MALFORMED_DERIVED_COMMAND`);
- item id ≤ 200 bytes;
- or stricter still, ≤ 64 for both.

---

## 5. Per-chain key summary

| Chain | Identity type | Keys needed | Already registered live? | Client can self-register? | How |
|---|---|---|---|---|---|
| roles-chain | member-governed registry + actor Ed25519 signatures | seeds for the client's own actors | **No**: registry, policies and stats are empty | **Yes, no secrets** (live UNVERIFIED) | governed propose via node0, approve via node1–3, activate (4-of-5 members); org → actor (with key-proof) → policy; build records with the Java contracts |
| document-review-chain | actor Ed25519 (genesis-bound, actor-admin governance) | issuer-a, auditor-a, auditor-b (registry-admin-a for onboarding) | **Yes** (GET-proof verified) | Yes, via registry-admin-a (public seed); custom code, no CLI | option (a) works now |
| authenticated-map-chain / -jmt | member key for owner/member collections; actor Ed25519 for governed-role and approval | as above (issuer for governed-catalog; issuer + 2 auditors for released-products) | **Yes** (GET verified) | Yes, via registry-admin-a | option (a) works now |
| registry-audit-chain / approval-audit-chain | member key only (choose the relay node) | none | not applicable | not applicable | pick node0..4 as the persona |

**Nothing requires keys from the operator.** Operator secrets would only matter for admin routes (API key) or member keys,
and the demo needs neither.

---

## 6. Suggested backend design notes

- Keep all seeds server-side and derive demo seeds with the formula in §0.3.
- Signing options:
  - sign natively: Ed25519 over the domain-separated preimages in §0.4, §2.2 and §3.2; or
  - shell out to `yano.sh appchain role sign` and `authenticated-map direct-preimage|direct-complete|command`. The CLI starts a
    JVM per call and needs `--seed-file`; the release's own showcase does this.
- Before signing, always read the live tip (`GET /chains/{c}/tip`) and the current actor revision and key
  (`GET …/actors/{id}`). Compare the derived public key with the registered one, as `showcase.sh:1161` does.
- Submit a message, poll `GET /messages/{id}` until its height is above 0, then read the domain result.
  HTTP 202 only means the message was admitted.
- To show which person or node decided what, spread relays across node0..4. For actor chains the relay node is irrelevant;
  the signature decides.

---

## 7. Public-demo risks

1. **Public seeds mean public authority.**
   - Anyone can derive `registry-admin-a` and govern the authenticated-map and document-review registries. They could revoke
     issuer-a or the auditors, rotate keys, or replace the administrator authority (`PutAuthority`), which locks out further
     governance until a new chain.
   - Anyone can also act as issuer or auditor.
   - The release itself says demo seeds are "showcase-only" and must never be used on public networks
     (`showcase_signer.py` header; `showcase.sh:2012-2025` refuses published seeds for settlement on non-devnet).
   - The client should check that actor records are current (revision 1, key matches) and fail clearly if someone has
     tampered with them.
2. **roles-chain is governable by anyone on the internet.** Submission through the public nodes is unauthenticated, so anyone
   can drive 4-of-5 member governance by posting through 4 nodes. The same holds for kv ownership and approvals votes on the
   binding chains. This is inherent to "unauthenticated by design".
3. **Binding key-length hazard.** Some lengths throw instead of rejecting (§4.6), which is a consensus/apply exception on a
   live chain. Guard lengths server-side.
4. **Identifier mismatch on document-review** (§2.6).
5. **Height-bound parameters on quiet chains:**
   - deadlines and expiries are block heights, and blocks appear only with traffic;
   - a notBefore above the current tip needs extra messages before ACTIVATE succeeds;
   - direct-authorization windows are at most 100 blocks and approval lifetimes at most 600;
   - regenerate evidence close to submission.
6. **Capacity limits:** at most 4096 pending approvals, 64 per actor, 1024 pending governance, and 512 crypto work units per
   block (limits decoded from genesis). A spammy public demo could exhaust per-actor pending slots (64) for the shared demo
   actors. Use unique proposal ids and let proposals terminate.
7. **Broken domain API on document-review.** The role-workflow and stdlib domain APIs return 500 there. Use committed queries
   and state proofs instead.
8. **Rate limit** 200 average / 400 burst per client IP at each gateway. A single backend IP funnels every user.
9. **No command results on roles-chain.** Invalid or unauthorized commands are silent no-ops there, so the UI must diff
   proposal state before and after.

---

## Working notes

- Local decoder: `scratchpad/cbordump.py`.
- Public demo seed files for local CLI use: `scratchpad/demo-actors/*.demohex`. They are derived from the public formula and
  contain no operator secrets.
- No file under `/secrets/`, `private-*`, `*.seed`, `*.key` or `admin-api` was read.
