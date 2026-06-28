package io.github.drunkmages.networking;


/**
 * First packet the server sends back after the handshake:
 * type byte 0x01 + the assigned player id (i32).
 */
record WelcomePacket(int id) {}
