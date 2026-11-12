package com.bloxbean.yano.democlient.cardano;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small JSON state file: deployments (script parameters, seeds), recorded claims and escrows. No secrets. */
final class Store {
    private final Path file;
    private ObjectNode data;

    public Store(Path file) {
        this.file = file;
        try {
            data = Files.exists(file) ? (ObjectNode) Json.MAPPER.readTree(file.toFile()) : Json.object();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized ObjectNode section(String name) {
        if (!data.has(name)) {
            data.set(name, Json.object());
        }
        return (ObjectNode) data.get(name);
    }

    public synchronized ArrayNode list(String name) {
        if (!data.has(name)) {
            data.set(name, Json.MAPPER.createArrayNode());
        }
        return (ArrayNode) data.get(name);
    }

    public synchronized JsonNode snapshot() {
        return data.deepCopy();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Json.MAPPER.writeValue(tmp.toFile(), data);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
