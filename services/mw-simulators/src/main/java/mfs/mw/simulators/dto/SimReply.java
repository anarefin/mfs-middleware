package mfs.mw.simulators.dto;

/** HTTP status and body for a controller to send; a String body is written as is. */
public record SimReply(int httpStatus, Object body) {}
