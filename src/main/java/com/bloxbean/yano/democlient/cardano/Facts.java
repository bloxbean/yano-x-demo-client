package com.bloxbean.yano.democlient.cardano;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * App-ledger keys and values the validators rebuild on-chain (onchain/lib/yano/records.ak). Kept in one place so
 * the off-chain side and the validators agree byte for byte.
 */
final class Facts {
    private Facts() {
    }

    /** Composite component key: "yano-composite-state-v1\0" ‖ u8 len(id) ‖ id ‖ u16 len(local) ‖ local. */
    static byte[] componentKey(String componentId, byte[] local) {
        byte[] id = componentId.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("yano-composite-state-v1\0".getBytes(StandardCharsets.US_ASCII));
        out.write(id.length);
        out.writeBytes(id);
        out.write(local.length >> 8);
        out.write(local.length & 0xff);
        out.writeBytes(local);
        return out.toByteArray();
    }

    /** document-review release receipt: component "document-review-receipts", local "approval/<proposal>". */
    static byte[] receiptKey(String proposal) {
        return componentKey("document-review-receipts", ("approval/" + proposal).getBytes(StandardCharsets.US_ASCII));
    }

    /** The receipt's CBOR `[1, proposal, entity, commitment, policy, revision, height, messageId]` as typed Data. */
    static PlutusData receipt(byte[] value) {
        try {
            List<DataItem> f = ((Array) CborSerializationUtil.deserialize(value)).getDataItems();
            return Pd.constr(0,
                    Pd.text(((UnicodeString) f.get(1)).getString()),
                    Pd.text(((UnicodeString) f.get(2)).getString()),
                    Pd.bytes(((ByteString) f.get(3)).getBytes()),
                    Pd.text(((UnicodeString) f.get(4)).getString()),
                    Pd.integer(((UnsignedInteger) f.get(5)).getValue()),
                    Pd.integer(((UnsignedInteger) f.get(6)).getValue()),
                    Pd.bytes(((ByteString) f.get(7)).getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("unexpected receipt encoding: " + e.getMessage(), e);
        }
    }

    /** ordered-log finalized-message record key: sha256("~yano/finalized-message/v1/" ‖ messageId). */
    static byte[] finalizedMessageKey(byte[] messageId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("~yano/finalized-message/v1/".getBytes(StandardCharsets.US_ASCII));
            return sha.digest(messageId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** balances key: "b/" ‖ lowercase hex of the member key. */
    static byte[] balanceKey(String memberKeyHex) {
        return ("b/" + memberKeyHex.toLowerCase()).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] uintCbor(long value) {
        try {
            return CborSerializationUtil.serialize(new UnsignedInteger(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Enterprise address of the Cardano key hash blake2b-224(member key): only the member can spend from it. */
    static String ownerAddress(byte[] memberKey) {
        return AddressProvider.getEntAddress(Credential.fromKey(Blake2bUtil.blake2bHash224(memberKey)),
                Networks.preprod()).toBech32();
    }
}
