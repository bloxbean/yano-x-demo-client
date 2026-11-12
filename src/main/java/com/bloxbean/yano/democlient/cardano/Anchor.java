package com.bloxbean.yano.democlient.cardano;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * A chain's script anchor as it is on Cardano right now: the UTxO holding the chain's one-shot thread token at
 * the anchor validator, and its decoded 11-field datum (Yano anchor-v1.cddl). Validators read it as a
 * reference input; only this latest root is on chain, so proofs are fetched at {@link #height()}.
 */
record Anchor(String chainId, String scriptHash, String scriptAddress, String threadPolicy, String genesisId,
              Utxo utxo, long height, String stateRoot, int memberCount, int threshold) {
    private static final HexFormat HEX = HexFormat.of();

    /** Identity from the member's status; datum and UTxO from Cardano, checked against that identity. */
    static Anchor load(L1 l1, String chainId) {
        JsonNode status = l1.status(chainId);
        JsonNode a = status.path("anchor");
        if (!"script".equals(a.path("mode").asText())) {
            throw new IllegalStateException(chainId + " has no script anchor");
        }
        String policy = a.path("threadPolicyId").asText();
        String address = a.path("scriptAddress").asText();
        String genesis = status.path("stateCommitment").path("genesisId").asText();
        List<Utxo> holding = l1.utxos(address).stream()
                .filter(u -> u.getAmount().stream().anyMatch(x -> x.getUnit().startsWith(policy)))
                .toList();
        if (holding.size() != 1) {
            throw new IllegalStateException(chainId + ": expected one anchor UTxO, found " + holding.size());
        }
        Utxo utxo = holding.getFirst();
        List<PlutusData> f = Pd.fields(Pd.decode(utxo.getInlineDatum()));
        if (!chainId.equals(new String(Pd.asBytes(f.get(1)), StandardCharsets.UTF_8))
                || !genesis.equals(HEX.formatHex(Pd.asBytes(f.get(2))))) {
            throw new IllegalStateException(chainId + ": the anchor datum names another chain or generation");
        }
        return new Anchor(chainId, a.path("scriptHash").asText(), address, policy, genesis, utxo,
                Pd.asInt(f.get(6)).longValue(), HEX.formatHex(Pd.asBytes(f.get(8))),
                ((ListPlutusData) f.get(9)).getPlutusDataList().size(), Pd.asInt(f.get(10)).intValue());
    }

    /** The validators' {@code AnchorRef} parameter. */
    PlutusData ref() {
        return Pd.constr(0, Pd.hex(scriptHash), Pd.hex(threadPolicy), Pd.text(chainId), Pd.hex(genesisId));
    }

    ObjectNode json() {
        ObjectNode o = Json.object();
        o.put("chainId", chainId);
        o.put("height", height);
        o.put("stateRoot", stateRoot);
        o.put("utxoTx", utxo.getTxHash());
        o.put("utxoIndex", utxo.getOutputIndex());
        o.put("scriptAddress", scriptAddress);
        o.put("threadPolicy", threadPolicy);
        o.put("threshold", threshold);
        o.put("members", memberCount);
        return o;
    }
}
