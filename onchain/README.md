# On Cardano validators

Aiken v1.1.x project with the three validators the demo client's On Cardano features use. Each reads a Yano
chain's script anchor as a reference input (`lib/yano/anchor.ak`), rebuilds an app-ledger key and value from typed
redeemer fields (`lib/yano/cbor.ak`, `lib/yano/records.ak`), proves it with `aiken-lang/merkle-patricia-forestry`
against the anchored root, and spends a nullifier.

| Validator | Purposes | Parameters |
|---|---|---|
| `certificates` | mint (registry token, certificate NFT) + spend (registry) | anchor of document-review-chain, approval policy id, seed UTxO |
| `escrow` | spend | anchor of orders-chain |
| `rewards` | mint (registry token, POINTS) + spend (registry) | anchor of balances-chain, seed UTxO |

```bash
aiken check            # 19 tests, including real showcase bytes in lib/vectors/
aiken build            # writes plutus.json
cp plutus.json ../src/main/resources/cardano/plutus.json
```

`tools/` regenerates the showcase vectors (`gen_vectors.py`, `gen_receipt_vector.py <proposal>`; Python with `cbor2`).
`lib/vectors/nullifiers.tests.ak` holds exclusion proofs and roots from cardano-client-lib's `MpfTrie` (the
client's claimed-set trie) and checks them against `mpf.insert` for five successive claims.
