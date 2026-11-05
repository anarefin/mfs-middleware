package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of {@code QueryTransaction}: the transaction is the envelope's, optionally one command of it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryPayload(String queryCommandId) {}
