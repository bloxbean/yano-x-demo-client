package com.bloxbean.yano.democlient.cardano;

import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Applied validators must be the same UPLC program `aiken blueprint apply` produces (goldens recorded with Aiken
 * v1.1.23). Bytes may differ only in how the parameter Data is serialised (julc writes indefinite-length CBOR
 * arrays), so the terms are compared structurally.
 */
class ScriptParamsTest {
    @Test
    void sameProgramAsAikenBlueprintApply() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/cardano/apply-golden.json")) {
            JsonNode cases = new ObjectMapper().readTree(in);
            for (var it = cases.fields(); it.hasNext(); ) {
                var c = it.next();
                List<PlutusData> params = new ArrayList<>();
                for (JsonNode p : c.getValue().get("params")) {
                    params.add(PlutusData.deserialize(HexFormat.of().parseHex(p.asText())));
                }
                Program ours = decode(ScriptParams.apply(c.getValue().get("compiledCode").asText(), params));
                Program aiken = decode(c.getValue().get("applied").asText());
                assertEquals(aiken.term(), ours.term(), c.getKey());
            }
        }
    }

    private static Program decode(String compiledCodeHex) throws Exception {
        return UplcFlatDecoder.decodeProgram(((ByteString) CborSerializationUtil
                .deserialize(HexFormat.of().parseHex(compiledCodeHex))).getBytes());
    }
}
