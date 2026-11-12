package com.bloxbean.yano.democlient.cardano;

import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/** Plutus Data in the shapes Aiken gives the validators' types (records are Constr 0). */
final class Pd {
    private static final HexFormat HEX = HexFormat.of();

    private Pd() {
    }

    public static PlutusData constr(int alternative, PlutusData... fields) {
        ListPlutusData list = new ListPlutusData();
        for (PlutusData field : fields) {
            list.add(field);
        }
        return ConstrPlutusData.builder().alternative(alternative).data(list).build();
    }

    public static PlutusData bytes(byte[] value) {
        return BytesPlutusData.of(value);
    }

    public static PlutusData hex(String value) {
        return BytesPlutusData.of(HEX.parseHex(value));
    }

    public static PlutusData text(String value) {
        return BytesPlutusData.of(value.getBytes(StandardCharsets.UTF_8));
    }

    public static PlutusData integer(long value) {
        return BigIntPlutusData.of(value);
    }

    public static PlutusData integer(BigInteger value) {
        return BigIntPlutusData.of(value);
    }

    public static PlutusData some(PlutusData value) {
        return constr(0, value);
    }

    public static PlutusData none() {
        return constr(1);
    }

    public static PlutusData list(List<PlutusData> items) {
        ListPlutusData list = new ListPlutusData();
        items.forEach(list::add);
        return list;
    }

    /** A proof as served by Yano (`proofWireHex`) is already the CBOR of Aiken's `Proof` type. */
    public static PlutusData decode(String cborHex) {
        try {
            return PlutusData.deserialize(HEX.parseHex(cborHex));
        } catch (CborDeserializationException e) {
            throw new IllegalStateException("not Plutus Data: " + e.getMessage(), e);
        }
    }

    public static List<PlutusData> fields(PlutusData data) {
        return ((ConstrPlutusData) data).getData().getPlutusDataList();
    }

    public static byte[] asBytes(PlutusData data) {
        return ((BytesPlutusData) data).getValue();
    }

    public static BigInteger asInt(PlutusData data) {
        return ((BigIntPlutusData) data).getValue();
    }
}
