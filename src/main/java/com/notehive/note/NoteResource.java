package com.notehive.note;

import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.common.Page;
import com.notehive.common.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

/** Note REST surface (docs/API.md). Idempotency + rate limits + caching apply via middleware. */
@Path("/api/notes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class NoteResource {

    @Inject NoteService notes;

    @POST
    public NoteService.NoteView create(NoteService.CreateRequest req) {
        var p = TenantContext.get();
        return notes.create(p.tenantId(), p.userId(), req);
    }

    @GET
    public Page<NoteService.NoteView> list(
            @QueryParam("teamId") UUID teamId,
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") Integer limit) {
        var p = TenantContext.get();
        return notes.list(p.tenantId(), p.userId(), teamId, cursor, limit);
    }

    @GET
    @Path("/search")
    public Page<NoteService.NoteView> search(
            @QueryParam("q") String q,
            @QueryParam("teamId") UUID teamId,
            @QueryParam("limit") @DefaultValue("20") Integer limit) {
        var p = TenantContext.get();
        return notes.search(p.tenantId(), p.userId(), teamId, q, limit);
    }

    @GET
    @Path("/{id}")
    public NoteService.NoteView get(@PathParam("id") UUID noteId) {
        var p = TenantContext.get();
        return notes.get(p.tenantId(), p.userId(), noteId);
    }

    @PUT
    @Path("/{id}")
    public NoteService.NoteView update(@PathParam("id") UUID noteId, NoteService.UpdateRequest req) {
        var p = TenantContext.get();
        return notes.update(p.tenantId(), p.userId(), noteId, req);
    }

    @DELETE
    @Path("/{id}")
    public Map<String, Object> delete(@PathParam("id") UUID noteId) {
        var p = TenantContext.get();
        notes.delete(p.tenantId(), p.userId(), noteId);
        return Map.of("ok", true);
    }

    @POST
    @Path("/{id}/restore")
    public NoteService.NoteView restore(@PathParam("id") UUID noteId) {
        var p = TenantContext.get();
        return notes.restore(p.tenantId(), p.userId(), noteId);
    }

    @GET
    @Path("/{id}/versions")
    public Page<NoteService.VersionView> versions(
            @PathParam("id") UUID noteId,
            @QueryParam("cursor") Integer cursorVersion,
            @QueryParam("limit") @DefaultValue("20") Integer limit) {
        var p = TenantContext.get();
        return notes.versions(p.tenantId(), p.userId(), noteId, cursorVersion, limit);
    }

    @POST
    @Path("/{id}/restore/{version}")
    public NoteService.NoteView restoreVersion(@PathParam("id") UUID noteId,
                                               @PathParam("version") int version) {
        var p = TenantContext.get();
        return notes.restoreVersion(p.tenantId(), p.userId(), noteId, version);
    }

    /** Version conflict helper exposed for clients to re-read the authoritative version. */
    @GET
    @Path("/{id}/version")
    public Map<String, Object> currentVersion(@PathParam("id") UUID noteId) {
        var p = TenantContext.get();
        int v = notes.currentVersion(p.tenantId(), noteId);
        return Map.of("version", v);
    }
}
