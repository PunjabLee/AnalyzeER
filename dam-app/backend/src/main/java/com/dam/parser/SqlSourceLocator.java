package com.dam.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the location of a DDL .sql file for ingestion/testing without hard-coding
 * an absolute path. Order: explicit arg -> DAM_DDL_PATH env -> common repo-relative candidates.
 */
public final class SqlSourceLocator {

    private SqlSourceLocator() { }

    public static Path locate(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
        }
        String env = System.getenv("DAM_DDL_PATH");
        if (env != null && Files.isRegularFile(Path.of(env))) {
            return Path.of(env).toAbsolutePath();
        }
        // candidates relative to the backend module working dir (dam-app/backend)
        String[] candidates = {
                "../../test_erp.sql",
                "../test_erp.sql",
                "test_erp.sql",
                "../../../test_erp.sql"
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                try {
                    return p.toRealPath();
                } catch (IOException ignored) {
                    return p.toAbsolutePath();
                }
            }
        }
        throw new IllegalStateException(
                "test_erp.sql not found. Set env DAM_DDL_PATH or pass an explicit path.");
    }
}
