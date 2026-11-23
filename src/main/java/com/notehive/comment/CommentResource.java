package com.notehive.comment;

import com.notehive.common.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/notes/{noteId}/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class CommentResource {

    @Inject CommentService comments;

    public record CreateCommentRequest(String body) {}

    @POST
    public CommentService.CommentView create(@PathParam("noteId") UUID noteId,
                                             CreateCommentRequest req) {
        var p = TenantContext.get();
        return comments.create(p.tenantId(), p.userId(), noteId,
                req == null ? null : req.body());
    }

    @GET
    public List<CommentService.CommentView> list(@PathParam("noteId") UUID noteId) {
        var p = TenantContext.get();
        return comments.list(p.tenantId(), p.userId(), noteId);
    }
}
