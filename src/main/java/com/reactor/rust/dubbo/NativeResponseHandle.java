package com.reactor.rust.dubbo;

public record NativeResponseHandle(
        int nativeId,
        int statusCode,
        String contentType,
        String headers
) {
    public NativeResponseHandle {
        if (nativeId <= 0) {
            throw new IllegalArgumentException("nativeId must be positive");
        }
        if (statusCode <= 0) {
            statusCode = 200;
        }
        contentType = contentType == null ? "" : contentType;
        headers = headers == null ? "" : headers;
    }

    public static NativeResponseHandle json(int nativeId) {
        return new NativeResponseHandle(nativeId, 200, "application/json; charset=utf-8", "");
    }
}
