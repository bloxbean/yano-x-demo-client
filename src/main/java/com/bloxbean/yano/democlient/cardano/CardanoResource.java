package com.bloxbean.yano.democlient.cardano;

import com.bloxbean.yano.democlient.web.DemoResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * On Cardano API. Visitor actions (mint a certificate, create/release an escrow, claim rewards) build Cardano
 * transactions paid by the demo wallet, within an hourly budget; operator actions (open registries and seasons,
 * populate, anchor now, server-side release flow) need the operator token when one is configured.
 */
@Path("/api/demo/cardano")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class CardanoResource {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern ORDER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,40}");
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TX = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> NODES = Set.of("node-0", "node-1", "node-2", "node-3", "node-4");

    private final CardanoService cardano;

    public CardanoResource(CardanoService cardano) {
        this.cardano = cardano;
    }

    public record ProposalRequest(String proposal, String entity, String content) {
    }

    public record EscrowRequest(String order, String carrier, String createTx, String node) {
    }

    public record ChainRequest(String chain) {
    }

    public record AccountRequest(String memberKey, Long amount) {
    }

    @GET
    @Path("/overview")
    public ObjectNode overview() {
        return cardano.overview();
    }

    @GET
    @Path("/jobs")
    public JsonNode jobs() {
        cardano.requireEnabled();
        return cardano.jobs().recent();
    }

    @GET
    @Path("/jobs/{id}")
    public Response job(@PathParam("id") String id) {
        cardano.requireEnabled();
        ObjectNode job = cardano.jobs().view(id);
        return job == null ? Response.status(404).entity(new DemoResource.Problem("unknown job")).build() : Response.ok(job).build();
    }

    // ------------------------------------------------------------------ certificates

    @GET
    @Path("/certificates/lookup")
    public ObjectNode lookup(@QueryParam("proposal") String proposal) {
        cardano.requireEnabled();
        return cardano.lookupRelease(id(proposal, "proposal id"));
    }

    @GET
    @Path("/certificates")
    public JsonNode certificates() {
        cardano.requireEnabled();
        return cardano.certificateMints();
    }

    @POST
    @Path("/certificates/mint")
    public Response mint(ProposalRequest request) {
        cardano.requireEnabled();
        String proposal = id(request == null ? null : request.proposal(), "proposal id");
        return started(cardano.jobs().start("Mint certificate " + proposal, log -> cardano.mintCertificate(log, proposal)));
    }

    @POST
    @Path("/certificates/open")
    public Response openCertificates(@HeaderParam("X-Demo-Operator") String token) {
        cardano.requireOperator(token);
        return started(cardano.jobs().start("Open certificate registry", cardano::openCertificates));
    }

    @POST
    @Path("/certificates/release")
    public Response release(@HeaderParam("X-Demo-Operator") String token, ProposalRequest request) {
        cardano.requireOperator(token);
        String proposal = id(request.proposal(), "proposal id");
        String entity = id(request.entity(), "document id");
        String content = request.content() == null || request.content().isBlank() ? "Certificate " + entity
                : request.content().substring(0, Math.min(2000, request.content().length()));
        return started(cardano.jobs().startLedger("Release " + proposal,
                log -> cardano.releaseDocument(log, proposal, entity, content)));
    }

    // ------------------------------------------------------------------ escrow

    @GET
    @Path("/escrow")
    public ObjectNode escrows() {
        cardano.requireEnabled();
        return cardano.escrows();
    }

    @POST
    @Path("/escrow/create")
    public Response createEscrow(EscrowRequest request) {
        cardano.requireEnabled();
        String order = match(ORDER, request == null ? null : request.order(), "order id");
        String carrier = request.carrier() == null || request.carrier().isBlank() ? "" : match(KEY, request.carrier(), "carrier key");
        return started(cardano.jobs().start("Lock ADA for order " + order, log -> cardano.createEscrow(log, order, carrier)));
    }

    @POST
    @Path("/escrow/deliver")
    public Response deliver(EscrowRequest request) {
        cardano.requireEnabled();
        String order = match(ORDER, request == null ? null : request.order(), "order id");
        String node = request.node() == null || !NODES.contains(request.node()) ? "node-1" : request.node();
        return started(cardano.jobs().startLedger("Record delivery of " + order, log -> cardano.recordDelivery(log, order, node)));
    }

    @POST
    @Path("/escrow/release")
    public Response releaseEscrow(EscrowRequest request) {
        cardano.requireEnabled();
        String tx = match(TX, request == null ? null : request.createTx(), "escrow");
        return started(cardano.jobs().start("Release escrow", log -> cardano.releaseEscrow(log, tx)));
    }

    @POST
    @Path("/escrow/refund")
    public Response refundEscrow(EscrowRequest request) {
        cardano.requireEnabled();
        String tx = match(TX, request == null ? null : request.createTx(), "escrow");
        return started(cardano.jobs().start("Refund escrow", log -> cardano.refundEscrow(log, tx)));
    }

    // ------------------------------------------------------------------ rewards

    @GET
    @Path("/rewards")
    public JsonNode rewards() {
        cardano.requireEnabled();
        return cardano.accounts();
    }

    @POST
    @Path("/rewards/claim")
    public Response claim(AccountRequest request) {
        cardano.requireEnabled();
        String key = match(KEY, request == null ? null : request.memberKey(), "member key");
        return started(cardano.jobs().start("Claim rewards", log -> cardano.claimRewards(log, key)));
    }

    @POST
    @Path("/rewards/open")
    public Response openSeason(@HeaderParam("X-Demo-Operator") String token) {
        cardano.requireOperator(token);
        return started(cardano.jobs().start("Open rewards season", cardano::openSeason));
    }

    @POST
    @Path("/rewards/points")
    public Response points(@HeaderParam("X-Demo-Operator") String token, AccountRequest request) {
        cardano.requireOperator(token);
        String key = match(KEY, request.memberKey(), "member key");
        long amount = request.amount() == null ? 10 : Math.max(1, Math.min(1000, request.amount()));
        return started(cardano.jobs().startLedger("Mint points", log -> cardano.mintPoints(log, key, amount)));
    }

    // ------------------------------------------------------------------ anchoring

    @POST
    @Path("/populate")
    public Response populate(@HeaderParam("X-Demo-Operator") String token, ChainRequest request) {
        cardano.requireOperator(token);
        String chain = id(request == null ? null : request.chain(), "chain");
        return started(cardano.jobs().startUnique("populate:" + chain, "Populate " + chain + " until anchored",
                log -> cardano.populate(log, chain)));
    }

    @POST
    @Path("/anchor-now")
    public Response anchorNow(@HeaderParam("X-Demo-Operator") String token, ChainRequest request) {
        cardano.requireOperator(token);
        String chain = id(request == null ? null : request.chain(), "chain");
        return started(cardano.jobs().startUnique("anchor:" + chain, "Anchor " + chain + " now",
                log -> cardano.anchorNow(log, chain)));
    }

    // ------------------------------------------------------------------ helpers

    @org.jboss.resteasy.reactive.server.ServerExceptionMapper
    public Response unavailable(IllegalStateException failure) {
        return Response.status(409).entity(new DemoResource.Problem(failure.getMessage())).build();
    }

    @org.jboss.resteasy.reactive.server.ServerExceptionMapper
    public Response forbidden(SecurityException failure) {
        return Response.status(403).entity(new DemoResource.Problem(failure.getMessage())).build();
    }

    private static Response started(String jobId) {
        return Response.accepted(java.util.Map.of("job", jobId)).build();
    }

    private static String id(String value, String what) {
        return match(ID, value, what);
    }

    private static String match(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("invalid " + what);
        }
        return value.trim();
    }
}
