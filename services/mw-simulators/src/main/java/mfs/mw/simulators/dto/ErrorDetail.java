package mfs.mw.simulators.dto;

/** One entry of {@code details[]} in the error envelope (spec 5.3). */
public record ErrorDetail(String field, String issue) {}
