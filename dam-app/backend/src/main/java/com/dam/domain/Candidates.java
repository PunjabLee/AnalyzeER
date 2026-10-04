package com.dam.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * R3 structured candidates (PLAN §3.2 {@code meta_relation.candidate_targets}): the free-text
 * {@code ｜ER候选:…} notes appended to basis_raw were capped by its 300-char cut, so a candidate
 * target could be silently truncated away and could never be filtered or enumerated by the
 * confirmation workbench. Candidates therefore live in a dedicated JSON column as an auditable
 * entry list — one entry per named alternative, never rewritten into a pinned target (R4).
 *
 * <p>Entry format is owned by this class only; read/write are tolerant: malformed stored JSON
 * degrades to an empty list instead of failing ingestion or the detail page.
 */
public final class Candidates {

    /** @param target  real catalog table name (channel-1) or resolved candidate text (channel-2)
     * @param source    origin of this entry: 逻辑FK列 / ER证据摘录
     * @param doc       source document, nullable
     * @param why       why unresolved: 多父候选 / 自证否认 / 目标冲突 / 多态A/B, nullable */
    public record Candidate(String target, String source, String doc, String why) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Candidates() { }

    /** parse stored JSON; null/blank/malformed all yield an empty list (never throws). */
    public static List<Candidate> read(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Candidate> l = MAPPER.readValue(json, new TypeReference<>() { });
            return l == null ? new ArrayList<>() : new ArrayList<>(l);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** serialize; an empty list stores as NULL (keeps the column sparse). */
    public static String write(List<Candidate> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize candidates", e);
        }
    }

    /** append one entry idempotently (the identical entry is never duplicated). */
    public static String upsert(String json, Candidate c) {
        List<Candidate> list = read(json);
        if (!list.contains(c)) {
            list.add(c);
        }
        return write(list);
    }

    /**
     * Rebuild all entries owned by {@code source} from a fresh parse, leaving other sources'
     * entries (e.g. channel-1 ER candidates on a channel-2 refresh) untouched. An empty fresh
     * list removes that source's stale entries.
     */
    public static String replaceSource(String json, String source, List<Candidate> fresh) {
        List<Candidate> kept = read(json).stream()
                .filter(x -> !source.equals(x.source()))
                .toList();
        List<Candidate> list = new ArrayList<>(kept);
        for (Candidate c : fresh) {
            if (!list.contains(c)) {
                list.add(c);
            }
        }
        return write(list);
    }
}
