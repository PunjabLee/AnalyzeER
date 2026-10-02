package com.dam.parser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates er-model documents (00-总览 / 03-逻辑数据模型) without hard-coded absolute paths.
 * Order: explicit arg -> env (DAM_ERMODEL_DIR) -> common repo-relative candidates.
 */
public final class ErModelSourceLocator {

    private ErModelSourceLocator() { }

    /** Root dir of er-model documents (contains 00-总览与分组清单.md). */
    public static Path locateDir(String explicit) {
        if (explicit != null && !explicit.isBlank() && Files.isDirectory(Path.of(explicit))) {
            return Path.of(explicit).toAbsolutePath();
        }
        String env = System.getenv("DAM_ERMODEL_DIR");
        if (env != null && Files.isDirectory(Path.of(env))) {
            return Path.of(env).toAbsolutePath();
        }
        String[] candidates = {"../../er-model", "../er-model", "er-model"};
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p.resolve("00-总览与分组清单.md"))) {
                return p.toAbsolutePath();
            }
        }
        throw new IllegalStateException(
                "er-model dir not found; set DAM_ERMODEL_DIR or pass an explicit path");
    }
}
