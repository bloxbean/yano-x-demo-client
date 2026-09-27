package com.bloxbean.yano.democlient.actor;

import com.bloxbean.yano.democlient.codec.Hex;
import com.bloxbean.yano.democlient.config.DemoConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Named actors whose Ed25519 keys the server holds. Seeds never leave this class; the UI only sees public info. */
@ApplicationScoped
public class ActorDirectory {
    private final Map<String, Loaded> actors = new LinkedHashMap<>();

    @RegisterForReflection
    public record ActorInfo(String id, String label, String organization, String role, String keyId, String publicKey) {
    }

    private record Loaded(ActorInfo info, byte[] seed) {
    }

    public ActorDirectory(DemoConfig config) {
        for (DemoConfig.Actor actor : config.actors()) {
            byte[] seed = seed(actor);
            actors.put(actor.id(), new Loaded(new ActorInfo(actor.id(), actor.label(), actor.organization(), actor.role(),
                    actor.keyId(), Hex.encode(Ed25519.publicKey(seed))), seed));
        }
    }

    public List<ActorInfo> all() {
        return actors.values().stream().map(Loaded::info).toList();
    }

    public Optional<ActorInfo> find(String id) {
        return Optional.ofNullable(actors.get(id)).map(Loaded::info);
    }

    public byte[] signedStatement(String actorId, RoleStatements.Statement statement) {
        Loaded actor = actors.get(actorId);
        if (actor == null) {
            throw new IllegalArgumentException("unknown actor " + actorId);
        }
        return RoleStatements.signedCommand(statement, actor.seed);
    }

    private static byte[] seed(DemoConfig.Actor actor) {
        try {
            return switch (actor.seedSource()) {
                case "showcase-formula" -> MessageDigest.getInstance("SHA-256")
                        .digest(("yano-showcase-demo-actor:" + actor.id()).getBytes(StandardCharsets.UTF_8));
                case "file" -> {
                    String hex = Files.readString(Path.of(actor.seedFile()
                            .orElseThrow(() -> new IllegalStateException("seed-file missing for actor " + actor.id())))).trim();
                    byte[] seed = Hex.decode(hex);
                    if (seed.length != 32) {
                        throw new IllegalStateException("seed-file of actor " + actor.id() + " must hold 32 bytes");
                    }
                    yield seed;
                }
                default -> throw new IllegalStateException("unknown seed-source " + actor.seedSource());
            };
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot load the key of actor " + actor.id(), failure);
        }
    }
}
