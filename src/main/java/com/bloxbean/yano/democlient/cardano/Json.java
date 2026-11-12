package com.bloxbean.yano.democlient.cardano;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON tree helpers for the Cardano layer (tree model only: nothing needs reflection in the native image). */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }
}
