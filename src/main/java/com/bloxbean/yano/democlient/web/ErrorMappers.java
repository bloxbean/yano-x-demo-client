package com.bloxbean.yano.democlient.web;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** User-facing errors as {"error": "..."}; no stack traces or node internals leak to the browser. */
public class ErrorMappers {

    @ServerExceptionMapper
    public Response invalid(IllegalArgumentException failure) {
        return Response.status(400).entity(new DemoResource.Problem(failure.getMessage())).build();
    }

    @ServerExceptionMapper
    public Response notFound(NotFoundException failure) {
        return Response.status(404).entity(new DemoResource.Problem(failure.getMessage())).build();
    }
}
