package com.bloxbean.yano.democlient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFilesTest {
    private static final String SEALED = "{\"format\":\"yano-appchain-chain-v1\",\"genesis\":{\"name\":\"showcase\",\"chains\":[{\"id\":\"orders-chain\"}]}}";

    @TempDir
    Path dir;

    @Test
    void servesOnlyPublicAppChainFilesAndSkipsNodeSecrets() throws Exception {
        Files.createDirectories(dir.resolve("coordinator"));
        Files.writeString(dir.resolve("coordinator/showcase.chain"), SEALED);
        Files.writeString(dir.resolve("coordinator/showcase.draft.yaml"), "name: showcase\nchains:\n  - id: orders-chain\n");
        Files.writeString(dir.resolve("coordinator/roles-chain.role-plan.yaml"), "roles: []\n");
        Files.writeString(dir.resolve("coordinator/node0.member"), "member card");
        Files.writeString(dir.resolve("broken.chain"), "not json");
        Files.createDirectories(dir.resolve("node0/secrets"));
        Files.writeString(dir.resolve("node0/secrets/leak.chain"), SEALED);
        Files.createDirectories(dir.resolve("node0/config"));
        Files.writeString(dir.resolve("node0/config/local.yaml"), "operator-seed: abc\n");

        ConfigFiles.Listing listing = new ConfigFiles(Optional.of(dir), new ObjectMapper()).listing();

        assertTrue(listing.configured());
        List<String> paths = listing.files().stream().map(ConfigFiles.ConfigFile::path).toList();
        assertEquals(List.of("coordinator/roles-chain.role-plan.yaml", "coordinator/showcase.chain",
                "coordinator/showcase.draft.yaml"), paths);
        assertEquals(ConfigFiles.Kind.SEALED, listing.files().get(1).kind());
    }

    @Test
    void unsetDirectoryIsNotAnError() {
        ConfigFiles.Listing listing = new ConfigFiles(Optional.empty(), new ObjectMapper()).listing();
        assertFalse(listing.configured());
        assertTrue(listing.files().isEmpty());
    }
}
