package com.notehive.infra;

import com.notehive.common.BloomUsernameIndex;
import com.notehive.config.AppConfig;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup rebuild of the Bloom index from the authoritative store. The rebuild
 * reads EVERY username/email cross-tenant, so it runs on the worker datasource
 * (BYPASSRLS role) — the app role must never see users of other tenants.
 * The filter bean itself is produced by {@link CdiBeans} and shared.
 */
@ApplicationScoped
public class BloomIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(BloomIndexService.class);

    @Inject
    BloomUsernameIndex bloom;

    @Inject
    @DataSource("worker")
    AgroalDataSource workerDb;

    public BloomUsernameIndex index() {
        return bloom;
    }

    void rebuild(@Observes StartupEvent ev) {
        try (Connection c = workerDb.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT lower(username) FROM users UNION SELECT lower(email) FROM users");
             ResultSet rs = ps.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            bloom.rebuild(values);
            LOG.info("Bloom index rebuilt with {} entries", values.size());
        } catch (Exception e) {
            LOG.error("Bloom rebuild failed — starting with an empty filter " +
                    "(checks will fall through to the DB, correctness unaffected)", e);
        }
    }

    public void record(String value) {
        bloom.record(value);
    }
}
