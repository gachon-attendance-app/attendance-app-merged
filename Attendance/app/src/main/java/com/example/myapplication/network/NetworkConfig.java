package com.example.myapplication.network;

/**
 * 네트워크 엔드포인트 단일 진실원천.
 * 서버 IP/포트 변경 시 HOST 한 줄만 수정.
 *
 * Retrofit과 Socket.IO가 같은 host를 보지만 형식이 살짝 다름:
 *   - Retrofit:  baseUrl은 끝 슬래시 필수 ("http://host:port/")
 *   - Socket.IO: IO.socket()은 끝 슬래시 없음 ("http://host:port")
 */
public final class NetworkConfig {
    private NetworkConfig() {}

    // 변경 시 여기 한 줄만 (다른 두 상수는 자동 파생)
    private static final String HOST = "http://172.30.1.12:3000";

    /** Retrofit/OkHttp용. 끝 슬래시 포함. */
    public static final String HTTP_BASE_URL = HOST + "/";

    /** Socket.IO용. 끝 슬래시 없음. */
    public static final String SOCKET_BASE_URL = HOST;
}
