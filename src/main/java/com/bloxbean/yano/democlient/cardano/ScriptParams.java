package com.bloxbean.yano.democlient.cardano;

import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.cbor.PlutusDataCborDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;

import java.util.HexFormat;
import java.util.List;

/**
 * Applies deployment parameters (a chain's anchor identity, a one-shot seed UTxO) to a validator from the
 * checked-in blueprint, as {@code aiken blueprint apply} does, using julc's UPLC flat codec.
 */
final class ScriptParams {
    private ScriptParams() {
    }

    static String apply(String compiledCodeHex, List<PlutusData> params) {
        try {
            byte[] flat = ((ByteString) CborSerializationUtil.deserialize(HexFormat.of().parseHex(compiledCodeHex))).getBytes();
            Program program = UplcFlatDecoder.decodeProgram(flat);
            for (PlutusData param : params) {
                program = program.applyParams(PlutusDataCborDecoder.decode(param.serializeToBytes()));
            }
            return HexFormat.of().formatHex(CborSerializationUtil.serialize(new ByteString(UplcFlatEncoder.encodeProgram(program))));
        } catch (Exception e) {
            throw new IllegalStateException("cannot apply validator parameters: " + e.getMessage(), e);
        }
    }
}
