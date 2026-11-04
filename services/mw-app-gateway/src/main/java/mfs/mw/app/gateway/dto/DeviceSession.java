package mfs.mw.app.gateway.dto;

/** Answer of a device binding or session open: the device and the session's idle timeout. */
public record DeviceSession(String deviceId, long idleTimeoutSeconds) {}
