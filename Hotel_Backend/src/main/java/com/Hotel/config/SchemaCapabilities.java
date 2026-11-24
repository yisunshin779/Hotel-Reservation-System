package com.Hotel.config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the PostgreSQL catalog for optional schema objects. Positive answers are cached because
 * migrations only add objects; negative answers are re-checked so a schema created after start-up
 * (test fixtures, a migration applied by an operator) is picked up.
 */
@Component
public class SchemaCapabilities {
    private final JdbcTemplate jdbc;
    private final Set<String> present = ConcurrentHashMap.newKeySet();

    public SchemaCapabilities(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasTable(String table) {
        String key = "t:" + table;
        if (present.contains(key)) {
            return true;
        }
        Boolean found = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "hotel." + table);
        if (Boolean.TRUE.equals(found)) {
            present.add(key);
            return true;
        }
        return false;
    }

    public boolean hasColumn(String table, String column) {
        String key = "c:" + table + "." + column;
        if (present.contains(key)) {
            return true;
        }
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_schema = 'hotel' AND table_name = ? AND column_name = ?)
                """, Boolean.class, table, column);
        if (Boolean.TRUE.equals(found)) {
            present.add(key);
            return true;
        }
        return false;
    }

    /** {@code signature} is a function in schema hotel with its argument types, e.g. {@code expire_holds()}. */
    public boolean hasFunction(String signature) {
        String key = "f:" + signature;
        if (present.contains(key)) {
            return true;
        }
        Boolean found = jdbc.queryForObject("SELECT to_regprocedure(?) IS NOT NULL", Boolean.class, "hotel." + signature);
        if (Boolean.TRUE.equals(found)) {
            present.add(key);
            return true;
        }
        return false;
    }

    /**
     * True when the body of function {@code signature} references {@code callee}. Never cached: re-running
     * an older script (for example 02_operations.sql) can replace a migrated body with a previous one.
     */
    public boolean functionCalls(String signature, String callee) {
        Boolean found = jdbc.queryForObject("""
                SELECT coalesce((SELECT position(? IN p.prosrc) > 0 FROM pg_proc p
                                 WHERE p.oid = to_regprocedure(?)), false)
                """, Boolean.class, callee, "hotel." + signature);
        return Boolean.TRUE.equals(found);
    }

    /** V11 bridge between hotel.account and hotel.users. */
    public boolean hasAccountLinks() {
        return hasTable("account_user_links") && hasTable("account");
    }
}
