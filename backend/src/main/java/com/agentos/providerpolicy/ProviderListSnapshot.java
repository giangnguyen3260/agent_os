package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal list snapshot; HTTP wiring requires the separately provisioned read capability. */
@Service
class ProviderListSnapshot {
  static final int PAGE_SIZE = 20;
  private static final long MAX_SAFE_TOTAL = 9_007_199_254_740_991L;
  private final JdbcTemplate jdbc;
  private final PolicyAuthority authority;

  ProviderListSnapshot(JdbcTemplate jdbc, PolicyAuthority authority) {
    this.jdbc = jdbc;
    this.authority = authority;
  }

  @JsonIgnoreType
  record Row(
      UUID organization,
      UUID id,
      String provider,
      String mode,
      String name,
      String workspace,
      String state,
      long revision,
      long credentialRevision,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      boolean credentialAvailable) {}

  @JsonIgnoreType
  record Page(long total, int page, List<Row> rows) {
    Page {
      rows = List.copyOf(rows);
    }
  }

  @Transactional(
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      propagation = Propagation.REQUIRES_NEW)
  public Page read(HumanIdentity actor, UUID organization, int page) {
    return readPage(actor, organization, page, false);
  }

  @Transactional(
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      propagation = Propagation.REQUIRED)
  public Page readWithEvidence(HumanIdentity actor, UUID organization, int page) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        || jdbc.getDataSource() == null
        || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
      throw PolicyFailure.unavailable();
    }
    return readPage(actor, organization, page, true);
  }

  private Page readPage(HumanIdentity actor, UUID organization, int page, boolean evidence) {
    try {
      if (evidence && !authority.current(actor)) throw new PolicyFailure(401, "UNAUTHORIZED");
      authority.require(actor, organization);
      if (evidence) requireEvidenceRuntime();
      if (page < 0 || page > 100_000) throw OptionsWire.bad();
      Long total =
          jdbc.queryForObject(
              "select count(*) from public.provider_connections where organization_id=?",
              Long.class,
              organization);
      if (total == null || total < 0 || total > MAX_SAFE_TOTAL) throw PolicyFailure.unavailable();
      String pageQuery =
          """
          select organization_id,id,provider,mode,name,workspace,state,
                 configuration_revision,credential_revision,created_at,updated_at
          from public.provider_connections where organization_id=?
          order by created_at desc,id desc limit 20 offset ?
          """;
      String query =
          evidence
              ? "with selected as materialized ("
                  + pageQuery
                  + ")"
                  + " select selected.*, agentos_list_read.credential_available(organization_id,id)"
                  + " from selected order by created_at desc,id desc"
              : "select selected.*, false from ("
                  + pageQuery
                  + ") selected"
                  + " order by created_at desc,id desc";
      List<Row> rows =
          jdbc.query(
              query,
              (rs, index) ->
                  new Row(
                      rs.getObject(1, UUID.class),
                      rs.getObject(2, UUID.class),
                      rs.getString(3),
                      rs.getString(4),
                      rs.getString(5),
                      rs.getString(6),
                      rs.getString(7),
                      rs.getLong(8),
                      rs.getLong(9),
                      rs.getObject(10, OffsetDateTime.class),
                      rs.getObject(11, OffsetDateTime.class),
                      rs.getBoolean(12)),
              organization,
              page * PAGE_SIZE);
      return new Page(total, page, rows);
    } catch (PolicyFailure known) {
      throw known;
    } catch (RuntimeException failure) {
      throw PolicyFailure.unavailable();
    }
  }

  private void requireEvidenceRuntime() {
    if (!Boolean.TRUE.equals(
        jdbc.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                    !connection.getAutoCommit()
                        && connection.isReadOnly()
                        && connection.getTransactionIsolation()
                            == Connection.TRANSACTION_REPEATABLE_READ))) {
      throw PolicyFailure.unavailable();
    }
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            with reachable as materialized (
              select * from pg_catalog.pg_roles
              where rolname=current_user or pg_catalog.pg_has_role(current_user,oid,'MEMBER')
            )
            select current_user=session_user
              and pg_catalog.current_setting('transaction_read_only')='on'
              and pg_catalog.current_setting('transaction_isolation')='repeatable read'
              and pg_catalog.pg_has_role(current_user,'agentos_provider_list_evidence','USAGE')
              and pg_catalog.has_function_privilege(current_user,
                  'agentos_list_read.credential_available(uuid,uuid)','EXECUTE')
              and exists (
                select 1 from pg_catalog.pg_proc p join pg_catalog.pg_roles owner
                  on owner.oid=p.proowner
                where p.oid=pg_catalog.to_regprocedure(
                          'agentos_list_read.credential_available(uuid,uuid)')
                  and owner.rolname='agentos_provider_evidence_owner'
                  and p.prosecdef and p.provolatile='s'
                  and p.prorettype='pg_catalog.bool'::pg_catalog.regtype
              )
              and not exists (
                select 1 from (values
                  ('public.provider_credential_versions'),
                  ('public.provider_credential_integrity'),
                  ('public.provider_credential_health'),
                  ('public.provider_evidence_publishers'),
                  ('agentos_evidence_private.integrity_provenance'),
                  ('agentos_evidence_private.integrity_assertions'),
                  ('agentos_evidence_private.publication_attempts')
                ) required(relation)
                where pg_catalog.to_regclass(required.relation) is null
              )
              and not pg_catalog.has_function_privilege(current_user,
                  'agentos_list_read.credential_available(uuid,uuid)','EXECUTE WITH GRANT OPTION')
              and not exists (
                select 1 from reachable r
                where (r.rolsuper or r.rolcreaterole or r.rolcreatedb or r.rolreplication
                       or r.rolbypassrls or r.rolname in
                          ('agentos_provider_evidence_owner','agentos_provider_evidence_reader',
                           'agentos_provider_evidence_publisher'))
              )
              and not exists (
                select 1 from pg_catalog.pg_database d
                where d.datname=current_database()
                  and pg_catalog.pg_has_role(current_user,d.datdba,'MEMBER')
              )
              and not exists (
                select 1 from pg_catalog.pg_namespace n cross join reachable r
                where n.nspname in ('public','agentos_list_read','agentos_evidence_private')
                  and pg_catalog.has_schema_privilege(r.oid,n.oid,'CREATE')
              )
              and not exists (
                select 1 from pg_catalog.pg_class c
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                cross join reachable r
                where c.relkind in ('r','p')
                  and (n.nspname='agentos_evidence_private' or
                       (n.nspname='public' and c.relname in
                        ('provider_credential_versions','provider_credential_health',
                         'provider_credential_integrity','provider_evidence_publishers')))
                  and (pg_catalog.pg_has_role(current_user,c.relowner,'MEMBER')
                    or pg_catalog.has_table_privilege(r.oid,c.oid,
                         'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER')
                    or pg_catalog.has_any_column_privilege(r.oid,c.oid,
                         'SELECT,INSERT,UPDATE'))
              )
            """,
            Boolean.class))) {
      throw PolicyFailure.unavailable();
    }
  }
}
