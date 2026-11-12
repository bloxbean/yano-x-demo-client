package com.bloxbean.yano.democlient.cardano;

import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The off-chain copy of a registry's claimed-set trie. Only its root is on chain; the app rebuilds the trie
 * from the claims it recorded and checks the root against the registry datum before every claim.
 */
final class NullifierTrie {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] EMPTY_ROOT = new byte[32];

    private final MpfTrie trie;

    public NullifierTrie(Map<String, String> claimed) {
        Map<String, byte[]> nodes = new HashMap<>();
        NodeStore store = new NodeStore() {
            @Override public byte[] get(byte[] key) { return nodes.get(HEX.formatHex(key)); }
            @Override public void put(byte[] key, byte[] value) { nodes.put(HEX.formatHex(key), value); }
            @Override public void delete(byte[] key) { nodes.remove(HEX.formatHex(key)); }
        };
        this.trie = new MpfTrie(store);
        new LinkedHashMap<>(claimed).forEach((k, v) -> trie.put(HEX.parseHex(k), HEX.parseHex(v)));
    }

    public String root() {
        byte[] root = trie.getRootHash();
        return HEX.formatHex(root == null ? EMPTY_ROOT : root);
    }

    public boolean contains(byte[] key) {
        return trie.get(key) != null;
    }

    /** The proof `mpf.insert` needs: the key's absence in the current trie (an empty list for an empty trie). */
    public PlutusData exclusionProof(byte[] key) {
        if (contains(key)) {
            throw new IllegalStateException("already claimed: " + HEX.formatHex(key));
        }
        return trie.getProofPlutusData(key).<PlutusData>map(p -> p).orElseGet(ListPlutusData::new);
    }

    /** The root after inserting, as the validator will compute it. */
    public String rootAfter(byte[] key, byte[] value) {
        trie.put(key, value);
        String root = root();
        trie.delete(key);
        return root;
    }
}
