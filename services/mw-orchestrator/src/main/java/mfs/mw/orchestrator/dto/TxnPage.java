package mfs.mw.orchestrator.dto;

import java.util.List;

/** One page of search results, newest first (ticket 35). */
public record TxnPage(List<TxnView> items, int limit, int offset) {}
