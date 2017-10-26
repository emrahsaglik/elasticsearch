/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.security;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.lucene.util.SuppressForbidden;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.hamcrest.Matcher;
import org.junit.AfterClass;
import org.junit.Before;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;

public abstract class SqlSecurityTestCase extends ESRestTestCase {
    /**
     * Actions taken by this test.
     */
    protected interface Actions {
        void queryWorksAsAdmin() throws Exception;
        /**
         * Assert that running some sql as a user returns the same result as running it as
         * the administrator.
         */
        void expectMatchesAdmin(String adminSql, String user, String userSql) throws Exception;
        /**
         * Same as {@link #expectMatchesAdmin(String, String, String)} but sets the scroll size
         * to 1 and completely scrolls the results.
         */
        void expectScrollMatchesAdmin(String adminSql, String user, String userSql) throws Exception;
        void expectDescribe(Map<String, String> columns, String user) throws Exception;
        void expectShowTables(List<String> tables, String user) throws Exception;

        void expectForbidden(String user, String sql) throws Exception;
        void expectUnknownColumn(String user, String sql, String column) throws Exception;
    }

    protected static final String SQL_ACTION_NAME = "indices:data/read/sql";
    protected static final String SQL_INDICES_ACTION_NAME = "indices:data/read/sql/tables";
    /**
     * Location of the audit log file. We could technically figure this out by reading the admin
     * APIs but it isn't worth doing because we also have to give ourselves permission to read
     * the file and that must be done by setting a system property and reading it in
     * {@code plugin-security.policy}. So we may as well have gradle set the property.
     */
    private static final Path AUDIT_LOG_FILE = lookupAuditLog();

    @SuppressForbidden(reason="security doesn't work with mock filesystem")
    private static Path lookupAuditLog() {
        String auditLogFileString = System.getProperty("tests.audit.logfile");
        if (null == auditLogFileString) {
            throw new IllegalStateException("tests.audit.logfile must be set to run this test. It is automatically "
                    + "set by gradle. If you must set it yourself then it should be the absolute path to the audit "
                    + "log file generated by running x-pack with audit logging enabled.");
        }
        return Paths.get(auditLogFileString);
    }

    private static boolean oneTimeSetup = false;
    private static boolean auditFailure = false;

    /**
     * The actions taken by this test.
     */
    private final Actions actions;

    /**
     * How much of the audit log was written before the test started.
     */
    private long auditLogWrittenBeforeTestStart;

    public SqlSecurityTestCase(Actions actions) {
        this.actions = actions;
    }

    /**
     * All tests run as a an administrative user but use
     * <code>es-security-runas-user</code> to become a less privileged user when needed.
     */
    @Override
    protected Settings restClientSettings() {
        return RestSqlIT.securitySettings();
    }

    @Override
    protected boolean preserveIndicesUponCompletion() {
        /* We can't wipe the cluster between tests because that nukes the audit
         * trail index which makes the auditing flaky. Instead we wipe all
         * indices after the entire class is finished. */
        return true;
    }

    @Before
    public void oneTimeSetup() throws Exception {
        if (oneTimeSetup) {
            /* Since we don't wipe the cluster between tests we only need to
             * write the test data once. */
            return;
        }
        StringBuilder bulk = new StringBuilder();
        bulk.append("{\"index\":{\"_index\": \"test\", \"_type\": \"doc\", \"_id\":\"1\"}\n");
        bulk.append("{\"a\": 1, \"b\": 2, \"c\": 3}\n");
        bulk.append("{\"index\":{\"_index\": \"test\", \"_type\": \"doc\", \"_id\":\"2\"}\n");
        bulk.append("{\"a\": 4, \"b\": 5, \"c\": 6}\n");
        bulk.append("{\"index\":{\"_index\": \"bort\", \"_type\": \"doc\", \"_id\":\"1\"}\n");
        bulk.append("{\"a\": \"test\"}\n");
        client().performRequest("PUT", "/_bulk", singletonMap("refresh", "true"),
                new StringEntity(bulk.toString(), ContentType.APPLICATION_JSON));
        oneTimeSetup = true;
    }

    @Before
    public void setInitialAuditLogOffset() throws IOException {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            if (false == Files.exists(AUDIT_LOG_FILE)) {
                auditLogWrittenBeforeTestStart = 0;
                return null;
            }
            if (false == Files.isRegularFile(AUDIT_LOG_FILE)) {
                throw new IllegalStateException("expected tests.audit.logfile [" + AUDIT_LOG_FILE + "]to be a plain file but wasn't");
            }
            try {
                auditLogWrittenBeforeTestStart = Files.size(AUDIT_LOG_FILE);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @AfterClass
    public static void wipeIndicesAfterTests() throws IOException {
        try {
            adminClient().performRequest("DELETE", "*");
        } catch (ResponseException e) {
            // 404 here just means we had no indexes
            if (e.getResponse().getStatusLine().getStatusCode() != 404) {
                throw e;
            }
        } finally {
            // Clear the static state so other subclasses can reuse it later
            oneTimeSetup = false;
            auditFailure = false;
        }
    }

    public void testQueryWorksAsAdmin() throws Exception {
        actions.queryWorksAsAdmin();
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("test_admin", "test")
            .assertLogs();
    }

    public void testQueryWithFullAccess() throws Exception {
        createUser("full_access", "read_all");

        actions.expectMatchesAdmin("SELECT * FROM test ORDER BY a", "full_access", "SELECT * FROM test ORDER BY a");
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("test_admin", "test")
            .expectSqlWithSyncLookup("full_access", "test")
            .assertLogs();
    }

    public void testScrollWithFullAccess() throws Exception {
        createUser("full_access", "read_all");

        actions.expectScrollMatchesAdmin("SELECT * FROM test ORDER BY a", "full_access", "SELECT * FROM test ORDER BY a");
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("test_admin", "test")
            /* Scrolling doesn't have to access the index again, at least not through sql.
             * If we asserted query and scroll logs then we would see the scoll. */
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expectSqlWithSyncLookup("full_access", "test")
            .expect(true, SQL_ACTION_NAME, "full_access", empty())
            .expect(true, SQL_ACTION_NAME, "full_access", empty())
            .assertLogs();
    }

    public void testQueryNoAccess() throws Exception {
        createUser("no_access", "read_nothing");

        actions.expectForbidden("no_access", "SELECT * FROM test");
        new AuditLogAsserter()
            .expect(false, SQL_ACTION_NAME, "no_access", empty())
            .assertLogs();
    }

    public void testQueryWrongAccess() throws Exception {
        createUser("wrong_access", "read_something_else");

        actions.expectForbidden("wrong_access", "SELECT * FROM test");
        new AuditLogAsserter()
            /* This user has permission to run sql queries so they are
             * given preliminary authorization. */
            .expect(true, SQL_ACTION_NAME, "wrong_access", empty())
            /* But as soon as they attempt to resolve an index that
             * they don't have access to they get denied. */
            .expect(false, SQL_ACTION_NAME, "wrong_access", hasItems("test"))
            .assertLogs();
    }

    public void testQuerySingleFieldGranted() throws Exception {
        createUser("only_a", "read_test_a");

        actions.expectMatchesAdmin("SELECT a FROM test ORDER BY a", "only_a", "SELECT * FROM test ORDER BY a");
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("test_admin", "test")
            .expectSqlWithSyncLookup("only_a", "test")
            .assertLogs();
    }

    public void testScrollWithSingleFieldGranted() throws Exception {
        createUser("only_a", "read_test_a");

        actions.expectScrollMatchesAdmin("SELECT a FROM test ORDER BY a", "only_a", "SELECT * FROM test ORDER BY a");
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("test_admin", "test")
            /* Scrolling doesn't have to access the index again, at least not through sql.
             * If we asserted query and scroll logs then we would see the scoll. */
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expectSqlWithSyncLookup("only_a", "test")
            .expect(true, SQL_ACTION_NAME, "only_a", empty())
            .expect(true, SQL_ACTION_NAME, "only_a", empty())
            .assertLogs();
    }

    public void testQueryStringSingeFieldGrantedWrongRequested() throws Exception {
        createUser("only_a", "read_test_a");

        actions.expectUnknownColumn("only_a", "SELECT c FROM test", "c");
        /* The user has permission to query the index but one of the
         * columns that they explicitly mention is hidden from them
         * by field level access control. This *looks* like a successful
         * query from the audit side because all the permissions checked
         * out but it failed in SQL because it couldn't compile the
         * query without the metadata for the missing field. */
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("only_a", "test")
            .assertLogs();
    }

    public void testQuerySingleFieldExcepted() throws Exception {
        createUser("not_c", "read_test_a_and_b");

        actions.expectMatchesAdmin("SELECT a, b FROM test ORDER BY a", "not_c", "SELECT * FROM test ORDER BY a");
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("test_admin", "test")
            .expectSqlWithSyncLookup("not_c", "test")
            .assertLogs();
    }

    public void testScrollWithSingleFieldExcepted() throws Exception {
        createUser("not_c", "read_test_a_and_b");

        actions.expectScrollMatchesAdmin("SELECT a, b FROM test ORDER BY a", "not_c", "SELECT * FROM test ORDER BY a");
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("test_admin", "test")
            /* Scrolling doesn't have to access the index again, at least not through sql.
             * If we asserted query and scroll logs then we would see the scoll. */
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expectSqlWithSyncLookup("not_c", "test")
            .expect(true, SQL_ACTION_NAME, "not_c", empty())
            .expect(true, SQL_ACTION_NAME, "not_c", empty())
            .assertLogs();
    }

    public void testQuerySingleFieldExceptionedWrongRequested() throws Exception {
        createUser("not_c", "read_test_a_and_b");

        actions.expectUnknownColumn("not_c", "SELECT c FROM test", "c");
        /* The user has permission to query the index but one of the
         * columns that they explicitly mention is hidden from them
         * by field level access control. This *looks* like a successful
         * query from the audit side because all the permissions checked
         * out but it failed in SQL because it couldn't compile the
         * query without the metadata for the missing field. */
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("not_c", "test")
            .assertLogs();
    }

    public void testQueryDocumentExclued() throws Exception {
        createUser("no_3s", "read_test_without_c_3");

        actions.expectMatchesAdmin("SELECT * FROM test WHERE c != 3 ORDER BY a", "no_3s", "SELECT * FROM test ORDER BY a");
        new AuditLogAsserter()
            .expectSqlWithSyncLookup("test_admin", "test")
            .expectSqlWithSyncLookup("no_3s", "test")
            .assertLogs();
    }

    public void testShowTablesWorksAsAdmin() throws Exception {
        actions.expectShowTables(Arrays.asList("bort", "test"), null);
        new AuditLogAsserter()
            .expectSqlWithAsyncLookup("test_admin", "bort", "test")
            .assertLogs();
    }

    public void testShowTablesWorksAsFullAccess() throws Exception {
        createUser("full_access", "read_all");

        actions.expectMatchesAdmin("SHOW TABLES", "full_access", "SHOW TABLES");
        new AuditLogAsserter()
            .expectSqlWithAsyncLookup("test_admin", "bort", "test")
            .expectSqlWithAsyncLookup("full_access", "bort", "test")
            .assertLogs();
    }

    public void testShowTablesWithNoAccess() throws Exception {
        createUser("no_access", "read_nothing");

        actions.expectForbidden("no_access", "SHOW TABLES");
        new AuditLogAsserter()
            .expect(false, SQL_ACTION_NAME, "no_access", empty())
            .assertLogs();
    }

    public void testShowTablesWithLimitedAccess() throws Exception {
        createUser("read_bort", "read_bort");

        actions.expectMatchesAdmin("SHOW TABLES LIKE 'bort'", "read_bort", "SHOW TABLES");
        new AuditLogAsserter()
            .expectSqlWithAsyncLookup("test_admin", "bort")
            .expectSqlWithAsyncLookup("read_bort", "bort")
            .assertLogs();
    }

    public void testShowTablesWithLimitedAccessUnaccessableIndex() throws Exception {
        createUser("read_bort", "read_bort");

        actions.expectMatchesAdmin("SHOW TABLES LIKE 'not_created'", "read_bort", "SHOW TABLES LIKE 'test'");
        new AuditLogAsserter()
            .expect(true, SQL_ACTION_NAME, "test_admin", empty())
            .expect(true, SQL_INDICES_ACTION_NAME, "test_admin", contains("*", "-*"))
            .expect(true, SQL_ACTION_NAME, "read_bort", empty())
            .expect(true, SQL_INDICES_ACTION_NAME, "read_bort", contains("*", "-*"))
            .assertLogs();
    }

    public void testDescribeWorksAsAdmin() throws Exception {
        Map<String, String> expected = new TreeMap<>();
        expected.put("a", "BIGINT");
        expected.put("b", "BIGINT");
        expected.put("c", "BIGINT");
        actions.expectDescribe(expected, null);
        new AuditLogAsserter()
            .expectSqlWithAsyncLookup("test_admin", "test")
            .assertLogs();
    }

    public void testDescribeWorksAsFullAccess() throws Exception {
        createUser("full_access", "read_all");

        actions.expectMatchesAdmin("DESCRIBE test", "full_access", "DESCRIBE test");
        new AuditLogAsserter()
            .expectSqlWithAsyncLookup("test_admin", "test")
            .expectSqlWithAsyncLookup("full_access", "test")
            .assertLogs();
    }

    public void testDescribeWithNoAccess() throws Exception {
        createUser("no_access", "read_nothing");

        actions.expectForbidden("no_access", "DESCRIBE test");
        new AuditLogAsserter()
            .expect(false, SQL_ACTION_NAME, "no_access", empty())
            .assertLogs();
    }

    public void testDescribeWithWrongAccess() throws Exception {
        createUser("wrong_access", "read_something_else");

        actions.expectForbidden("wrong_access", "DESCRIBE test");
        new AuditLogAsserter()
            /* This user has permission to run sql queries so they are
             * given preliminary authorization. */
            .expect(true, SQL_ACTION_NAME, "wrong_access", empty())
            /* But as soon as they attempt to resolve an index that
             * they don't have access to they get denied. */
            .expect(false, SQL_INDICES_ACTION_NAME, "wrong_access", hasItems("test"))
            .assertLogs();
    }

    public void testDescribeSingleFieldGranted() throws Exception {
        createUser("only_a", "read_test_a");

        actions.expectDescribe(singletonMap("a", "BIGINT"), "only_a");
        new AuditLogAsserter()
            .expectSqlWithAsyncLookup("only_a", "test")
            .assertLogs();
    }

    public void testDescribeSingleFieldExcepted() throws Exception {
        createUser("not_c", "read_test_a_and_b");

        Map<String, String> expected = new TreeMap<>();
        expected.put("a", "BIGINT");
        expected.put("b", "BIGINT");
        actions.expectDescribe(expected, "not_c");
        new AuditLogAsserter()
            .expectSqlWithAsyncLookup("not_c", "test")
            .assertLogs();
    }

    public void testDescribeDocumentExclued() throws Exception {
        createUser("no_3s", "read_test_without_c_3");

        actions.expectMatchesAdmin("DESCRIBE test", "no_3s", "DESCRIBE test");
        new AuditLogAsserter()
            .expectSqlWithAsyncLookup("test_admin", "test")
            .expectSqlWithAsyncLookup("no_3s", "test")
            .assertLogs();
    }

    protected final void createUser(String name, String role) throws IOException {
        XContentBuilder user = JsonXContent.contentBuilder().prettyPrint().startObject(); {
            user.field("password", "testpass");
            user.field("roles", role);
        }
        user.endObject();
        client().performRequest("PUT", "/_xpack/security/user/" + name, emptyMap(),
                new StringEntity(user.string(), ContentType.APPLICATION_JSON));
    }

    /**
     * Used to assert audit logs. Logs are asserted to match in any order because
     * we don't always scroll in the same order but each log checker must match a
     * single log and all logs must be matched.
     */
    protected final class AuditLogAsserter {
        private final List<Function<Map<String, Object>, Boolean>> logCheckers = new ArrayList<>();

        public AuditLogAsserter expectSqlWithAsyncLookup(String user, String... indices) {
            expect(true, SQL_ACTION_NAME, user, empty());
            expect(true, SQL_INDICES_ACTION_NAME, user, contains(indices));
            for (String index : indices) {
                expect(true, SQL_ACTION_NAME, user, hasItems(index));
            }
            return this;
        }

        public AuditLogAsserter expectSqlWithSyncLookup(String user, String... indices) {
            expect(true, SQL_ACTION_NAME, user, empty());
            for (String index : indices) {
                expect(true, SQL_ACTION_NAME, user, hasItems(index));
            }
            return this;
        }

        public AuditLogAsserter expect(boolean granted, String action, String principal,
                    Matcher<? extends Iterable<? extends String>> indicesMatcher) {
            String request;
            switch (action) {
            case SQL_ACTION_NAME:
                request = "SqlRequest";
                break;
            case SQL_INDICES_ACTION_NAME:
                request = "Request";
                break;
            default:
                throw new IllegalArgumentException("Unknown action [" + action + "]");
            }
            return expect(granted, action, principal, indicesMatcher, request);
        }

        public AuditLogAsserter expect(boolean granted, String action, String principal,
                    Matcher<? extends Iterable<? extends String>> indicesMatcher, String request) {
            String eventType = granted ? "access_granted" : "access_denied";
            logCheckers.add(m -> eventType.equals(m.get("event_type"))
                && action.equals(m.get("action"))
                && principal.equals(m.get("principal"))
                && indicesMatcher.matches(m.get("indices"))
                && request.equals(m.get("request"))
            );
            return this;
        }

        public void assertLogs() throws Exception {
            assertFalse("Previous test had an audit-related failure. All subsequent audit related assertions are bogus because we can't "
                    + "guarantee that we fully cleaned up after the last test.", auditFailure);
            try {
                assertBusy(() -> {
                    SecurityManager sm = System.getSecurityManager();
                    if (sm != null) {
                        sm.checkPermission(new SpecialPermission());
                    }
                    BufferedReader logReader = AccessController.doPrivileged((PrivilegedAction<BufferedReader>) () -> {
                        try {
                            return  Files.newBufferedReader(AUDIT_LOG_FILE, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    logReader.skip(auditLogWrittenBeforeTestStart);

                    List<Map<String, Object>> logs = new ArrayList<>();
                    String line;
                    Pattern logPattern = Pattern.compile(
                            ("PART PART PART origin_type=PART, origin_address=PART, "
                            + "principal=PART, (?:run_as_principal=PART, )?(?:run_by_principal=PART, )?"
                            + "action=\\[(.*?)\\], (?:indices=PART, )?request=PART")
                                .replace(" ", "\\s+").replace("PART", "\\[([^\\]]*)\\]"));
                    // fail(logPattern.toString());
                    while ((line = logReader.readLine()) != null) {
                        java.util.regex.Matcher m = logPattern.matcher(line);
                        if (false == m.matches()) {
                            throw new IllegalArgumentException("Unrecognized log: " + line);
                        }
                        int i = 1;
                        Map<String, Object> log = new HashMap<>();
                        /* We *could* parse the date but leaving it in the original format makes it
                        * easier to find the lines in the file that this log comes from. */
                        log.put("time", m.group(i++));
                        log.put("origin", m.group(i++));
                        String eventType = m.group(i++);
                        if (false == ("access_denied".equals(eventType) || "access_granted".equals(eventType))) {
                            continue;
                        }
                        log.put("event_type", eventType);
                        log.put("origin_type", m.group(i++));
                        log.put("origin_address", m.group(i++));
                        String principal = m.group(i++);
                        log.put("principal", principal);
                        log.put("run_as_principal", m.group(i++));
                        log.put("run_by_principal", m.group(i++));
                        String action = m.group(i++);
                        if (false == (SQL_ACTION_NAME.equals(action) || SQL_INDICES_ACTION_NAME.equals(action))) {
                            continue;
                        }
                        log.put("action", action);
                        // Use a sorted list for indices for consistent error reporting
                        List<String> indices = new ArrayList<>(Strings.splitStringByCommaToSet(m.group(i++)));
                        Collections.sort(indices);
                        if ("test_admin".equals(principal)) {
                            /* Sometimes we accidentally sneak access to the security tables. This is fine, SQL
                            * drops them from the interface. So we might have access to them, but we don't show
                            * them. */
                            indices.remove(".security");
                            indices.remove(".security-v6");
                        }
                        log.put("indices", indices);
                        log.put("request", m.group(i++));
                        logs.add(log);
                    }
                    List<Map<String, Object>> allLogs = new ArrayList<>(logs);
                    List<Integer> notMatching = new ArrayList<>();
                    checker: for (int c = 0; c < logCheckers.size(); c++) {
                        Function<Map<String, Object>, Boolean> logChecker = logCheckers.get(c);
                        for (Iterator<Map<String, Object>> logsItr = logs.iterator(); logsItr.hasNext();) {
                            Map<String, Object> log = logsItr.next();
                            if (logChecker.apply(log)) {
                                logsItr.remove();
                                continue checker;
                            }
                        }
                        notMatching.add(c);
                    }
                    if (false == notMatching.isEmpty()) {
                        fail("Some checkers " + notMatching + " didn't match any logs. All logs:" + logsMessage(allLogs)
                            + "\nRemaining logs:" + logsMessage(logs));
                    }
                    if (false == logs.isEmpty()) {
                        fail("Not all logs matched. Unmatched logs:" + logsMessage(logs));
                    }
                });
            } catch (AssertionError e) {
                auditFailure = true;
                logger.warn("Failed to find an audit log. Skipping remaining tests in this class after this the missing audit"
                        + "logs could turn up later.");
                throw e;
            }
        }

        private String logsMessage(List<Map<String, Object>> logs) {
            StringBuilder logsMessage = new StringBuilder();
            for (Map<String, Object> log : logs) {
                logsMessage.append('\n').append(log);
            }
            return logsMessage.toString();
        }
    }
}
