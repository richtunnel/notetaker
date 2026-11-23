package com.notehive.team;

import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.common.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Team REST surface. All responses are tenant-scoped by RLS. */
@Path("/api/teams")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class TeamResource {

    @Inject TeamService teams;

    public record CreateTeamRequest(String name) {}

    public record AddMemberRequest(String username, String role) {}

    @POST
    public TeamService.TeamView create(CreateTeamRequest req) {
        var p = TenantContext.get();
        if (req == null) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "body required");
        }
        return teams.create(p.tenantId(), p.userId(), req.name());
    }

    @GET
    public List<TeamService.TeamView> list() {
        var p = TenantContext.get();
        return teams.listForUser(p.tenantId(), p.userId());
    }

    @POST
    @Path("/{id}/members")
    public Map<String, Object> addMember(@PathParam("id") UUID teamId, AddMemberRequest req) {
        var p = TenantContext.get();
        if (req == null) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "body required");
        }
        teams.addMember(p.tenantId(), p.userId(), teamId, req.username(),
                req.role() == null ? "viewer" : req.role());
        return Map.of("ok", true);
    }

    @DELETE
    @Path("/{id}/members/{userId}")
    public Map<String, Object> removeMember(@PathParam("id") UUID teamId,
                                            @PathParam("userId") UUID userId) {
        var p = TenantContext.get();
        teams.removeMember(p.tenantId(), p.userId(), teamId, userId);
        return Map.of("ok", true);
    }
}
